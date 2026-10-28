# Still TV

A small native Android TV launcher with a spacious, Apple TV–inspired app grid. The top-left wordmark is **bhanureddy.dev**.

- Large app tiles and D-pad focus transitions.
- **Dark**, **Super Dark** (default), and **Light** themes under **Settings → Appearance → Theme**. Theme choice persists locally.
- Favorites: hold OK or press Menu on an app to pin/unpin it or open Android app information.
- Discovers installed TV apps and loads their existing banners. No recommendation feeds, video previews, ads, analytics, network permission, or account service.
- **Settings → System settings** opens Android settings. **Restore original launcher** switches back to the preserved original launcher; if it is disabled, the setting first opens Android App Info so you can select **Enable**, then return.
- Native Java/Android Views, Android 8.0/API 26 or later. Tested initially on one Android 11 television; behavior can differ by firmware.
- Bundled DM Sans typography, a fixed navigation bar, single-view app tiles, progressive background artwork loading, and bounded artwork caches.
- Matching 36sp wordmark/clock typography. Neon amber is the default accent. Preview Electric violet, Electric cyan, or Neon amber under **Settings → Appearance → Accent**; **Keep color** saves the choice and **Cancel** restores it. Accents adapt for contrast in Light.

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

To make Still TV the default, use **Settings → Use Still TV as Home** and accept the Android Home selection where available. On firmware that requires authorized ADB, enable its Home entry and assign it:

```sh
adb -s TV_SERIAL shell cmd package set-home-activity --user 0 dev.still.launcher
```

If the Home entry was previously disabled by restoration, first select **Use Still TV as Home** in the app. Some TVs give their system launcher higher intent priority: even a successful ADB Home-role assignment may leave the original handling Home. Verify the actual Home button; the default chooser cannot override that priority. An owner-authorized disable workflow is described below.

**Settings → Restore original launcher** removes only Still TV’s Home entry from selection and opens the saved original launcher. Still TV remains available in Apps. Verify recovery on your own firmware before switching. Never uninstall the original launcher.

## Firmware that prioritizes the original Home

Some TVs give their built-in launcher a higher HOME priority. Android caps positive priorities for ordinary apps at zero, so changing Still TV's manifest does not override it. On the tested Sony Android 11 TV, disabling the original for the current user lets Still TV handle Home while the original remains installed.

Only do this after confirming authorized ADB recovery works and saving the original Home snapshot. Enable Still TV's Home entry using **Use Still TV as Home** before disabling the original. Choose the actual package from your own snapshot; package names vary by TV:

```sh
adb -s TV_SERIAL shell pm disable-user --user 0 ORIGINAL_HOME_PACKAGE
adb -s TV_SERIAL shell cmd package set-home-activity --user 0 dev.still.launcher
adb -s TV_SERIAL shell cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME
```

Verify Still TV handles the remote Home button and original remains installed. **Restore original launcher** opens the original's Android App Info when it is disabled: select **Enable**, confirm **OK**, then press Back to complete restoration. Android does not allow Still TV to silently re-enable another package. If the TV does not expose Enable or Still TV is unresponsive, use the independent ADB recovery below, which re-enables the saved original before restoring Home. Keep the original package installed throughout.

## Recovery

```sh
# Inspect the planned recovery without changing the TV:
python3 tools/recover.py restore --dry-run

# Restore and open original Home; keep Still TV and its preferences:
python3 tools/recover.py restore

# Restore original Home first, then remove only Still TV and its user data:
python3 tools/recover.py restore --uninstall
```

Recovery verifies that original Home is installed, re-enables it if needed and verifies that state, restores and reads back its default assignment, requires a successful launch and checks the foreground Activity before stopping or removing Still TV. If verification fails, Still TV is not removed. **The tool never disables or uninstalls the original launcher.** See [RECOVERY.md](RECOVERY.md).

## License and warranty

Licensed under the [MIT License](LICENSE). Provided **AS IS, without warranty**, with the license's disclaimer of author/copyright-holder liability. Nothing here promises universal compatibility or unconditional recovery. A disclaimer is not a guarantee of immunity from every legal claim.

Third-party apps and their names, icons and banners belong to their respective owners. App artwork is read from packages already installed on the TV and is not bundled with this project. This project is independent of Apple, Google and TV manufacturers.

DM Sans is bundled under the [SIL Open Font License](app/assets/fonts/OFL.txt), separate from the MIT license for the launcher code. Font source: [Google Fonts / DM Sans](https://github.com/google/fonts/tree/main/ofl/dmsans).
