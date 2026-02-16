# CLAUDE.md - DiskMapperAndroid project context

## Product idea
DiskMapperAndroid is an Android storage analysis and cleanup app focused on one practical goal:
- show where storage is actually consumed;
- let user safely remove heavy garbage files;
- work with problematic Android paths (`/Android/data`, `/Android/obb`) via Shizuku when possible.

This is not a generic file manager. It is a disk usage mapper + cleanup tool.

## What we are trying to achieve
Target result for the user:
1. Scan storage quickly.
2. See expandable tree structure like desktop `TreeSize` / mobile `X-plore`.
3. For each node, show two sizes:
   - `D` (on-disk/allocated estimate, cluster-aware, "real occupied")
   - `L` (logical/ideal size, equivalent to "if cluster were smaller than smallest file", i.e. near-zero slack model)
4. Detect heavy folders for common СНГ usage patterns, especially Telegram-related storage.
5. Delete unneeded files directly from scan results.

## Shizuku is mandatory for Android private folders
Important: without Shizuku, app does not get full readable access to `/Android/data` and `/Android/obb`.
- SAF and normal root-like scan can show incomplete/near-zero values there.
- Accurate scan of Android private folders must be done through Shizuku flow.
- UI/diagnostics should explicitly communicate when access is partial.

## UX direction (mandatory)
- Tree-first UI, not card-heavy Material style.
- Compact single-line rows:
  - left: tree branch + name
  - right: `D` and `L` values
- Collapsed by default.
- Expand/collapse must work reliably.
- Visual style should be close to X-plore file tree behavior.

## Critical functional requirements
1. Sources:
   - SAF selected folder scan.
   - Root-like scan (`/storage/emulated/0`) with All Files access.
   - Shizuku scan for `Android/data` and `Android/obb` (primary/required path for real values there).
   - show every storage area/section that is actually readable on the device; if section is not readable, show diagnostics so user still understands where gap is.
2. Filters:
   - All, Telegram, Videos, Archives, Installers.
3. Deletion:
   - file deletion from result list/tree.
4. Observability:
   - user input and key UI actions must be visible in logcat.
   - scan lifecycle logs: start/success/failure, items count, sizes, source.

## Known platform constraint
If Shizuku runs as `shell` (uid 2000), some ROMs still restrict full `Android/data` access.
Root/Sui backend may be required for true full access.
Without Shizuku at all, assume `Android/data` and `Android/obb` visibility is not complete.

## Logging protocol (must keep)
Use log tag: `DiskMapperTrace`.

Current expected categories:
- `INPUT` (touch events)
- `UI` (button taps, filter changes, expand/collapse, delete confirm/cancel)
- `VM` (scan start/success/failure, source transitions, delete lifecycle)
- `ERR` (exceptions)

## Manual debug workflow
1. Clear and start focused log stream:
```powershell
& "C:\Users\kvita\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe" logcat -c
& "C:\Users\kvita\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe" logcat DiskMapperTrace:I AndroidRuntime:E *:S
```
2. Reproduce issue in app for 30-60 seconds.
3. Validate:
   - taps are visible (`INPUT`/`UI`);
   - scan path/source is visible (`VM`);
   - crash stack present (`AndroidRuntime`).

## Definition of done for each iteration
Change is accepted only if all conditions pass:
1. No crash during root scan + shizuku scan + expand/collapse actions.
2. Tree is collapsed by default and can be expanded/collapsed repeatedly.
3. Both sizes (`D`, `L`) are visible on each row.
4. `Android` subtree size is realistic (not fake near-zero because of missing access).
5. Logcat clearly shows performed actions and scan lifecycle.
6. UI makes it clear which parts are fully scanned vs restricted/unreadable.

## Priority order for future work
1. Stability and correctness of scan results.
2. Full/clear access diagnostics for Android private folders.
3. X-plore-like tree usability polish.
4. Fast cleanup flow for Telegram-heavy storage.
