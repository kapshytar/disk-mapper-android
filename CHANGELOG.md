# Changelog

## Unreleased
- Added one-tap Shizuku flow:
  - auto-open Shizuku app when needed,
  - auto-retry Android private scan on return to app.
- Added full app storage coverage from `dumpsys diskstats` arrays:
  - package names,
  - app sizes,
  - app data sizes,
  - cache sizes.
- Added `Apps` storage mode with category breakdown:
  - `apps-apk`, `apps-data`, `apps-cache`, `system`, `other`, `photos`, `videos`, `audio`, `free`.
- Added per-app drill-down trees:
  - `per-app`,
  - `apps-apk-by-app`,
  - `apps-data-by-app`,
  - `apps-cache-by-app`.
- Added visibility diagnostics line:
  - `Apps visible: X / Y`.
- Fixed tree size aggregation to avoid double counting in mixed node cases.
- Removed top `Folder` selector action as unused for current workflow.
- Updated `README.md` with current behavior and usage instructions.

## 0.1.0
- Initial public MVP with:
  - root scan,
  - Shizuku Android private scan,
  - tree UI,
  - dual size display (`D`/`L`),
  - Telegram/video/archive/installers filters.
