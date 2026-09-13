# Workout Tracker

A small Android app for logging workout sessions, written in Kotlin with Jetpack
Compose. Everything is stored locally in Room; the one "advanced" piece is
automatic background sync to a private folder in your Google Drive.

## Features

- **Log sessions** — name, date, free-text notes.
- **Exercises and sets** — reps and weight per set, grouped by exercise. Adding
  a set reuses the last reps/weight for that exercise, so logging 5×5 is four
  taps after the first set.
- **Exercise suggestions** — recently used exercise names are one tap away.
- **Session list** — every workout with its set count and total volume
  (reps × weight), newest first.
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

## Building

```bash
./gradlew assembleDebug     # APK at app/build/outputs/apk/debug/
./gradlew test              # unit tests
```

Requires JDK 17 and the Android SDK (compileSdk 35). minSdk is 26 (Android 8.0).

## Enabling Drive sync

The app builds and runs without any of this — it just works offline, and
Settings shows "Connect Google Drive" as unavailable until you do the following
once:

1. In the [Google Cloud Console](https://console.cloud.google.com/), create a
   project and enable the **Google Drive API**.
2. Configure the OAuth consent screen. While it is in *Testing*, add your own
   Google account under **Test users**.
3. Create an **OAuth client ID** of type **Android** with:
   - package name `com.workouttracker`
   - the SHA-1 of the signing key you build with. For debug builds:
     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore \
       -alias androiddebugkey -storepass android -keypass android | grep SHA1
     ```
4. Rebuild and install. Settings → **Connect Google Drive** now shows Google's
   consent screen.

No file needs to be checked into the repo — the Android OAuth client is matched
by package name and signing certificate, so there are no secrets to manage. If
you change the `applicationId` or sign with a release key, add a second OAuth
client for that combination.

## Layout

```
app/src/main/java/com/workouttracker/
├── WorkoutApp.kt          Application; holds the singletons
├── MainActivity.kt
├── data/                  Room entities, DAO, repository, snapshot model
├── sync/                  Drive auth + REST client, merge driver, WorkManager
└── ui/                    Compose screens, navigation, theme
```

There is no DI framework: `WorkoutApp` builds the three singletons and
`appViewModel` hands them to ViewModels. At this size that is less machinery to
read than Hilt.
