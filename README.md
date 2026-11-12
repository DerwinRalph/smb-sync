# SMB Sync

Android app that keeps folders on a phone in sync with shared folders on a PC over SMB
(Windows file sharing), in the spirit of FolderSync.

## Features

- Any number of folder pairs, each with its own settings
- Direction per pair: two-way, phone → PC only, or PC → phone only
- Optional syncing of deletions, with a safety stop if one side suddenly looks empty
- Conflict handling: keep both, newer wins, phone wins, PC wins, or skip
- Schedule: manual, repeat every N minutes/hours/days, or at a set time on chosen weekdays
- Wi-Fi-only and charging-only options
- Exclude folders or name patterns per pair
- Find PCs on the network, browse shares and folders, create folders
- Live transfer stats and a per-pair log

## Build

Open in Android Studio, or from the project root:

```
gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Tests

```
gradlew testDebugUnitTest
```

This runs the sync-rule tests. Tests against a real SMB server are skipped unless
`SMB_TEST_HOST` is set; see `tools/smb-test-server/README.md`.

## Code

Everything is in `app/src/main/java/com/example/smbsync/`:

| File | What it holds |
|---|---|
| `SyncEngine.kt` | The sync rules (no Android dependencies) |
| `Smb.kt` | SMB client wrapper around smbj |
| `SyncWorker.kt` | Background runs and scheduling (WorkManager) |
| `Discovery.kt` | Network scan for PCs with file sharing on |
| `Model.kt` | Folder-pair settings, storage, log |
| `MainActivity.kt` | All screens (Jetpack Compose) |
