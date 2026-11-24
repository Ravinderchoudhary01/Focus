package com.focusguard.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Detection rules for one app. Kept in rules.json (not hard-coded) because
 * YouTube and Instagram change their screen IDs from time to time.
 *
 * All ID lists are matched as substrings of the view ID name (the part after ":id/").
 */
data class AppRule(
    val pkg: String,
    val name: String,
    /** "shorts" or "reels" - decides which blocking toggle applies. */
    val kind: String,
    val kindLabel: String,
    /** Time-limit bucket for the rest of the app: "yt_long" or "ig_feed". */
    val bucket: String,
    val bucketLabel: String,
    /** Visible views with these IDs mean the Shorts/Reels screen is open. */
    val blockIds: List<String>,
    /** Same, but only when the view is also selected (e.g. a bottom-bar tab). */
    val blockSelectedIds: List<String>,
    /** Screens with these IDs (e.g. chats) never count toward the time limit. */
    val exemptIds: List<String>,
    /** Button that opens chats; clicked when the daily limit is reached. */
    val inboxIds: List<String>,
)

class Rules private constructor(
    val version: Int,
    private val byPackage: Map<String, AppRule>,
) {
    fun forPackage(pkg: String?): AppRule? = if (pkg == null) null else byPackage[pkg]

    companion object {
        /**
         * Loads filesDir/rules.json if present and valid (so rules can be updated
         * without rebuilding, e.g. via adb), otherwise the bundled assets/rules.json.
         */
        fun load(context: Context): Rules {
            val override = File(context.filesDir, "rules.json")
            if (override.exists()) {
                try {
                    return parse(override.readText())
                } catch (_: Exception) {
                    // Malformed override: fall back to the bundled rules.
                }
            }
            val text = context.assets.open("rules.json").bufferedReader().use { it.readText() }
            return parse(text)
        }

        private fun parse(text: String): Rules {
            val root = JSONObject(text)
            val apps = root.getJSONArray("apps")
            val map = HashMap<String, AppRule>()
            for (i in 0 until apps.length()) {
                val o = apps.getJSONObject(i)
                val rule = AppRule(
                    pkg = o.getString("package"),
                    name = o.getString("name"),
                    kind = o.getString("kind"),
                    kindLabel = o.optString("kindLabel", o.getString("kind")),
                    bucket = o.getString("bucket"),
                    bucketLabel = o.optString("bucketLabel", o.getString("name")),
                    blockIds = o.optJSONArray("blockIds").toStrings(),
                    blockSelectedIds = o.optJSONArray("blockSelectedIds").toStrings(),
                    exemptIds = o.optJSONArray("exemptIds").toStrings(),
                    inboxIds = o.optJSONArray("inboxIds").toStrings(),
                )
                map[rule.pkg] = rule
            }
            return Rules(root.optInt("version", 1), map)
        }

        private fun JSONArray?.toStrings(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { getString(it) }
        }
    }
}
