# Workout Tracker

A small Android app for logging workout sessions, written in Kotlin with Jetpack
Compose. Everything is stored locally in Room; the one "advanced" piece is
automatic background sync to a private folder in your Google Drive.

## Features

- **Three tabs** — Workouts, History, Settings.
- **Log sessions** — name, date, free-text notes.
- **Exercises and sets** — a session is a list of exercises; open one for its
  sets. Tick each set off as you do it, add or remove sets, and edit the
  numbers in place. Adding a set repeats the last one for that exercise, so
  planning 5×5 is four taps after the first. The session list shows how far
  through each exercise you are, and finishing one offers the next.
- **Reordering** — drag an exercise or a set by its ⋮ handle to move it, or use
  Move up / Move down in the menu behind the same button. Dropping an exercise
  from the session is in that menu too.
- **Exercise metrics** — not everything is reps and kilos, so each exercise
  says what to ask for: weight and reps, reps alone (pull-ups, push-ups), time
  (plank, wall sit, dead hang), or distance and time (running, cycling,
  rowing). The set row shows only those fields, and volume, best set, totals
  and the progress chart each read in the right units. The built-in exercises
  come with a sensible choice; the ruler icon on an exercise's screen changes
  it, and a new custom exercise picks one when you create it.
- **Start from a previous session** — a new session offers the ones you have
  already logged, newest first, each listed with its exercises. Picking one
  copies the whole thing — exercises, order, reps, weights, durations,
  distances — as a plan with nothing ticked off, and takes that session's name
  unless you have already typed one. Offered only while the session is empty.
- **Copy last time** — inside an exercise, what you did in its last session,
  and one tap to start from it. Copying replaces the sets you have not ticked
  yet and leaves the ticked ones alone, so it is safe mid-exercise.
- **Built-in exercise library** — 110 common exercises across 13 muscle
  groups, cardio included, searchable and filterable. Anything missing can be
  added as a custom exercise with its own muscle group and metric; custom
  exercises sync, the built-in list does not (it ships with the app, so syncing
  it would be pure duplication).
- **This week** — a card above the session list: how many sessions so far this
  week and what they came to, with last week underneath for comparison, because
  three sessions is either a good week or a slow one depending on what came
  before. Weeks run Monday to Sunday, and a session counts once it has a set
  in it.
- **Personal bests** — your best set for an exercise is marked, in the session
  you did it and in its history. The best, not every set that ever led: one
  badge per exercise, and it moves when you beat it. What counts as best
  follows the metric — weight then reps, distance then speed, or simply the
  most reps or the longest hold. Only sets you actually ticked off are
  eligible, so a planned 200 kg claims nothing.
- **History per exercise** — every exercise you have trained, filterable by
  muscle group. Open one for its sessions, best set and total — volume for a
  lift, distance for a run, time for a hold.
- **Export** — Settings writes your whole log to a CSV wherever you point the
  file picker: one row per set, with raw numbers rather than formatted ones so
  a spreadsheet can sum and chart them. Worth having, because the Drive backup
  below lives in a folder you cannot browse.
- **Backup and restore** — the whole database as a JSON file, in the same
  format the Drive sync uses, written wherever you point the picker. Restoring
  *merges* rather than replaces: for each row, whichever copy was edited more
  recently wins, deletions included. So a stale backup cannot undo work done
  since it was taken, and restoring a backup you already have is a no-op. A
  backup from a newer version of the app is refused rather than partly read.
- **Progress chart** — per exercise, a line across sessions of whichever
  measures suit it: top set or volume for a lift, longest or total time for a
  hold, furthest or total distance for cardio. Points are spaced by date, so a
  month off looks like a month off rather than steady training. Appears once
  there are two sessions to compare.
