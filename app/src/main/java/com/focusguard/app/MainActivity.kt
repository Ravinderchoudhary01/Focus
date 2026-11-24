@file:OptIn(ExperimentalMaterial3Api::class)

package com.focusguard.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val COOLDOWN_SECONDS = 30

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(Modifier.fillMaxSize()) { FocusGuardScreen() }
            }
        }
    }
}

@Composable
private fun FocusGuardScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }

    // Re-read prefs every second so stats and the service status stay fresh.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE")
    val refresh = tick

    val serviceOn = isServiceEnabled(ctx)

    // Anything that makes blocking weaker goes through a 30 second pause.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var timePicker by remember { mutableStateOf<String?>(null) }

    val set: (() -> Unit) -> Unit = { action ->
        action()
        tick++
    }
    val gate: (() -> Unit) -> Unit = { action ->
        if (prefs.enabled) {
            pending = {
                action()
                tick++
            }
        } else {
            action()
            tick++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("FocusGuard", style = MaterialTheme.typography.headlineMedium)

        if (!serviceOn) {
            SectionCard("Step 1: turn on the blocker") {
                Text(
                    "FocusGuard uses Android's Accessibility Service only to notice when " +
                        "the Shorts or Reels screen is open in YouTube and Instagram. It reads " +
                        "screen element IDs, never your messages, text or passwords, and " +
                        "nothing leaves your phone.",
                )
                Text(
                    "Open Accessibility settings, find FocusGuard under Installed apps and " +
                        "switch it on. If the switch is greyed out: Settings > Apps > FocusGuard " +
                        "> menu (three dots) > Allow restricted settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Open Accessibility settings") }
            }
        } else {
            SectionCard("Status") {
                Text(
                    when {
                        !prefs.enabled -> "Paused"
                        prefs.isActiveNow() -> "Active now"
                        else -> "On, but outside your scheduled hours"
                    },
                )
            }
        }

        SectionCard("Protection") {
            SwitchRow("FocusGuard on", null, prefs.enabled) { on ->
                if (on) set { prefs.enabled = true } else gate {
                    prefs.enabled = false
                    prefs.markOff()
                }
            }
            SwitchRow(
                "Block YouTube Shorts",
                "Long videos, search and subscriptions stay open",
                prefs.blockShorts,
            ) { on ->
                if (on) set { prefs.blockShorts = true } else gate {
                    prefs.blockShorts = false
                    prefs.markOff()
                }
            }
            SwitchRow(
                "Block Instagram Reels",
                "Chats, posts and Stories stay open",
                prefs.blockReels,
            ) { on ->
                if (on) set { prefs.blockReels = true } else gate {
                    prefs.blockReels = false
                    prefs.markOff()
                }
            }
        }

        SectionCard("Daily time limits") {
            LimitRow(
                title = "Instagram feed and posts",
                subtitle = "Chats are never counted or blocked",
                minutes = prefs.limitMin(Prefs.BUCKET_IG),
                onTighten = { v -> set { prefs.setLimitMin(Prefs.BUCKET_IG, v) } },
                onLoosen = { v -> gate { prefs.setLimitMin(Prefs.BUCKET_IG, v) } },
            )
            LimitRow(
                title = "YouTube (non-Shorts)",
                subtitle = "Optional cap on long videos and browsing",
                minutes = prefs.limitMin(Prefs.BUCKET_YT),
                onTighten = { v -> set { prefs.setLimitMin(Prefs.BUCKET_YT, v) } },
                onLoosen = { v -> gate { prefs.setLimitMin(Prefs.BUCKET_YT, v) } },
            )
        }

        SectionCard("Schedule") {
            SwitchRow(
                "Only enforce during set hours",
                "Off means blocking and limits run all day",
                prefs.scheduleEnabled,
            ) { on ->
                if (on) set { prefs.scheduleEnabled = true } else gate { prefs.scheduleEnabled = false }
            }
            if (prefs.scheduleEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { timePicker = "start" }) {
                        Text("From ${formatMinutes(prefs.scheduleStartMin)}")
                    }
                    OutlinedButton(onClick = { timePicker = "end" }) {
                        Text("To ${formatMinutes(prefs.scheduleEndMin)}")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { index, label ->
                        val day = index + 1
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = day in prefs.scheduleDays,
                            onClick = {
                                val current = prefs.scheduleDays
                                val next = if (day in current) current - day else current + day
                                if (next.isNotEmpty()) gate { prefs.scheduleDays = next }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        SectionCard("Today") {
            Text("Shorts blocked: ${prefs.blocksToday(Prefs.KIND_SHORTS)}")
            Text("Reels blocked: ${prefs.blocksToday(Prefs.KIND_REELS)}")
            UsageLine("Instagram feed and posts", prefs.usageMin(Prefs.BUCKET_IG), prefs.limitMin(Prefs.BUCKET_IG))
            UsageLine("YouTube (non-Shorts)", prefs.usageMin(Prefs.BUCKET_YT), prefs.limitMin(Prefs.BUCKET_YT))
            Text(
                "Streak: ${prefs.streakDays()} days without hitting a limit or pausing protection",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("Advanced") {
            SwitchRow(
                "Debug logging",
                "Writes the screen IDs it sees to Logcat (tag FocusGuard) so you can fix rules.json",
                prefs.debug,
            ) { on -> set { prefs.debug = on } }
        }
    }

    timePicker?.let { which ->
        TimeDialog(
            initialMinutes = if (which == "start") prefs.scheduleStartMin else prefs.scheduleEndMin,
            onDismiss = { timePicker = null },
        ) { minutes ->
            timePicker = null
            gate {
                if (which == "start") prefs.scheduleStartMin = minutes else prefs.scheduleEndMin = minutes
            }
        }
    }

    pending?.let { action ->
        CooldownDialog(
            seconds = COOLDOWN_SECONDS,
            onConfirm = {
                pending = null
                action()
            },
            onCancel = { pending = null },
        )
    }
}

// ---- Building blocks ---------------------------------------------------------

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 0 minutes = no limit. Lower or enable = tighten (instant). Raise or disable = loosen (30 s pause). */
@Composable
private fun LimitRow(
    title: String,
    subtitle: String,
    minutes: Int,
    onTighten: (Int) -> Unit,
    onLoosen: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow(title, subtitle, minutes > 0) { on ->
            if (on) onTighten(20) else onLoosen(0)
        }
        if (minutes > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onTighten((minutes - 5).coerceAtLeast(5)) }) { Text("-5") }
                Text("$minutes min / day")
                OutlinedButton(onClick = { onLoosen((minutes + 5).coerceAtMost(240)) }) { Text("+5") }
            }
        }
    }
}

@Composable
private fun UsageLine(label: String, usedMin: Int, limitMin: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (limitMin > 0) "$label: $usedMin / $limitMin min" else "$label: $usedMin min")
        if (limitMin > 0) {
            LinearProgressIndicator(
                progress = { (usedMin.toFloat() / limitMin).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TimeDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}

@Composable
private fun CooldownDialog(seconds: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var left by remember { mutableIntStateOf(seconds) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1000)
            left--
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pause before loosening") },
        text = {
            Text(
                if (left > 0) {
                    "Wait $left s. Do you really need this change right now?"
                } else {
                    "Ready. Confirm only if you still want this change."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = left == 0) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Keep it strict") } },
    )
}

private fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun isServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val component = ComponentName(context, BlockerService::class.java)
    val candidates = listOf(component.flattenToString(), component.flattenToShortString())
    return enabled.split(':').any { entry -> candidates.any { it.equals(entry, ignoreCase = true) } }
}
