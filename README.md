# Disk Mapper (Android)

Android storage analysis tool: shows where disk space actually goes, as an expandable tree (think X-plore / TreeSize).

## Core idea
The app answers one question: "Why is ~95-100 GB used when regular folders show much less?"

Two analysis modes:
- file tree (`Shared files`, optional `Private files`),
- system-level breakdown by categories and apps (`Apps`).

## Current features
- `Root scan` of `/storage/emulated/0` (via All files access).
- Optional Shizuku scan of `Android/data` and `Android/obb`, including a bounded list of the largest deletable files.
- `Apps` mode through Android's public `StorageStatsManager` API (no Shizuku required):
  - `apps-apk`, `apps-data`, `apps-cache`, `system`, `other`, `photos`, `videos`, `audio`, `free`.
- Per-app drill-down:
  - by app (total APK+data+cache),
  - APK by app,
  - data by app,
  - cache by app.
- `Clear caches` action:
  - one-tap `pm trim-caches` when private Shizuku access is ready;
  - Android's confirmation-based cache cleanup screen otherwise.
- Two sizes for every node:
  - `D` - actually allocated on disk (on-disk),
  - `L` - logical data size (without cluster overhead).
- Collapsible/expandable tree with proportional size bars per row (TreeSize-style).
- Dark compact file-manager UI.
- Filters: `All`, `Telegram`, `Videos`, `Archives`, `Installers`.

## How to use
1. Launch the app.
2. Tap `Apps` for the big picture of where space goes.
3. Treat the categories block as the primary non-overlapping summary.
4. To find specific offenders, expand:
   - by-app totals (top by overall size),
   - data by app (top by app data),
   - cache by app (top by cache).
5. Tap `Clear caches`; Shizuku is optional because Android's cleanup screen is used as fallback.
6. For user folders, tap `Shared files`.
7. For a file-level view of `Android/data` and `Android/obb`, use optional `Private files` access.

## Math caveat
- Categories are the base used/free summary.
- Per-app views are alternative representations of the same app bytes.
- Do not sum them with each other or with categories — you would get "more than 100%".

## Permissions and access
- `Usage Access` is required for proper per-app detail.
- `Shizuku` is required only for file-level access to `Android/data`/`obb` and unattended cache trimming.
- `Apps` uses Usage Access and does not require Shizuku.
- Without Shizuku, Android private folders are only partially visible on some ROMs.

## Limitations
- On some ROMs, even with Shizuku (shell mode / uid 2000), access to some private data may be restricted.
- Android does not expose a normal-app API for browsing other apps' `Android/data` trees; without Shizuku/root the app shows public aggregate statistics instead.
- The UI shows what is accessible and explicitly reports the visible share of app bytes (`Apps visible: X / Y`).

## Building from source
1. Open the project in Android Studio.
2. Run Gradle sync.
3. Build and install the debug APK.

## CI / APK
- Workflow: `.github/workflows/android-ci.yml`
- Every push to `main` publishes an `app-debug` artifact in GitHub Actions.