- **Rest timer** — starts on its own when you tick a set off, counts down in a
  bar at the bottom of the screen, and buzzes when the rest is up. Adjustable
  by 15 seconds either way, skippable, and set to any length you like — or
  switched off — in Settings. Individual exercises can override that from the
  clock icon on their screen, because deadlifts need longer than curls; the
  override syncs, and clearing it goes back to the default. The countdown also
  runs in the notification shade, with +15s and Skip on it. A foreground
  service keeps it alive for the length of the rest, typed `specialUse`
  because `shortService` is capped at three minutes and a rest can be set to
  thirty.

  It also asks Android 16 to promote it to a Live Update, which is meant to put
  it on the lock screen and in the status bar chip. Android grants that — the
  posted notification comes back carrying `FLAG_PROMOTED_ONGOING` — but One UI
  8.5 draws none of it: no chip, no lock screen, nothing in Now Bar. That is
  Samsung's side of the line and no change here reaches it. The request costs
  nothing and stays, so it will appear on its own if One UI starts honouring it.
- **Session list** — every workout newest first, with its set count and
  whatever it came to: volume for a lifting session, distance and time for a
  run.
- **Auto sync to Google Drive** — see below.
- Material 3 with dynamic colour on Android 12+, light and dark.

## How the sync works

Sync is two-way and conflict-tolerant rather than a plain "upload a file"
backup:

- Every row has a UUID primary key, so rows created on two devices never
  collide.
- Every row carries `updatedAt`. On merge, **the newer edit of each row wins**;
  ties keep the local copy.
- Deletes are tombstones (`deleted = true`) rather than physical removals,
  otherwise a delete on one phone would be undone by the next sync from
  another.

The snapshot is versioned. Version 1 (workouts and sets) still decodes, so a
backup written by an older install restores correctly; an install still on
version 1 will refuse a version 2 snapshot rather than silently dropping the
fields it cannot read, so update every device you sync.

One sync round downloads the snapshot from Drive, merges it into the local
database, then uploads the merged result. Running it on two devices in any
order converges on the same data.

It runs on two triggers, both requiring a network connection and both scheduled
through WorkManager so Android can batch them:

| Trigger | When |
| --- | --- |
| Periodic | Every hour / 6 hours / day, your choice in Settings |
| After an edit | ~5 minutes after you log something, debounced so a whole session is one upload |

The snapshot lives in Drive's `appDataFolder` — a hidden per-app area. The app
requests only the `drive.appdata` scope, so it cannot see any of your other
Drive files, and you cannot browse the backup from the Drive UI (it is removed
if you uninstall the app or disconnect it from your Google account settings).
That privacy cuts both ways, which is what the CSV export in Settings is for:
it is the copy of your log you can actually open.

## Building

```bash
./gradlew assembleDebug     # APK at app/build/outputs/apk/debug/
./gradlew test              # unit tests
```

Requires JDK 21 and the Android SDK (compileSdk 37). minSdk is 26 (Android 8.0).

### Toolchain

These versions are a matched set. AGP 9 compiles Kotlin itself (built-in
Kotlin), so there is no `org.jetbrains.kotlin.android` plugin; the Compose and
serialization compiler plugins are still applied separately and must stay on
the same Kotlin version, and KSP's version tracks Kotlin's too.

| | Version |
| --- | --- |
| Android Gradle Plugin | 9.4.0 |
| Gradle | 9.6.0 |
| Kotlin (compiler plugins) | 2.3.21 |
| KSP | 2.3.12 |
| JDK | 21 |

Do not accept Android Studio's AGP Upgrade Assistant prompt. It bumps AGP,
Gradle, Kotlin and KSP without moving Room or the Compose BOM with them, and
the resulting skew fails in KSP with `unexpected jvm signature V`. Upgrade the
whole set together in `gradle/libs.versions.toml` instead, and let CI build it.

In Android Studio, set **Gradle JDK** to a JDK 21 and **Use Gradle from** to
`'gradle-wrapper.properties' file`, so the IDE builds with the same versions
as CI.

## Enabling Drive sync

The app builds and runs without any of this — it works offline, and Settings
shows an error when you try to connect until you do the following once:

