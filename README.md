# Still TV

A small native Android TV launcher with a spacious, Apple TV–inspired app grid. The top-left wordmark is **bhanureddy.dev**.

- Large app tiles and D-pad focus transitions.
- **Dark**, **Super Dark** (default), and **Light** themes under **Settings → Appearance**. Theme choice persists locally.
- Favorites: hold OK or press Menu on an app to pin/unpin it or open Android app information.
- Discovers installed TV apps and loads their existing banners. No recommendation feeds, video previews, ads, analytics, network permission, or account service.
- **Settings → System settings** opens Android settings. **Open original Home** opens the preserved original launcher.
- Native Java/Android Views, Android 8.0/API 26 or later. Tested initially on one Android 11 television; behavior can differ by firmware.

This changes the home screen. It does not repair freezes inside streaming apps, improve internet service, or guarantee a particular frame rate.

## Build

Requires a JDK 17 and Android SDK 35 platform/build-tools. A local helper downloads official tools with checksum verification on macOS (Apple Silicon or Intel) and x86_64 Linux:

```sh
python3 tools/bootstrap.py
sh test.sh
sh build.sh
```

Output: `build/still-tv.apk`. No Gradle or AndroidX dependencies are required. `curl`, Python 3 and `zip` must be available. SDK/JDK usage is subject to their respective licenses.

With an existing compatible toolchain, set `STILL_JAVA_HOME`, `STILL_BUILD_TOOLS`, and `STILL_ANDROID_JAR` to your JDK directory, build-tools directory, and `android.jar` file.

Local builds generate an ignored signing key in `build/`. Keep that key and its password file to sign compatible updates. CI artifacts use temporary build keys and are development builds, not a stable update channel. Do not publish your private signing key.

## Preview without changing Home

Connect your TV through authorized ADB. Replace `TV_SERIAL` with its connected ADB serial/address:

```sh
python3 tools/recover.py snapshot --device TV_SERIAL
adb -s TV_SERIAL install --no-incremental -r build/still-tv.apk
adb -s TV_SERIAL shell am start -W -n dev.still.launcher/.MainActivity
```

The snapshot saves your original launcher and device locally in `installation-state.json`, excluded from Git. Preserve that file. The preview does not change the default launcher; pressing Home still opens your existing Home.

When you deliberately choose to make Still TV the default, first verify recovery on your own TV. Some firmware restricts launcher selection. Never disable or uninstall the original launcher to force a switch.

## Recovery

```sh
# Inspect the planned recovery without changing the TV:
python3 tools/recover.py restore --dry-run

# Restore and open original Home; keep Still TV and its preferences:
python3 tools/recover.py restore

# Restore original Home first, then remove only Still TV and its user data:
python3 tools/recover.py restore --uninstall
```

Recovery verifies that original Home is installed/enabled, restores and reads back its default assignment, requires a successful launch and checks the foreground Activity before stopping or removing Still TV. If verification fails, Still TV is not removed. **The tool never disables or uninstalls the original launcher.** See [RECOVERY.md](RECOVERY.md).

## License and warranty

Licensed under the [MIT License](LICENSE). Provided **AS IS, without warranty**, with the license's disclaimer of author/copyright-holder liability. Nothing here promises universal compatibility or unconditional recovery. A disclaimer is not a guarantee of immunity from every legal claim.

Third-party apps and their names, icons and banners belong to their respective owners. App artwork is read from packages already installed on the TV and is not bundled with this project. This project is independent of Apple, Google and TV manufacturers.
