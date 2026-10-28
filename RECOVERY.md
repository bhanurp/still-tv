# Installation and recovery plan

## Always preserve the original launcher

Still TV and its tooling must never disable, uninstall, clear data from, or replace the original launcher package. The original remains installed and enabled during previews and any later default switch. A default Home assignment is separate from removing an app.

## Before installation

1. Keep authorized ADB working from a computer on the same trusted network.
2. Run `python3 tools/recover.py snapshot --device TV_SERIAL` before changing Home. This records the original component and Android user in an ignored local file.
3. Preserve `installation-state.json` and verify that `restore --dry-run` describes the intended sequence.
4. Install/open a preview. Leave the original as the default during development and crash/recovery checks.

## Normal exit

**Settings → Restore original launcher** disables only Still TV’s own Home alias and opens the saved original Home. The app verifies that the saved original now resolves Home before reporting success. If verification or launch fails, it restores Still TV’s previous Home-entry state. Still TV stays installed and can be opened from Apps. **Use Still TV as Home** enables its Home entry again and opens Android’s Home selection where the firmware supports it.

## Crash recovery

For an uncaught Java exception, a process-level handler saves a recovery flag, disables only Still TV’s own Home alias, and tries to open original Home before handing the exception to Android. On a later Home launch while this flag is set, Still TV forwards to original Home. Opening Still TV explicitly instead offers a recovery dialog with **Open original Home**, **Try Still TV again**, and **System settings**.

This is best effort. A frozen main thread, native crash, low-memory kill, power loss, or Android restrictions may prevent in-process recovery. There is no claim that an app can always repair its own failure or silently reassign the Home role. A computer with working ADB supplies the independent recovery route below. Without ADB, use the TV's Home-app selection/settings where supported.

## Independent ADB recovery

Run from this source directory with the original snapshot file available:

```sh
python3 tools/recover.py restore
```

The tool performs these steps in order:

1. Check that the saved original package is installed and enabled for the saved Android user.
2. Assign the original Home using Android's `cmd package set-home-activity` command.
3. Resolve Home again and verify the original component.
4. Launch original Home with `am start -W`; reject errors and launch timeouts.
5. Check that original Home is the resumed foreground Activity.
6. Stop only `dev.still.launcher`.
7. With `--uninstall`, uninstall only `dev.still.launcher` for the saved user. This removes its favorites/theme preferences for that user.

The tool stops on failed verification rather than proceeding to removal. The original package is never a removal target. Keep the snapshot local: it contains a device identifier.

## Before making Still TV the default

Verify cold/warm app launches, all three themes, remote navigation, launch/return from streaming apps, an intentional test crash, and independent ADB restoration on the target firmware. A controlled crash test should not run during viewing or unsaved configuration work. If these checks fail, keep using the original default and the launchable preview.

A future decision to remove the system launcher is outside this project's installation/recovery workflow and requires the owner's separate explicit instruction. It is not needed to test Still TV.
