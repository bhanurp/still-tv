# Responsiveness and rendering notes

Still TV stays on native Android Views without a web runtime or UI framework dependency.

## Implementation

- One accessible `AppTile` View per app, rather than a nested container/image/label hierarchy.
- Reuse typefaces, paints, text measurements, shaders and focus geometry. Focus does not query settings providers, change typefaces, or construct new styling drawables.
- Focus state changes immediately; the optional 110 ms transform is interruptible and respects Android's disabled-animation setting.
- Package discovery, resource decoding, PNG cache reads/writes and sorting run on one background worker.
- Publish app entries before artwork; update existing tile artwork rather than rebuilding the whole screen for each image.
- Reusable artwork cache: 4 MiB in memory, with a bounded 8 MiB disk cache. Live tiles additionally retain their displayed bitmaps (about 225 KiB per unique 320×180 ARGB app banner); the cache limit is not a cap on total process memory.
- Package update timestamps invalidate cached images. Package changes during a scan queue another refresh. Activity destruction detaches callbacks and closes the worker.
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