1. In the [Google Cloud Console](https://console.cloud.google.com/), create a
   project and enable the **Google Drive API**. Note which project you used —
   the API must be enabled in the *same* project that holds the OAuth client in
   step 3.
2. Configure the OAuth consent screen. While it is in *Testing*, add your own
   Google account under **Test users**, or Google will refuse the sign-in.
3. Create an **OAuth client ID** of type **Android** with:
   - package name `com.workouttracker` (debug and release share it — there is
     no `applicationIdSuffix`)
   - the SHA-1 of the key that signs the build you are installing:
     ```bash
     ./gradlew :app:signingReport      # SHA1 per variant
     ```
     or straight from the debug keystore:
     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore \
       -alias androiddebugkey -storepass android -keypass android | grep SHA1
     ```
4. Install a locally built APK and open Settings → **Connect Google Drive**.

No file needs to be checked into the repo: an Android OAuth client is matched
server-side on package name and signing certificate, so there are no secrets to
manage and no client id to paste into the app. Changing the client in Google
Cloud therefore needs no rebuild — just retry the connection.

You can register several Android OAuth clients in one project, one per SHA-1,
so add debug now and release later rather than swapping them.

### Which signing key

This is the setting that most often goes wrong, because an app can be signed by
three different keys depending on how it reached the device:

| Build | Key | Where its SHA-1 comes from |
| --- | --- | --- |
| Local debug | `~/.android/debug.keystore` | `./gradlew :app:signingReport` |
| Local release | your own keystore | `signingReport`, once a `signingConfig` exists |
| Installed from Google Play | Play's app signing key | Play Console → Setup → App integrity |

Two traps:

- **The debug APK built by CI is useless for Drive.** Gradle generates
  `~/.android/debug.keystore` on first use, and the CI runner is ephemeral, so
  that APK is signed with a throwaway key that differs on every run. Its SHA-1
  can never match what you registered. Use it to try the app offline; build
  locally to test sync.
- **With Play App Signing, register the app signing key, not your upload key.**
  Play re-signs your upload, so registering the upload key means sync works for
  you and fails for everyone who installs from Play.

See [Release builds](#release-builds) for configuring the release key.

### When it does not work

Settings → **Status** names the cause rather than a status code, which tells you
whether waiting will help:

| Status message | Cause |
| --- | --- |
| This build isn't registered with Google | SHA-1 or package name mismatch. Permanent |
| The Drive API isn't enabled for your Google Cloud project | Not enabled, or enabled in a different project — the message quotes Google's text, which names the project |
| Google refused the sign-in | Consent screen is in Testing and this account is not a test user |
| This app wasn't granted Drive access | The Drive scope was not accepted; reconnect |
| Google Play services isn't available on this device | Play services missing, disabled or outdated |
| Couldn't reach Google / unavailable right now | Transient; the next sync retries on its own |

Only the last row is worth waiting out. Note that Google shows *"it may take 5
minutes to a few hours for settings to take effect"* when you create OAuth
credentials — that is real, but it only explains a newly created client, never
a mismatched SHA-1.

## Release builds

`./gradlew assembleRelease` works out of the box but produces
`app-release-unsigned.apk`, which no device will install. To sign it:

1. Create a keystore, once, and back it up. If you lose it you can never update
   an app published under that key:
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias workout-tracker \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Copy `keystore.properties.example` to `keystore.properties` and fill it in.
   That file, and `*.jks` / `*.keystore`, are gitignored — nothing secret is
   ever committed.
3. `./gradlew assembleRelease` now emits a signed `app-release.apk`.

CI has no keystore, so it keeps building the unsigned variant; that is
deliberate, and it still catches R8/shrinker breakage. To sign elsewhere, set
`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD` instead of using the properties file.

Once signed, `./gradlew :app:signingReport` prints the release SHA-1 — register
it as a second Android OAuth client so Drive sync works in release builds too
(see [Which signing key](#which-signing-key)).

## Layout

```
app/src/main/java/com/workouttracker/
├── WorkoutApp.kt          Application; holds the singletons
├── MainActivity.kt
├── data/                  Room entities, DAO, repository, snapshot model,
│                          migrations, muscle groups, metrics, catalogue
├── sync/                  Drive auth + REST client, merge driver, WorkManager
└── ui/                    Compose screens, navigation, theme
```

There is no DI framework: `WorkoutApp` builds the three singletons and
`appViewModel` hands them to ViewModels. At this size that is less machinery to
read than Hilt.
