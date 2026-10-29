# Responsiveness and rendering notes

Still TV stays on native Android Views without a web runtime or UI framework dependency.

## Stable-build audit · 29 October 2026

The audit compared the signed v0.1.0 release with an updated development build on one Sony BRAVIA running Android 11, with 2 GiB RAM and 20 TV apps. Source inspection, native instrumentation, cache inspection, StrictMode, seven process starts per build, and a 100-key navigation sequence covered startup, artwork, refresh, focus, appearance, rendering and lifecycle handling. [Machine-readable measurements and evidence references](docs/evidence/performance-2026-10-29.json) preserve the sampled results.

| Check | Stable v0.1.0 | Updated build |
|---|---:|---:|
| Unchanged refresh, median of 3 rounds | 962 ms | 53 ms |
| Loaded banners replaced with placeholders per refresh | 20 | 0 |
| Exact focused All apps tile retained | No | Yes |
| All artwork callbacks, from activity creation, disk cache present | 2,781 ms | 1,111 ms |
| Artwork delivery after app-list callback | 1,903 ms | 341 ms |
| Fresh-process launch, median of 7 `TotalTime` samples | 942 ms | 616 ms |
| Warmed navigation, median / p95 frame duration | 6.63 / 8.41 ms | 6.61 / 8.59 ms |
| Navigation frames above 16.67 ms | 0 / 800 | 1 / 800 |

The strongest result is removal of repeated refresh work. The refresh timer polls settlement every 50 ms, so 53 ms is an observed bound at that resolution, not an exact input-response measurement. Artwork times record main-thread callback arrival, not the moment pixels appear. Start samples use warm disk caches and fresh processes; they are not boot measurements. Samples were small and not randomized, with variable system load. The startup median improved in this run; it does not establish a universal 35% speedup.

### What was causing the delays

1. **The disk cache rejected valid images.** Package update times include milliseconds, but this TV rounds `File.setLastModified()` down to whole seconds. Exact comparisons rejected 16 of 20 cached PNGs at the next process start. Cache filenames now include the exact package update timestamp, so validation is independent of filesystem timestamp precision. The device probe confirmed 20 valid version keys across fresh processes. Completed PNGs are renamed from temporary files; obsolete entries are removed and overflow evicts individual oldest files.
2. **The memory cache thrashed during refresh.** A 320×180 ARGB banner uses 230,400 bytes; a 4 MiB LRU holds 18. Scanning 20 apps in the same order evicted later entries before they could be reused. The catalog now reuses the App objects and images already retained by live tiles after checking component, label and package update time. The existing LRU remains bounded and useful across activity instances.
3. **Every scan rebuilt the grid.** The previous implementation published new objects with empty artwork, removed all tiles, then decoded their images again. It also moved focus away from the original All apps tile. Unchanged ordered catalogs and favorites now retain the existing views and focus; catalog membership or metadata changes still rebuild the groups.
4. **PNG compression delayed artwork callbacks.** New images now reach the UI before disk-cache persistence. The worker uses Android background priority, and `prepareToDraw()` requests early bitmap preparation. Empty-cache artwork delivery after app discovery fell from 2,483 to 936 ms in the paired samples. Total empty-cache first-favorite arrival was slower, 900 to 1,784 ms, while app discovery varied from 619 to 1,552 ms; these samples do not prove every startup phase improves.
5. **An accent change recreated the whole screen.** Preview and Cancel now update the two header text colors. The real-device check retained the same tiles and ScrollView, kept scroll position at 473 px, and restored the saved accent on cancellation. The baseline rebuilt both views and reset scroll to zero.

### Checks and limits

`sh test.sh` passed all 26 state checks and 12 recovery tests. The signed APK built successfully. Native device checks covered three unchanged refreshes, artwork/version-key retention, exact tile focus, accent preview/cancel, fresh-process launches and 800 navigation frames. The original signed stable APK was reinstalled successfully without uninstalling Still TV, then the updated build was reinstalled. Final checks retained the six favorites, brand, actual Still TV Home resolution and installed original Sony launcher. The temporary instrumentation helper was removed afterward.

