package com.focusguard.app

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** All settings and daily stats, stored locally in SharedPreferences. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("focusguard", Context.MODE_PRIVATE)

    init {
        if (!sp.contains("first_run")) sp.edit().putString("first_run", today()).apply()
    }

    // ---- Settings ----------------------------------------------------------

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    var blockShorts: Boolean
        get() = sp.getBoolean("block_shorts", true)
        set(v) { sp.edit().putBoolean("block_shorts", v).apply() }

    var blockReels: Boolean
        get() = sp.getBoolean("block_reels", true)
        set(v) { sp.edit().putBoolean("block_reels", v).apply() }

    var debug: Boolean
        get() = sp.getBoolean("debug", false)
        set(v) { sp.edit().putBoolean("debug", v).apply() }

    var scheduleEnabled: Boolean
        get() = sp.getBoolean("schedule_enabled", false)
        set(v) { sp.edit().putBoolean("schedule_enabled", v).apply() }

    /** Minutes after midnight. */
    var scheduleStartMin: Int
        get() = sp.getInt("schedule_start", 9 * 60)
        set(v) { sp.edit().putInt("schedule_start", v).apply() }

    var scheduleEndMin: Int
        get() = sp.getInt("schedule_end", 18 * 60)
        set(v) { sp.edit().putInt("schedule_end", v).apply() }

    /** 1 = Monday ... 7 = Sunday (java.time.DayOfWeek values). */
    var scheduleDays: Set<Int>
        get() = sp.getString("schedule_days", "1,2,3,4,5,6,7")!!
            .split(",").mapNotNull { it.toIntOrNull() }.toSet()
        set(v) { sp.edit().putString("schedule_days", v.sorted().joinToString(",")).apply() }

    /** Daily limit in minutes for a bucket. 0 = no limit. */
    fun limitMin(bucket: String): Int =
        sp.getInt("limit_$bucket", if (bucket == BUCKET_IG) 20 else 0)

    fun setLimitMin(bucket: String, minutes: Int) {
        sp.edit().putInt("limit_$bucket", minutes).apply()
    }

    fun blocks(kind: String): Boolean = if (kind == KIND_SHORTS) blockShorts else blockReels

    /** True when blocking should be enforced right now (master switch + schedule). */
    fun isActiveNow(now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (!enabled) return false
        if (!scheduleEnabled) return true
        val days = scheduleDays
        val today = now.dayOfWeek.value
        val yesterday = if (today == 1) 7 else today - 1
        val minute = now.hour * 60 + now.minute
        val start = scheduleStartMin
        val end = scheduleEndMin
        return if (start <= end) {
            today in days && minute >= start && minute < end
        } else {
            // Overnight window, e.g. 22:00 -> 06:00
            (today in days && minute >= start) || (yesterday in days && minute < end)
        }
    }

    // ---- Daily stats -------------------------------------------------------

    fun addUsage(bucket: String, ms: Long) {
        if (ms <= 0) return
        val key = "use_${bucket}_${today()}"
        sp.edit().putLong(key, sp.getLong(key, 0L) + ms).apply()
    }

    fun usageMs(bucket: String): Long = sp.getLong("use_${bucket}_${today()}", 0L)

    fun usageMin(bucket: String): Int = (usageMs(bucket) / 60_000L).toInt()

    fun addBlock(kind: String) {
        val key = "blocks_${kind}_${today()}"
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
    }

    fun blocksToday(kind: String): Int = sp.getInt("blocks_${kind}_${today()}", 0)

    fun warned(bucket: String): Boolean = sp.getBoolean("warned_${bucket}_${today()}", false)

    fun setWarned(bucket: String) {
        sp.edit().putBoolean("warned_${bucket}_${today()}", true).apply()
    }

    fun markLimitHit() {
        sp.edit().putString("last_limit_hit", today()).apply()
    }

    /** Called when the user pauses protection, which resets the streak. */
    fun markOff() {
        sp.edit().putString("last_off", today()).apply()
    }

    /** Days since setup, the last limit hit, or the last time protection was paused. */
    fun streakDays(): Int {
        val dates = listOf("first_run", "last_limit_hit", "last_off").mapNotNull { key ->
            sp.getString(key, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        }
        val start = dates.maxByOrNull { it.toEpochDay() } ?: LocalDate.now()
        return ChronoUnit.DAYS.between(start, LocalDate.now()).toInt().coerceAtLeast(0)
    }

    companion object {
        const val KIND_SHORTS = "shorts"
        const val KIND_REELS = "reels"
        const val BUCKET_IG = "ig_feed"
        const val BUCKET_YT = "yt_long"

        fun today(): String = LocalDate.now().toString()
    }
}
