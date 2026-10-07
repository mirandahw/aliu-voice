# aliu-voice

Personal build pipeline for Aliucord with working voice chat (Discord DAVE / E2EE).

Nothing here is original code. The GitHub Actions workflow checks out two unmerged
upstream pull requests at pinned commits and builds them:

| What | Source | Pinned at |
| --- | --- | --- |
| Aliucord core (`Aliucord.zip`) + Injector (`Injector.dex`, which bundles the new voice layer and webrtc) | [Aliucord/Aliucord#752](https://github.com/Aliucord/Aliucord/pull/752) | `ea06fcae` |
| Aliucord Manager APK, which swaps Discord's `libdiscord.so` for the DAVE-capable one (v333.12) | [Aliucord/Manager#143](https://github.com/Aliucord/Manager/pull/143) | `5097943c` |

The only local change is a one-line patch to Manager so it downloads `Injector.dex` from
this repo's release instead of `builds.aliucord.com`. That is what lets you install
without adb.

Outputs land on the **`voice`** pre-release: `AliucordManager-voice.apk`, `Injector.dex`,
`Aliucord.zip`.

## Install

You need Android 7.0+ (arm64 or armv7). Everything below happens on the phone.

1. **Uninstall the official Aliucord Manager** if you have it. This build uses the same
   package name but a different signing key, so it can't install over it.
   (Your existing Aliucord install and its settings/plugins are untouched.)
2. Install `AliucordManager-voice.apk` from the release.
3. Open Manager and patch Discord as usual (Install → pick a name/icon → Install).
   Watch the step list: it should include **"Download voice engine"** and
   **"Patch voice engine"**. The "Download injector" step pulls `Injector.dex` from this
   repo's release automatically.
   Let it install over your existing Aliucord. First launch after a reinstall may be slow.
4. Download `Aliucord.zip` from the release and put it at
   `Internal storage/Aliucord/Aliucord.zip` (same folder your `plugins` and `settings`
   dirs live in).
5. Enable the custom core. Either:
   - In Discord: User Settings → Advanced → turn on **Developer Mode**. Then
     Aliucord settings → **Developer Settings** → **Load custom Aliucord core** → restart; or
   - Edit `Internal storage/Aliucord/settings/Aliucord.json` and add `"AC_from_storage": true`,
     then force-stop and relaunch Aliucord.
6. Join a VC. The first time, Android will ask for mic (and camera / screen-capture
   if you use those).

To confirm it took: Aliucord settings → Updater should say core updates are disabled because
a custom core is loaded, and VoiceChatFix shows up under core plugins.

## Updating

Re-run the workflow (Actions → "Build voice-enabled Aliucord" → Run workflow). It rebuilds
from the pinned commits and replaces the `voice` release. To pull in newer upstream
commits, bump `ALIUCORD_REF` / `MANAGER_REF` in `.github/workflows/build.yml`, or pass the
commit SHAs as inputs when you run it.

Then on the phone:

- new `Aliucord.zip` → just overwrite the file in `Internal storage/Aliucord/` and restart.
- new `Injector.dex` → Manager caches the downloaded injector by version, so clear its
  downloads cache (Manager → Settings → clear cache) and re-patch.
- new Manager APK → install over the old one (the fixed `debug.keystore` in this repo
  keeps the signature stable).

## Fallback: custom injector via adb

If the auto-download of `Injector.dex` ever fails, Manager still supports importing a
custom injector manually:

```sh
adb push Injector.dex /data/local/tmp/1700000000000_2.4.0.dex
adb shell am start -n com.aliucord.manager/.MainActivity \
  -a com.aliucord.manager.IMPORT_COMPONENT \
  --es aliucord.file 1700000000000_2.4.0.dex \
  --es aliucord.componentType injector
```

Then in Manager: Settings → Developer options → on. On the patch options screen pick
**Custom Injector** → the imported 2.4.0, and patch.

## Notes

- `debug.keystore` is a throwaway Android debug key (password `android`). It is committed
  on purpose so every CI build signs the Manager APK identically.
- Both upstream PRs are still under review. When they merge and ship in official
  Aliucord, switch back: reinstall the official Manager, turn off "Load custom Aliucord
  core", and delete `Aliucord/Aliucord.zip`.
- Aliucord's [AGENTS.md](https://github.com/Aliucord/Aliucord/blob/main/AGENTS.md) bars
  AI-assisted contributions. This repo is for personal use and nothing in it is meant to
  be upstreamed.