StrictMode observed three main-thread startup disk reads and no writes in both builds, associated with preference/recovery initialization. This does not enumerate native I/O. Recovery durability, fonts, the Home alias, permissions and the 110 ms focus animation were preserved. Actual catalog changes still use the existing package-based focus restoration; a favorited app can return to its Favorites copy after that rebuild. No-op scans avoid that path.

The final foreground memory snapshot contained 51 Views, one Activity and 32.8 MiB proportional set size. It is a single snapshot, not a heap-leak test or a controlled memory-reduction comparison.

Warmed navigation was already quick and its tail timings did not improve. The updated run included one 31.78 ms frame; there is no zero-jank claim. FrameMetrics measures the app window, not physical remote-to-photon latency or streaming-app performance. No release tag or published v0.1.0 APK was replaced.

To repeat the device checks, use the [build-only native probe instructions](tests/device/README.md). The probe is separate from the launcher APK, uses the owner's existing signing key, and adds no production dependency or permission.

## Implementation

- One accessible `AppTile` View per app, rather than a nested container/image/label hierarchy.
- Reuse typefaces, paints, text measurements, shaders and focus geometry. Focus does not query settings providers, change typefaces, or construct new styling drawables.
- Focus state changes immediately; the optional 110 ms transform is interruptible and respects Android's disabled-animation setting.
- Package discovery, resource decoding, PNG cache reads/writes and sorting run on one background worker.
- Publish app entries before artwork; update existing tile artwork rather than rebuilding the whole screen for each image.
- Reusable artwork cache: 4 MiB in memory, with a bounded 8 MiB disk cache. Live tiles additionally retain their displayed bitmaps (about 225 KiB per unique 320×180 ARGB app banner); the cache limit is not a cap on total process memory.
- Exact package update timestamps in filenames invalidate cached images. Completed images are published through temporary-file rename; stale cache entries and individual oldest files are pruned within the 8 MiB budget.
- Unchanged catalog snapshots retain the current App objects, artwork, tile views and focus. Package changes during a scan queue another refresh. Activity destruction detaches callbacks and closes the worker.
- Accent previews update the wordmark and clock colors without rebuilding the screen.
- Native clock updates do not rebuild the home screen.

Source responsibilities: `MainActivity` orchestrates lifecycle and menus, `AppCatalog` loads app data, `AppTile` draws and handles focus, `UiStyle` owns typography/palette, and `Recovery` isolates original-Home preservation and crash forwarding.

## Initial real-device samples

On one Android 11 TV with 2 GiB RAM, after resetting `gfxinfo` counters and using the same eight right/left navigation pairs:

| Metric | Original preview | Refined preview |
|---|---:|---:|
| Attached Views | 128 | 51 |
| Render-node memory | 170.41 KiB | 59.95 KiB |
| Median frame time | 7 ms | 6 ms |
| 95th percentile frame time | 11 ms | 10 ms |
| 99th percentile frame time | 14 ms | 19 ms |
| Janky / rendered frames | 1 / 144 | 2 / 113 |

These short samples have different frame counts because animation duration changed. They do not establish a universal speedup, reliable tail-latency comparison, cold-start improvement, or remote-to-photon latency. The refined sample's 99th percentile and jank count were worse despite lower median/layout work. Check longer interactions and actual viewing behavior before drawing broader conclusions.

For 60 Hz, Android's frame budget is approximately 16.67 ms. Startup and cold artwork loading should be assessed separately from warmed navigation. Keeping the original launcher available remains part of testing and recovery.

## Primary references

- [Android TV typography](https://developer.android.com/design/ui/tv/guides/styles/typography)
- [View hierarchy performance](https://developer.android.com/topic/performance/rendering/optimizing-view-hierarchies)
- [Inspect rendering speed and overdraw](https://developer.android.com/topic/performance/rendering/inspect-gpu-rendering)
- [TV D-pad navigation](https://developer.android.com/training/tv/get-started/navigation)
- [Slow rendering](https://developer.android.com/topic/performance/vitals/render)
- [Bitmap preparation and GPU upload](https://developer.android.com/topic/performance/rendering/profile-gpu)
- [Android thread scheduling and UI responsiveness](https://developer.android.com/topic/performance/threads)
