# Disk Mapper (Android)

Android storage analysis tool: shows where disk space actually goes, as an expandable tree (think X-plore / TreeSize).

## Core idea
The app answers one question: "Why is ~95-100 GB used when regular folders show much less?"

Two analysis modes:
- file tree (`Root scan`, `Shizuku`),
- system-level breakdown by categories and apps (`Apps`).

## Current features
- `Root scan` of `/storage/emulated/0` (via All files access).
- `Shizuku` scan of `Android/data` and `Android/obb`.
- `Apps` mode with full categorization from `dumpsys diskstats`:
  - `apps-apk`, `apps-data`, `apps-cache`, `system`, `other`, `photos`, `videos`, `audio`, `free`.
- Per-app drill-down:
  - by app (total APK+data+cache),
  - APK by app,
  - data by app,
  - cache by app.
- `Trim caches` action: clears caches of ALL apps in one tap via Shizuku
  (`pm trim-caches`) — safe, apps rebuild caches on demand; reports freed bytes.
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
5. Tap `Trim caches` to clear all app caches at once (needs Shizuku).
6. For user folders, tap `Root scan`.
7. For `Android/data` and `Android/obb`, use `Shizuku`.

## Math caveat
- Categories are the base used/free summary.
- Per-app views are alternative representations of the same app bytes.
- Do not sum them with each other or with categories — you would get "more than 100%".

## Permissions and access
- `Usage Access` is required for proper per-app detail.
- `Shizuku` is required for full access to `Android/data`/`obb` and for `Trim caches`.
- Without Shizuku, Android private folders are only partially visible on some ROMs.

## Limitations
- On some ROMs, even with Shizuku (shell mode / uid 2000), access to some private data may be restricted.
- The UI shows what is accessible and explicitly reports the visible share of app bytes (`Apps visible: X / Y`).

## Building from source
1. Open the project in Android Studio.
2. Run Gradle sync.
3. Build and install the debug APK.

## CI / APK
- Workflow: `.github/workflows/android-ci.yml`
- Every push to `main` publishes an `app-debug` artifact in GitHub Actions.
