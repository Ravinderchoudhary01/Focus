# FocusGuard

Android app that blocks **YouTube Shorts** and **Instagram Reels** while leaving
long YouTube videos, Instagram chats, posts and Stories usable (posts/feed on a
daily time limit). Kotlin + Jetpack Compose, no backend, everything stays on the phone.

## Build and install

1. Install Android Studio (Koala or newer) and open this folder. Let Gradle sync.
2. Plug in your phone (USB debugging on) and press Run, or use Build > Build APK(s).
3. On the phone open FocusGuard and follow "Step 1": Accessibility settings >
   Installed apps > FocusGuard > On.
   - If the switch is greyed out (Android 13+, sideloaded app): Settings > Apps >
     FocusGuard > menu (three dots) > **Allow restricted settings**, then try again.

## What it does

| Feature | How |
|---|---|
| Block Shorts / Reels | Accessibility service sees the Shorts / Reels screen, presses Back (Home if Back doesn't get you out) |
| Feed / posts time limit | Time on the rest of Instagram (default 20 min/day) is counted; at the limit it opens your inbox so chats still work, otherwise goes Home |
| YouTube cap (optional) | Same idea for non-Shorts YouTube, off by default |
| Schedule | Enforce only on chosen days/hours (overnight windows work) |
| 30 s pause | Turning anything off, raising a limit or editing the schedule needs a 30-second wait and a confirm |
| Stats | Blocks today, minutes used, streak (days since setup / last limit hit / last pause) |

Only view IDs and visible/selected flags are read. No text, messages or passwords.

## IMPORTANT: the screen IDs need checking

The IDs in `app/src/main/assets/rules.json` are best guesses. They were **not
tested on a device**, and YouTube/Instagram change them between versions. To tune them:

1. In the app: Advanced > turn on **Debug logging**.
2. Connect the phone, run `adb logcat -s FocusGuard`.
3. Open a Short / Reel and note which IDs appear that don't appear on the normal
   Home screen. Do the same for an Instagram chat (these become `exemptIds`).
4. Edit `rules.json` and rebuild, **or** push an override without rebuilding:
   `adb push rules.json /sdcard/` then copy it into the app's private files:
   `adb shell run-as com.focusguard.app cp /sdcard/rules.json files/rules.json`
   (works on debug builds), then switch the Accessibility service off and on.

Keep `blockIds` specific. Too-broad names (e.g. anything containing "reel") can match
the Shorts shelf on YouTube Home and block the normal feed.

## Known limits

- Reels opened from inside a chat use the same viewer, so they are blocked too.
- You can still switch the Accessibility service off in system settings; the 30 s pause
  only protects the in-app controls. A PIN lock is the next step.
- Instagram Lite, YouTube Vanced etc. need their package added to
  `res/xml/accessibility_service_config.xml` and to `rules.json`.
- Android only. iOS doesn't allow blocking one screen inside another app.

## Project layout

```
app/src/main/java/com/focusguard/app/
  BlockerService.kt   accessibility service (detection, blocking, time tracking)
  Rules.kt            loads rules.json
  Prefs.kt            settings, schedule check, daily stats
  MainActivity.kt     Compose UI
app/src/main/assets/rules.json
app/src/main/res/xml/accessibility_service_config.xml
```
