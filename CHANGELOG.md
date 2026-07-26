# Changelog

## Unreleased
- Fixed totals not matching the device: capacity and free space now come from
  `statfs` on the data partition instead of `StorageStatsManager`, whose
  `getTotalBytes()` returns the advertised size (a Galaxy S10+ answers 128 GiB
  for a 109 GiB filesystem) and whose `getFreeBytes()` counts reclaimable cache
  as free. Measured on device: 107.5 GiB used / 20.5 GiB free became
  83.8 / 25.2, matching `df`. `System` is again a real remainder instead of
  collapsing to zero, and the categories always sum to used space.
- Fixed the tree collapsing whenever a filter chip was tapped; expansion state now
  survives filter changes and rescans, and resets only for a new scan target.
- Fixed `Shizuku` call timeouts covering only service binding, not the call itself:
  a wedged scan or trim no longer holds the call mutex forever.
- Fixed a failed or switched scan leaving the previous source's tree and totals on
  screen, and stale Shizuku/Usage diagnostics under the summary line.
- Removed the `Downloads` category, which was declared and rendered but never
  computed, so the row could never appear.
- Fixed a superseded scan overwriting a newer one's results, and the progress
  spinner sticking on forever when scans overlapped: scans now carry a generation
  and stale completions are ignored. `Clear caches` shares the same protocol.
- `deleteItem` snapshots the scan source and Telegram-only flag once instead of
  re-reading them mid-operation.
- Tree flattening is memoized and no longer re-sorts already-sorted children on every
  recomposition.
- Removed the Shizuku dependency from `Apps`: totals and per-app sizes now use public `StorageStatsManager` APIs with Usage Access.
- Fixed double-counting of cache bytes (`StorageStats.dataBytes` already includes cache).
- Fixed private scan totals by emitting and counting only explicit `Android/data` and `Android/obb` root records.
- Fixed Root + private merge to atomically replace the old private subtree instead of mixing stale rows.
- Added a bounded largest-files list to private scans so heavy files can be deleted from results.
- Restricted privileged deletion to canonical paths inside `Android/data` and `Android/obb`.
- Added system cache-cleanup fallback when Shizuku is unavailable.
- Added access coverage status, permission auto-retry, contextual filters, background tree construction, visible truncation notice, and larger tree rows.
- Added unit coverage for non-overlapping app categories and private-root accounting.
- CI now runs unit tests and Android lint before publishing the debug APK artifact.
- Added `Trim caches` action: clears ALL app caches via Shizuku (`pm trim-caches`),
  with confirmation dialog and freed-bytes report in snackbar.
- ShizukuBridge: service calls now run on the caller's thread (not the binder
  main-thread callback), with per-call timeout (120s for trim).
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
