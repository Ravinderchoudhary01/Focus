package com.focusguard.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Watches YouTube and Instagram. When the Shorts / Reels screen is visible it
 * sends the user back; everything else stays usable. Time spent on the rest of
 * the app is counted against the daily limit (chats are exempt).
 *
 * Only view IDs and selected/visible flags are read - never text content.
 */
class BlockerService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var rules: Rules
    private lateinit var powerManager: PowerManager
    private val handler = Handler(Looper.getMainLooper())

    // Event throttling: scan at most every SCAN_DELAY_MS, always with the latest event's app.
    private var scanQueued = false
    private var pendingRule: AppRule? = null
    private val scanRunnable = Runnable {
        scanQueued = false
        pendingRule?.let { scan(it) }
    }

    // Time tracking.
    private var currentBucket: String? = null
    private var lastTickMs = 0L
    private var tickerRunning = false
    private val ticker = object : Runnable {
        override fun run() {
            tickerRunning = false
            val bucket = currentBucket ?: return
            val rule = rules.forPackage(rootInActiveWindow?.packageName?.toString())
            if (rule == null || !powerManager.isInteractive || !prefs.isActiveNow()) {
                stopTracking()
                return
            }
            flush()
            warnIfClose(bucket)
            scan(rule) // may enforce the limit or stop tracking
            if (currentBucket != null && !tickerRunning) {
                tickerRunning = true
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    // Loop protection.
    private var lastActionMs = 0L
    private val recentBlocks = mutableListOf<Long>()
    private var lastDebugSignature = ""

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopTracking()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        rules = Rules.load(this)
        powerManager = getSystemService(PowerManager::class.java)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        rules = Rules.load(this) // pick up an updated rules.json
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rule = rules.forPackage(event?.packageName?.toString()) ?: return
        pendingRule = rule
        if (!scanQueued) {
            scanQueued = true
            handler.postDelayed(scanRunnable, SCAN_DELAY_MS)
        }
    }

    override fun onInterrupt() {
        stopTracking()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopTracking()
        runCatching { unregisterReceiver(screenOffReceiver) }
        return super.onUnbind(intent)
    }

    // ---- Screen inspection --------------------------------------------------

    private fun scan(rule: AppRule) {
        if (!prefs.isActiveNow()) {
            stopTracking()
            return
        }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != rule.pkg) return

        var blocked = false
        var exempt = false
        val seen = if (prefs.debug) sortedSetOf<String>() else null

        walk(root) { node ->
            val fullId = node.viewIdResourceName
            if (fullId != null) {
                val id = fullId.substringAfter(":id/")
                seen?.add(id)
                if (node.isVisibleToUser && rule.blockIds.any { id.contains(it) }) blocked = true
                if (node.isSelected && rule.blockSelectedIds.any { id.contains(it) }) blocked = true
                if (rule.exemptIds.any { id.contains(it) }) exempt = true
            }
            false // keep walking
        }

        if (seen != null) logIds(rule, seen, blocked, exempt)

        when {
            blocked && prefs.blocks(rule.kind) -> {
                stopTracking()
                block(rule)
            }
            exempt -> stopTracking()
            else -> {
                track(rule.bucket)
                if (limitReached(rule.bucket)) enforceLimit(rule, root)
            }
        }
    }

    /** Depth-first walk over the node tree. [visit] returns true to stop early. */
    private fun walk(
        root: AccessibilityNodeInfo,
        visit: (AccessibilityNodeInfo) -> Boolean,
    ) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var count = 0
        while (stack.isNotEmpty() && count < MAX_NODES) {
            val node = stack.removeLast()
            count++
            if (visit(node)) return
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
    }

    // ---- Actions -------------------------------------------------------------

    private fun block(rule: AppRule) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionMs < ACTION_DEBOUNCE_MS) return
        lastActionMs = now

        recentBlocks.add(now)
        recentBlocks.removeAll { now - it > 4_000 }
        if (recentBlocks.size >= 3) {
            // Back isn't getting us out (e.g. Shorts is the app's only screen): go Home.
            performGlobalAction(GLOBAL_ACTION_HOME)
            recentBlocks.clear()
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        prefs.addBlock(rule.kind)
        toast("${rule.kindLabel} blocked")
    }

    private fun enforceLimit(rule: AppRule, root: AccessibilityNodeInfo) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionMs < LIMIT_DEBOUNCE_MS) return
        lastActionMs = now
        prefs.markLimitHit()

        // Chats must stay reachable: jump to the inbox if we can, otherwise go Home.
        if (clickInbox(root, rule)) {
            toast("${rule.bucketLabel} limit reached - chats are still open")
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
            toast("${rule.bucketLabel} daily limit reached")
        }
        stopTracking()
    }

    private fun clickInbox(root: AccessibilityNodeInfo, rule: AppRule): Boolean {
        if (rule.inboxIds.isEmpty()) return false
        var clicked = false
        walk(root) { node ->
            val id = node.viewIdResourceName?.substringAfter(":id/")
            if (id != null && rule.inboxIds.any { id.contains(it) }) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clicked = true
                }
                true // stop walking
            } else {
                false
            }
        }
        return clicked
    }

    // ---- Time tracking -------------------------------------------------------

    private fun track(bucket: String) {
        val now = SystemClock.elapsedRealtime()
        if (currentBucket != bucket) {
            flush()
            currentBucket = bucket
            lastTickMs = now
        }
        if (!tickerRunning) {
            tickerRunning = true
            handler.postDelayed(ticker, TICK_MS)
        }
    }

    /** Adds the time since the last flush to the current bucket. */
    private fun flush() {
        val bucket = currentBucket ?: return
        val now = SystemClock.elapsedRealtime()
        prefs.addUsage(bucket, now - lastTickMs)
        lastTickMs = now
    }

    private fun stopTracking() {
        flush()
        currentBucket = null
        handler.removeCallbacks(ticker)
        tickerRunning = false
    }

    private fun limitReached(bucket: String): Boolean {
        val limit = prefs.limitMin(bucket)
        return limit > 0 && prefs.usageMs(bucket) >= limit * 60_000L
    }

    private fun warnIfClose(bucket: String) {
        val limit = prefs.limitMin(bucket)
        if (limit <= 0 || prefs.warned(bucket)) return
        val used = prefs.usageMs(bucket)
        if (used >= limit * 60_000L * 3 / 4 && used < limit * 60_000L) {
            prefs.setWarned(bucket)
            toast("${used / 60_000} of $limit min used today")
        }
    }

    // ---- Helpers --------------------------------------------------------------

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun logIds(rule: AppRule, ids: Set<String>, blocked: Boolean, exempt: Boolean) {
        val signature = "${rule.pkg}|$blocked|$exempt|$ids"
        if (signature == lastDebugSignature) return
        lastDebugSignature = signature
        Log.d(TAG, "${rule.name} blocked=$blocked exempt=$exempt ids=$ids")
    }

    companion object {
        private const val TAG = "FocusGuard"
        private const val SCAN_DELAY_MS = 250L
        private const val TICK_MS = 5_000L
        private const val ACTION_DEBOUNCE_MS = 800L
        private const val LIMIT_DEBOUNCE_MS = 1_500L
        private const val MAX_NODES = 2_000
    }
}
