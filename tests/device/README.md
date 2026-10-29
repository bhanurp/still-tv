# Native device probe

Build only, using the same existing signing key as the target launcher:

```sh
sh tools/device-test.sh
```

Supports `STILL_JAVA_HOME`, `STILL_BUILD_TOOLS`, and `STILL_ANDROID_JAR`, like
`build.sh`. The helper refuses to generate a key. Output is
`build/device-tests/still-tv-tests.apk`; it does not install or contact a device.

Owner-run commands (replace `TV_SERIAL`):

```sh
adb -s TV_SERIAL install --no-incremental -r build/device-tests/still-tv-tests.apk
adb -s TV_SERIAL shell am instrument -w -e mode audit dev.still.launcher.tests/.LauncherProbe
adb -s TV_SERIAL shell am instrument -w -e mode cold-artwork dev.still.launcher.tests/.LauncherProbe
adb -s TV_SERIAL shell am instrument -w -e mode navigation dev.still.launcher.tests/.LauncherProbe
adb -s TV_SERIAL shell am instrument -w -e mode cache dev.still.launcher.tests/.LauncherProbe
adb -s TV_SERIAL shell am instrument -w dev.still.launcher.tests/.HeaderProbe
adb -s TV_SERIAL uninstall dev.still.launcher.tests
```

The target app must already be installed with the same signing certificate.
`HeaderProbe` checks that all 19 approved phrases fit without ellipsis at the
TV's actual font scale and width, the left header is 32 sp, the clock remains
36 sp, and rebuilding appearance retains the current phrase. Inspect
`STILL_TV_HEADER_JSON=` for errors. Its fresh Activity launch advances the saved
phrase through the launcher's normal resume behavior.

The instrumentation opens a fresh MainActivity task. The launcher naturally
advances its saved header phrase on resume; favorites and appearance settings
are preserved. The probe does not clear app data, touch recovery flags or change
Home/package state.
Normal launcher cache reads/writes still occur. If safe mode prevents catalog
creation, the report records an error; the helper does not dismiss recovery.
Only the explicitly selected `cold-artwork` mode deletes regular files directly
inside the target's `cacheDir/artwork` before launch. It reports deletion counts
and failures, leaves the directory and every other file/preferences untouched,
and then runs the same audit. Derived artwork regenerates normally.
`cache` is read-only and does not launch MainActivity: it compares each PNG's
millisecond file timestamp against its installed package's lastUpdateTime,
reports matching/mismatching counts and total bytes, and includes the first
five sorted samples. It also lists stale uninstalled cache packages and
installed TV apps without a PNG. `difference_ms` is file timestamp minus APK
update timestamp. No cache file is decoded, changed or deleted in that mode.
Version-keyed `pkg_<exactlastUpdateTime>.png` files are parsed at the final
underscore with a trailing nonnegative long, so underscores inside package
names are preserved. `valid_version_keys` compares that encoded value to the
installed package update time independently of mtime precision; `legacy_pngs`
and `stale_version_keys` report the alternatives. Samples include
`encoded_updated`; `installed_tv_apps_missing_valid_key` lists apps without a
current version-keyed PNG. Original mtime statistics remain in the report.

Read `STILL_TV_PROBE_JSON=` in the instrumentation stream (also returned as the
`report` Bundle field). Always inspect `error`, observer status, settled flags,
and individual booleans: instrumentation exits successfully even for a baseline
regression so all three refresh results remain available.

Audit observes onCreate duration, first onApps, successful first artwork,
first favorite artwork, all initially missing artwork loaded, and initial
catalog-settled timestamps, then performs three explicit catalog refreshes with
elapsed times. Artwork timestamps are null when no corresponding successful
callback/loading completion was observed. Settlement uses 50 ms polling.
Before forwarding onApps it counts null banners, previously loaded banners
that became null, and retained App object identities. Each round prefers a
favorite's last tile (the All apps copy), retains the View, and checks its
identity and focus after the catalog settles. These are unchanged-package
refresh checks: do not install/update/remove other apps concurrently. Missing
artwork checks are meaningful only when existing artwork was already loaded.

On API 29+, audit opens the accent preview and uses public WindowInspector to
find the window titled Preview accent. On the main thread it matches an exact
ListView adapter item and invokes performItemClick on that visible row. It
checks tile and ScrollView identity/scroll position and presses Back to cancel.
It never sends CENTER or clicks Keep color. Earlier APIs skip this subtest.
Identity checks run only after the accent actually changes. Inspect
`saved_accent_restored`, `preview_accent_changed`, and error fields. A failed
selection includes a bounded native-window package/class/text summary.

On API 28+, audit observes StrictMode main-thread disk reads/writes from
onCreate through audit completion, reports counts and the top three launcher
class/method sites, and restores the prior thread policy before finishing.
These observations do not enumerate all syscalls; framework/probe-only stacks
are identified separately. StrictMode reporting does not fail or kill the app.

Navigation captures native Window FrameMetrics while sending 100 synthetic
D-pad keys spaced by at least 180 ms. It reports nearest-rank p50/p90/p95/p99/max,
sample counts and durations over 16.67 ms for TOTAL, DRAW, LAYOUT_MEASURE and
SYNC, plus dropped frame callbacks. These are frame durations, **not physical
remote input latency**, and they do not include every frame shown by the TV.
