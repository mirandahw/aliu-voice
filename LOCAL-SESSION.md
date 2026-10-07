# Handoff for the local (adb) session

Context: this repo's CI builds a voice-enabled Aliucord from two unmerged upstream PRs and
publishes three files to the `voice` pre-release:
https://github.com/mirandahw/aliu-voice/releases/tag/voice

- `AliucordManager-voice.apk`: Aliucord Manager from Aliucord/Manager#143, package id
  `com.aliucord.manager.voice`, installs alongside the official Manager.
- `Injector.dex`: Injector from Aliucord/Aliucord#752 with the new voice layer + webrtc.
- `Aliucord.zip`: core from Aliucord/Aliucord#752 (includes the `VoiceChatFix` core plugin).

The repo is public, so Manager's "Download injector" step fetches `Injector.dex` from the
release on its own. Step 3 (adb import as a custom injector) is only a fallback if that
download fails; if you skip it, also skip the "Custom Injector" pick in step 4.

Goal: get Miranda's existing Aliucord install (keep plugins/settings) joining voice channels.

## Steps

0. Preflight
   ```sh
   adb devices                      # expect one device, "device" not "unauthorized"
   adb shell getprop ro.product.cpu.abi   # arm64-v8a or armeabi-v7a; both are supported
   adb shell pm list packages | grep -i -E "aliucord|discord"
   ```
   Note the installed Aliucord package name (default `com.aliucord`) and whether the
   official Manager (`com.aliucord.manager`) is present. Leave both alone.

1. Download the release assets
   ```sh
   mkdir -p dist && cd dist
   for f in AliucordManager-voice.apk Aliucord.zip Injector.dex; do
     curl -sSLO "https://github.com/mirandahw/aliu-voice/releases/download/voice/$f"
   done; cd ..
   ```

2. Install Manager
   ```sh
   adb install -r dist/AliucordManager-voice.apk
   ```

3. (Fallback only) Import the injector as a custom component (file name must be
   `<epochMillis>_<x.y.z>.dex`, version 2.4.0 matches what the PR's Injector reports)
   ```sh
   name="$(date +%s)000_2.4.0.dex"
   adb push dist/Injector.dex /data/local/tmp/$name
   adb shell am start -n com.aliucord.manager.voice/com.aliucord.manager.MainActivity \
     -a com.aliucord.manager.IMPORT_COMPONENT \
     --es aliucord.file $name --es aliucord.componentType injector
   ```
   Manager opens and should toast/confirm the import.

4. Patch Discord (on the phone, in "Aliucord Manager (voice)")
   - Install → keep the same app name / package name as the existing install
   - Only if you did step 3: Settings → Advanced → **Developer options** on, then on the
     patch options screen → **Custom Injector** → pick the imported 2.4.0
   - Install. Expect steps "Download voice engine" and "Patch voice engine" in the list.
     It downloads Discord 126.21 (~100 MB) and the 333.12 voice split, then installs over
     the existing Aliucord as an update (it re-signs with the key embedded in the installed
     APK, so no uninstall).
   - If it refuses with a signature error, the existing Aliucord was installed with a key
     Manager can't recover; stop and ask before uninstalling anything.

5. Custom core
   ```sh
   adb push dist/Aliucord.zip /sdcard/Aliucord/Aliucord.zip
   adb pull /sdcard/Aliucord/settings/Aliucord.json /tmp/Aliucord.json
   # add "AC_from_storage": true to the top-level JSON object (python/jq), then
   adb push /tmp/Aliucord.json /sdcard/Aliucord/settings/Aliucord.json
   adb shell am force-stop com.aliucord      # use the real package name
   adb shell monkey -p com.aliucord 1
   ```
   `settings/Aliucord.json` may not exist if Aliucord settings were never touched; then
   create it as `{"AC_from_storage": true}`.
   Alternative on-device: Discord dev mode on → Aliucord settings → Developer Settings →
   "Load custom Aliucord core".

6. Verify
   ```sh
   adb logcat -c
   adb logcat -s Aliucord:* VoiceChatFix:* Injector:* AndroidRuntime:E
   ```
   Then on the phone join a voice channel. Good signs: Injector logs "Using custom Aliucord
   core!", a `VoiceChatFix` line with "Aliuvoice 90.0.29-krisp_vad_overuse (libdiscord
   333.12)", and no `WS CLOSED ... 4016, Unknown encryption mode` loop.
   Aliucord settings → Updater should say core updates are disabled (custom core).

## If it breaks

- Crash on launch: Injector auto-disables the custom core on a failed start
  (`AC_from_storage` flips to false) and falls back to downloading the official core. Grab
  `adb logcat -d | grep -E "Aliucord|Injector|AndroidRuntime"` right after.
- VC loops/4016: the libdiscord swap didn't happen. Check
  `adb shell pm path com.aliucord` → `unzip -l` that APK for `lib/<abi>/libdiscord.so` size
  (new one is ~11.7 MB arm64 / ~6.9 MB armv7; the old one is much smaller).
- Mic/camera permission prompts are expected on first use. Screenshare also needs the
  foreground-service permissions Manager#143 adds to the manifest.

## Upstream references

- Aliucord/Aliucord#752 (pinned `ea06fcae`): https://github.com/Aliucord/Aliucord/pull/752
- Aliucord/Manager#143 (pinned `5097943c`): https://github.com/Aliucord/Manager/pull/143
- Tracking issue: https://github.com/Aliucord/Aliucord/issues/642
- Build-time patches applied by `.github/workflows/build.yml` are listed in README.md.
