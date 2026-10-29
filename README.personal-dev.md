# Glasses Lab: personal development workflow

This checkout is the baseline for Ross's glasses and phone. **Glasses Lab**
(`companion_android/`) is the phone companion, and our **ASG build**
(`asg_client/`) runs on the glasses. The official Mentra App was uninstalled from
the phone on **October 27, 2026**. Daily use and development do not require it,
a Mentra account, or Mentra cloud services.

“Editing the glasses OS” here means editing the ASG Android application. The
glasses retain their existing MTK Android and BES firmware. Their bootloader
remains locked; this workflow builds and installs application APKs.

The 0.4 work adds a simpler capture/library UI, automatic phone-media publication,
automatic offload, and verified per-file glasses cleanup. Phone-media migration
and exact-source cleanup now have hardware evidence. The preceding keep-ready Wi-Fi
pair has locked-screen transfer evidence. The capture-defaults trials add
deployment, phone restart, photo parity, and video evidence; the latest phone
build fixes a settings-delivery failure found during those checks.
[Validation status](#validation-status)
separates those results from earlier tested builds. Build success alone does not
establish that a new transfer/purge path has passed its device checks.

## Everyday use

1. Turn on the glasses and open Glasses Lab. A previously requested connection
   reconnects automatically; use **Connect glasses** for initial setup. Wait for
   **Connected & ready**.
2. Use **Photo**, **Video**, or **Audio**. Video includes sound. The capture card
   shows the timer and **Stop recording**; audio also has **Pause audio** and
   **Resume audio**.
3. Open **Library** to view, play, or share media. Photos, video, and audio have
   separate filters. The transfer queue shows items still waiting for the phone.
4. Use **Settings** for camera and recording defaults, storage allowance,
   free-space reserve, and Android background association. Allow notifications
   for recording status and Stop.
   For the keep-ready build, use **Connect media Wi-Fi** with the app visible
   and phone unlocked, then approve Android's prompt. Ross selected **Keep media
   Wi-Fi ready**; see the connection behavior below.
5. Expand **Audio & calls** for media/call volume and the quiet playback test.
   Normal calls use the Phone app and the glasses' call gesture. **Connection**
   and **Diagnostics** hold setup and troubleshooting controls.

The service owns a started recording independently of the app screens. Explicit
Stop, Android force-stop, and process recovery do not silently start a new
recording. Calls take priority; audio needs explicit Resume after a call. Check
screen-off, idle, and interruption behavior on the actual installed APK pair.

### Where media goes

The 0.4 default is Android's shared media library, under **Glasses Lab**:

| Media | Phone location | Delivery |
| --- | --- | --- |
| Photos | `Pictures/Glasses Lab/` | Save original on glasses, authenticated local Wi-Fi transfer, then verified publication |
| Video with sound | `Movies/Glasses Lab/` | Local Wi-Fi, then verified publication |
| Audio | `Music/Glasses Lab/` | Glasses microphone over BLE; completed WAV segments are published |

Photos/video appear in compatible gallery apps. Audio appears in compatible
audio/file apps; many photo galleries do not display audio. Glasses Lab groups
long audio into one session even though phone media contains ordered segments.
Previously saved private files remain part of the migration/recovery path; failed
publication retains the original.

Photo capture sends its selected size over BLE and saves the original JPEG on
the glasses. Automatic authenticated Wi-Fi sync then transfers it,
publishes and verifies its phone-media copy, and requests reclamation of the
verified glasses source. Keeping the source until publication succeeds protects
against a full phone or a failed transfer. The old Bluetooth photo relay remains
for legacy code/tests; it is no longer the default capture path.

A successful transfer means a durable, verified phone copy. Only then may the
phone request permanent reclamation of that exact glasses file. The fork checks
identity, generation, size, and checksum. An interrupted transfer, stale receipt,
failed publication, or insufficient space leaves the glasses original available
for retry. Cleanup is scoped to our completed media; it does not empty arbitrary
trash or change the stock gallery's retention policy. Device validation must
measure actual reclaimed space, not just a successful HTTP response.

Audio goes directly to the phone and does not accumulate a matching glasses
recording. Video includes sound. The installed October 28 **0.6.0-dev** build uses one
**Recording defaults** section for both phone and glasses-button starts: 720p or
1080p, 5/15/30 FPS, a 3/5/10/15/20/30/60/120-minute maximum or **No time limit**,
and **Offload while recording**. Initial defaults are 720p/30 FPS/five minutes
with offload disabled. One Save action applies the whole profile; Applied means
the glasses acknowledged all its values and revision. Changes affect the next
recording. Short trials passed for app and physical glasses-button starts in
both local and offload mode; endurance acceptance remains open.

### Video recording and offload

With **Offload while recording** off, video saves on the glasses for later
transfer. Local mode remains bounded by the glasses' file and storage limits,
even when a longer maximum or No time limit is selected. With it on, completed
MP4 segments move to the phone while capture continues. Both the app and glasses
button use that selected mode, quality, and duration; there is no separate
phone-only recording settings card.

At installation, the phone's migrated profile was read back as **720p / 30 FPS /
60 minutes / offload on**, revision **6**. Photo remains **High**, revision **2**.
This preserves the choice restored after the earlier trial. Migration keeps
unrelated storage/Wi-Fi preferences and an active session's settings unchanged;
old preview keys are removed after the new profile and revision are saved.
After the local/offload trials, both devices were restored to that same
**720p / 30 FPS / 60 minutes / offload on** choice at revision **8**.

Connect media Wi-Fi before starting in offload mode. The phone checks glasses
capabilities and storage, then grants short-lived, single-use readiness for
either start surface. A missing or expired grant fails explicitly. The phone
adopts a glasses-button start from authoritative recording status; a button
event does not send another Start. Physical Stop remains available without a
grant. Offload mode keeps an approved media Wi-Fi connection ready; it never
opens a background approval prompt.

Defaults are 64 MiB per segment and a 256 MiB temporary payload backlog on the
glasses. The phone allowance and free-space reserve still apply, including
publication staging space. If transfer cannot keep up, the glasses stop at the
backlog or storage limit and retain unsaved segments.

The library groups ordered MP4s as one session; phone gallery apps see separate
files. One joined MP4 export is not implemented. Long recordings can
still end early for battery, heat, radio, or storage conditions. No older phone
recordings are automatically erased, and recovery never starts a new recording
without a user action.

The installed unified **0.6.0-dev / code 6** build passed **105 companion JVM
tests**, with **15 focused native SDK tests** passing. ASG **57624134** passed
**115 focused tests** and its guarded 60-second installation check; the personal
fork remains active. The final installed phone APK passed **32/32 instrumentation
tests**. At installation, the glasses' persisted revision **6** profile matched the phone, and
Settings showed one Recording defaults section with **720p / 30 FPS / 60 minutes**
and **Applied to glasses**. Exact APK hashes and the pre-install backup are in the
active plan linked below.

On that same installed pair, **all four app/button × local/offload trials passed**.
The user physically long-pressed the glasses button for both button starts;
the app's Stop control ended those recordings. App/offload produced two verified
parts (**97.9 MB / 96.8 seconds**) and button/offload produced three
(**158.4 MB / 156.7 seconds**). During the button recording, the phone already had
a verified **63.7 MB / 63.1-second** part while the session still showed Recording.
The two local trials produced one file each (**14.1 and 16.5 seconds**).
All seven final files passed size/hash checks and full decoding as 720p H.264/AAC,
at approximately 29.6 actual FPS for the selected 30 FPS. The app/offload trial
has final-file evidence only; its early live-copy check found no indexed part.
USB remained attached, and the phone was temporarily kept awake for the trials.
Testing ended with the camera idle, no active video, and an empty transfer queue.
The original phone display setting was restored, and Capture showed **Ready to
offload from app or button** with approved media Wi-Fi ready. Library displayed
the two offload sessions as one group each and the two local clips individually;
the earlier **303.5 MB / 5-part** session remained present. Long endurance and
continuous boundary timing remain unproven.

The earlier phone-only Glasses Lab **0.5.0-dev** compiled with **99 passing companion JVM tests** and
**eight passing focused SDK tests**; its instrumentation APK compiled and signing
verification passed. The ASG build passed **73 tests**, and **27 phone
instrumentation tests passed**. A five-minute 720p trial produced five verified,
independently decodable H.264/AAC segments totaling **303.5 MB**. An earlier
segment was reclaimed from the glasses while the next continued recording;
automatic Stop finalized all five and cleared the transfer queue. The phone
screen was off for the latter portion, with **USB still attached**.
The Library showed one **303.5 MB / 5-part** session; playback automatically
advanced from part 1 to part 2, and manual Next navigation worked.
That trial's later phone APK adds readable stop-reason text and passed the same 99 JVM
tests. The active plan distinguishes its hash from the package used for capture.
Those earlier results are separate from the unified trials above.
Frame-perfect picture/audio continuity, full-storage and interruption recovery,
and unplugged screen-off endurance still need device evidence.
See the [design](notes/superpowers/specs/2026-10-28-glasses-lab-segmented-video-design.md)
and [active acceptance plan](notes/superpowers/plans/2026-10-28-glasses-lab-segmented-video.md).

### Keep media Wi-Fi ready

Ross selected **Keep media Wi-Fi ready**. The installed pair and test results are
recorded below. Wi-Fi renewal and locked-screen photo/video offload passed on
the preceding keep-ready ASG; the current deployment also verified retained files
surviving restart unchanged.
After BLE connects, explicitly
use **Connect media Wi-Fi** while Glasses Lab is visible and the phone is unlocked.
That action may show Android's local-network approval prompt. Background polling
must not create a new request or show a popup every five minutes.

The service retains the approved network and the same session token between
transfers. A bounded **180-second glasses idle lease** renews every **60 seconds**;
the phone holds a bounded **180-second wake lock**, renewed every **60 seconds**,
while the approved connection is retained. Locked-screen renewal and offload have passed with USB attached; unplugged/Doze
reliability and power cost still need acceptance. Disconnect, expiry, or service teardown releases ownership;
reconnecting Wi-Fi requires the visible app again. Captures remain queued meanwhile.
Periodic transfers defer during media playback.

Keeping this local Wi-Fi link ready uses **both phone and glasses battery**.
The glasses network has no internet; internet traffic can use the phone's mobile
data and its allowance. The app binds transfer connections to that network,
not the whole process. Unplugged/Doze reliability and power cost still need
measurement on the final pair. In local recording mode, turning the preference
off releases the retained connection after work finishes. **Offload while
recording** keeps an approved connection ready regardless of that saved switch
choice. Changing either setting does not erase queued media.

### Storage and transfer decisions

**Recording allowance (GB)** is an optional combined audio/video budget. Blank
means available space above the reserve, not unlimited physical storage.
**Phone space to keep free (GB)** leaves capacity for other apps. The existing
default reserve is 1 GiB, displayed as approximately **1.074 GB**. The UI uses
decimal GB and accepts a minimum of **0.068 GB**. Photos use actual free-space
checks but are outside the optional audio/video allowance.

Count retained recordings and recording-related temporary work. Publication can
briefly need both a private staging file and a phone-media copy. Transfer
preflight must allow that peak usage, and capacity must be checked while bytes
arrive. Published audio/video still counts after private staging is reclaimed.
Other apps consuming disk space also reduce the available recording allowance.

| Situation | Intended behavior |
| --- | --- |
| Enough headroom | Transfer, verify phone publication, then reclaim the exact glasses source |
| The next file would cross the reserve/allowance | Keep it queued on the glasses; explain why and retry after space is available |
| Space runs out mid-capture/transfer | Stop/finalize recoverable work; retain saved segments and the glasses source |
| Phone unavailable or Wi-Fi awaiting approval | Keep the source; a sent command is not a saved file |
| Call starts | Give the call priority; pause/stop capture and defer transfer |
| User deletes published media in another app | Respect deletion; do not silently recreate it from stale state |

Older phone media is **never automatically erased** to make room. Free space or
increase the allowance, then retry. Avoid starting long glasses-side video when
the phone cannot accept it: glasses storage is a bounded waiting area, not an
unlimited overflow disk. Audio uses about **115 MB/hour**. Battery, temperature,
radio loss, and microphone availability still constrain real duration.

### Shortcuts and a locked phone

Version 0.4 includes Android Quick Settings tiles, notification controls, and
launcher shortcuts. Add **Glasses photo**, **Glasses video**, and **Glasses audio**
in the tile editor; video/audio tiles become Stop controls for a matching capture.
Adding a tile, using it while locked, and pressing a physical key with
the screen completely off are different capabilities. Samsung lock-screen and
foreground-service behavior need device testing. The audio tile has started a
93.2-second recording and stopped it while locked on this Samsung; USB remained
attached, so this does not establish unplugged/Doze operation. Do not assume a screen-off
volume-key gesture or phone-lock bypass. Physical key mappings are not yet part
of the verified baseline. Photo/video tiles and automatic offload passed later
checks on ASG `57534392-dev`; unplugged/Doze use and audible quality remain untested.

## Which code to edit

| Change | Source | Rebuild/install |
| --- | --- | --- |
| Capture, library, settings, styling | `companion_android/app/src/main/java/dev/ross/glasses/`: `MainActivity`, `LibraryActivity`, `RecordingSettingsActivity`, `LabUi` | Phone |
| Capture orchestration and automatic transfers | `GlassesController.kt`, `RecordingController.kt` | Phone |
| Phone-media publication and journal | `PhoneMediaPublisher.kt`, `MediaPublicationStore.kt`, related transfer code | Phone; both if the receipt protocol changes |
| Audio segments, recovery, storage policy | `SegmentedAudioWriter.kt`, `WavWriter.kt`, `PcmRecording.kt`, `RecordingRepository.kt`, `RecordingStoragePolicy.kt` | Phone |
| Background connection, notifications, calls | `CompanionService.kt`, `CompanionActivity.kt`, `CompanionAssociation.kt`, `BluetoothAudio.kt` | Phone |
| BLE command encoding/events | `mobile/modules/bluetooth-sdk/android/` | Phone through the standalone companion build |
| Camera, physical buttons, sounds, gallery cleanup | `asg_client/app/src/main/java/com/mentra/asg_client/` | Glasses |
| New command/capability/receipt | ASG handler + native SDK/phone controller | Both |
| Android/BES firmware, drivers, bootloader | Outside this APK workflow | Separate investigation |

Glasses Lab is a **native Android app**, not the React Native app in `mobile/`.
It compiles checked-out native Bluetooth SDK, LC3, and Silero sources. SDK edits
are included in the next companion build; React Native screen edits are not.
Metro, Expo, Docker, and cloud accounts are unnecessary for our local APKs.

Read [root guidelines](AGENTS.md). For glasses changes, also read
[ASG guidelines](asg_client/AGENTS.md) and the
[Mentra Live reference](asg_client/docs/mentra-live-spec.md). Consult
[mobile guidelines](mobile/AGENTS.md) for shared SDK edits.

## PowerShell setup

Define these variables in each new terminal:

```powershell
Set-Location 'C:\Users\Ross\MentraOS-smart-glasses'
$Adb = 'C:\platform-tools\adb.exe'
$GlassesSerial = '0123456789ABCDEF'
$PhoneSerial = 'R9TW705JXZV'
& $Adb devices -l
```

| Device | Platform | Custom package |
| --- | --- | --- |
| Mentra Live, `0123456789ABCDEF` | Android 11/API 30, arm64-v8a | `com.mentra.asg_client.thirdparty` |
| Samsung SM-A146U, `R9TW705JXZV` | Android 15/API 35 | `dev.ross.glasses` |

Both should show `device`. Unlock/approve USB debugging for `unauthorized` phones;
Developer mode alone does not enable debugging. See the
[phone connection steps](companion_android/README.md#connect-the-android-phone-over-usb).
Always target a serial. The glasses' generic USB serial is supplemented by the
deployment tool's saved firmware/BES identity check.

```powershell
python --version
Get-Content -Raw ./local/asg-dev/toolchain.json
Test-Path ./local/asg-dev/debug.keystore
```

Use Python 3.11+ and Java 17. The JSON supplies `javaHome`/`androidSdkRoot`; scripts
set their build environment and use project Gradle wrappers. ASG uses Android
platform/build-tools 34 and StreamPackLite. The companion uses compile SDK 36,
target SDK 35, build-tools 35.0.0, NDK 27.0.12077973, and CMake 3.22.1.
A replacement PC needs those components/licenses, correct paths, and the
**existing signing key**. Dependency downloads require internet access.

## Phone-only edit → build → test → install

Stop capture/transfers before updating. Native code and UI changes need an APK
rebuild; this app has no JavaScript hot reload.

```powershell
./companion_android/build.ps1 -RunTests -BuildDeviceTests
./companion_android/install.ps1 -Serial $PhoneSerial -Adb $Adb
```

Require `BUILD SUCCESSFUL` and successful signature verification. A failed build
can leave a stale output at:

```text
companion_android/app/build/outputs/apk/debug/app-debug.apk
```

The installer rejects the glasses, installs on the selected phone with `-r -g`,
and opens Glasses Lab. The same package/key preserves data. Pairing, Wi-Fi, and
companion association can still require an unlocked-phone prompt. Reopening
restores a requested connection; explicit Disconnect stays respected.

Instrumentation restarts the app. Run it **only while idle**:

```powershell
& $Adb -s $PhoneSerial install -r -t companion_android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $Adb -s $PhoneSerial shell am instrument -w -r dev.ross.glasses.test/androidx.test.runner.AndroidJUnitRunner
```

Database/media tests use isolated storage; the launch test opens the real screen
and may restore its connection, but does not start capture. Check the test result,
then physically test changed capture/transfer/lifecycle behavior and confirm old
media/settings survived. Omit test switches for a quick compile. `-RebuildApp`
can clear stale generated app output after Kotlin signature changes; it does not
clear phone data or source.

**Phone-only edits do not require reinstalling ASG.** For shared SDK changes,
also run `scripts/check-android-compile.sh bluetooth-sdk` in its supported setup.
That full Expo integration check requires Bun and was blocked by missing Bun in
the October 26 environment; our standalone build is not a substitute for it.

## Glasses edit → build → test → install

Preserve package isolation, the `-dev` marker, maintenance guards, media namespace,
camera cleanup, privacy indicators, and thermal limits. The fork disables stock
OTA/recovery activity and remote crash reporting; local diagnostics remain.
Useful sources include `camera/`, `io/media/`, `io/server/services/`,
`service/core/handlers/`, `AsgConstants.java`, and `DevelopmentSafety.java`.

```powershell
./asg_client/scripts/build-dev.ps1 -RunTests
```

The builder forces the third-party package, tests it, verifies the signature,
and prints the hash. It does not deploy. Output:

```text
asg_client/app/build/outputs/apk/debug/app-debug.apk
```

Default suites cover development safety, OTA commands, factory reset, and the
system controller. `-TestFilter` **replaces** that selection:

```powershell
./asg_client/scripts/build-dev.ps1 -RunTests -TestFilter '*Camera*'
# Broader check when needed:
./asg_client/scripts/build-dev.ps1 -RunTests -TestFilter '*'
# If install/restore tooling changes (fake devices; no real ADB):
python -m unittest discover -s asg_client/scripts -p test_dev_device.py -v
```

Finish calls, captures, and transfers. Inspect the glasses:

```powershell
python asg_client/scripts/dev_device.py inspect --serial $GlassesSerial --adb $Adb
& $Adb -s $GlassesSerial shell id
```

If glasses show `uid=2000`, use their existing root ADB support:

```powershell
& $Adb -s $GlassesSerial root
& $Adb -s $GlassesSerial wait-for-device
& $Adb -s $GlassesSerial shell id
```

Do this on the glasses, not the phone. It restarts ADB without unlocking the
bootloader. Installation requires root, at least 30% battery, temperature below
43°C, an idle camera, and no unfinished stock OTA. Resolve failed preflight checks.

For the normal custom-build workflow:

```powershell
python asg_client/scripts/dev_device.py install --serial $GlassesSerial --adb $Adb --trial-seconds 60 --keep-running
```

Keep the cable/terminal connected until **Fork left active as home**. The tool
preserves stock/recovery APKs and package/home states, updates the fork without
clearing its data, observes foreground-service survival/temperature, and leaves
our build active. No host watchdog remains after the command returns.

When updating an active custom session, the tool briefly restores its saved stock
baseline internally before taking a new snapshot. This is a guarded deployment
step; you do not switch phone companions or reinstall the Mentra App. Never run
another install/restore command concurrently.

For an exploratory change with automatic return to stock after observation:

```powershell
python asg_client/scripts/dev_device.py install --serial $GlassesSerial --adb $Adb --trial-seconds 300
```

Without `--keep-running`, the trial restores stock even on success. Stop capture
before expiry; use the normal keep-running command afterward to return to our
baseline. The timer starts at **Development app running; observing...**. Lost USB,
a killed host process, or PC sleep can prevent automatic restoration; see
[emergency recovery](#emergency-stock-recovery).

Use the guarded tool rather than Android Studio Run or bare `adb install`: it
coordinates package states, permissions, startup, and restoration evidence.
Stock ASG/recovery stay installed with their data but disabled during custom use.

## When a change spans both sides

Define commands/capabilities, request identity, response/error fields, and
persistence ordering first. A cleanup receipt must refer to a durable verified
copy of the exact source generation; keep retry idempotent and reject ambiguity.

1. Implement both ends and relevant tests; build both APKs before a device trial.
2. Install the phone APK while capture is idle.
3. Deploy ASG with the guarded keep-running workflow and reconnect.
4. Verify versions/capabilities, then success, interruption, full-storage,
   cancellation, and retry behavior.
5. Archive the tested pair, source, hashes, and observed results.

Compatibility is between **our two endpoints**. Negotiate capabilities where
needed instead of inferring support from a `-dev` name. Compatibility with the
removed official phone app is not the daily acceptance target.

## Device acceptance checklist

Use short captures first and comfortable playback volume. Keep existing media
while testing failures and identify exact installed APK hashes.

| Scenario | Verify |
| --- | --- |
| Update/reconnect | Correct custom version; media/settings intact; one connection service |
| Photo | Library and gallery image opens; camera released; source reclaimed only after verified delivery |
| Video with sound | Capture stops; automatic transfer completes; phone gallery plays picture and sound |
| Verified cleanup | Phone copy still hashes/reads correctly; exact glasses payload gone; actual free space rises |
| Transfer/publication interruption | Source survives; retry does not duplicate or purge the wrong file |
| Low allowance/free space | Clear stop/defer reason; saved segments retained; no old phone media erased |
| Audio | Expected glasses-mic content, ordered segments, Pause/Resume/Stop work |
| Calls/media/volume | Two-way call, normal playback, correct volume stream, safe recording interruption |
| UI closed/screen off | Capture persists, notification Stop works, reopening reflects service state |
| Unplugged stationary idle | Actual idle/Doze and battery measured, not merely a dark screen while charging |
| Bluetooth/range loss | Bounded reconnect, saved segments, truthful gap; no restart after explicit Stop |
| Device restart | Custom startup/allowed reconnect; no automatic recording start |
| Shortcuts | Confirm which tile/notification actions work while locked versus requiring unlock |

Audio's five-minute files are segments, not a session cap. Earlier endurance
crossed both former time limits; long battery/thermal/idle/storage tests remain.
Local video uses the shared selected maximum subject to glasses file/storage
limits. Shared video settings and offload have an
[active acceptance plan](notes/superpowers/plans/2026-10-28-glasses-lab-segmented-video.md);
earlier ordinary video transfers do not establish its boundary or endurance behavior.

## Back up source, keys, APKs, and media

| Keep | Purpose |
| --- | --- |
| `local/asg-dev/debug.keystore` | Signing identity; keep across PCs/builds |
| `local/asg-dev/toolchain.json` | Java/SDK configuration |
| `local/asg-dev/devices/` | Stock APK/state snapshots, hardware binding, logs |
| `C:\Users\Ross\Mentra-ASG-3.2.1-backup.apk` | Original stock-ASG backup |
| Source commit/archive including new files | Reproduce the actual tested source; a tracked diff alone is insufficient |
| Tested APK pair, SHA-256, test notes | Reinstall a known combination |
| Shared phone media plus private index/settings | Media and the metadata needed to recover sessions/publication |

Ignored `local/` files and keys are not in Git. Keep a separate backup; do not run
cleanup that erases ignored local state. APK/state snapshots are **not firmware
backups**.

### Archive the tested pair

Verify these output files are the ones actually tested:

```powershell
$CheckpointDir = Join-Path (Get-Location) ('local/asg-dev/checkpoints/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $CheckpointDir | Out-Null
Copy-Item -LiteralPath './companion_android/app/build/outputs/apk/debug/app-debug.apk' -Destination (Join-Path $CheckpointDir 'phone-tested.apk')
Copy-Item -LiteralPath './asg_client/app/build/outputs/apk/debug/app-debug.apk' -Destination (Join-Path $CheckpointDir 'asg-tested.apk')
Copy-Item -LiteralPath './local/asg-dev/debug.keystore' -Destination (Join-Path $CheckpointDir 'debug.keystore')
Get-FileHash -Algorithm SHA256 (Join-Path $CheckpointDir 'phone-tested.apk'), (Join-Path $CheckpointDir 'asg-tested.apk')
git status --short
```

Save the source revision, all local/new files, firmware, and results alongside it.
A source commit plus a separate ignored-state backup is a durable arrangement.
If one APK changed, preserve the partner actually tested with it.

### Back up phone media and private state

Stop recording/transfers. Copy shared media with Share, a file manager, or ADB.
Album folders appear after their first published item:

```powershell
$MediaBackup = Join-Path (Get-Location) ('local/asg-dev/media-backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $MediaBackup | Out-Null
& $Adb -s $PhoneSerial pull '/sdcard/Pictures/Glasses Lab' (Join-Path $MediaBackup 'photos')
& $Adb -s $PhoneSerial pull '/sdcard/Movies/Glasses Lab' (Join-Path $MediaBackup 'video')
& $Adb -s $PhoneSerial pull '/sdcard/Music/Glasses Lab' (Join-Path $MediaBackup 'audio')
```

A missing album has nothing to copy. Check the other command results and open
wanted files from the backup. Shared media is separate from the private session
index/publication journal. Back up debug-app private state while stopped, with a
binary-safe stream:

```powershell
& $Adb -s $PhoneSerial shell am force-stop dev.ross.glasses
$PrivateBackup = Join-Path $MediaBackup 'glasses-lab-private.tar'
$BackupScript = @'
import subprocess, sys
with open(sys.argv[3], 'wb') as output:
    subprocess.run([sys.argv[1], '-s', sys.argv[2], 'exec-out', 'run-as',
                    'dev.ross.glasses', 'tar', '-cf', '-',
                    'files', 'databases', 'shared_prefs'], stdout=output, check=True)
'@
python -c $BackupScript $Adb $PhoneSerial $PrivateBackup
& $Adb -s $PhoneSerial shell am start -n dev.ross.glasses/.MainActivity
```

Old Windows PowerShell text redirection can corrupt binary `adb exec-out` output.
A private tar excludes shared MediaStore files, so retain both backups. Do not
restore databases over a running app or mix them with unrelated media.

Copy pending glasses media without deleting it:

```powershell
& $Adb -s $GlassesSerial pull /sdcard/asg_media.thirdparty (Join-Path $MediaBackup 'glasses-pending')
```

Stock uses `/sdcard/asg_media/`; our fork uses `/sdcard/asg_media.thirdparty/`.
Do not merge/erase them as a transfer workaround.

## Roll back to a known working custom pair

Stop capture/transfers and preserve current media before replacing either APK.
Existing dated checkpoints include:

- `local/asg-dev/checkpoints/20261026-121139-before-extended-audio/`: earlier
  source/APKs/key, phone private data, official phone base/split APK backup.
- `local/asg-dev/checkpoints/20261026-142927-foreground-reopen/`: 0.3 phone build,
  ASG `57450965-dev`, source/key/hashes, before phone-media migration.
- `local/asg-dev/checkpoints/20261027-114059-phone-media/`: complete 0.4 source,
  phone/ASG APKs, and signing key before real-media migration. This is an
  implementation checkpoint with passing software tests, preceding the latest
  phone fixes. Live pair acceptance remains pending.
- `local/asg-dev/checkpoints/20261027-verified-phone-media/`: immutable earlier 0.4
  pair and post-migration library backup. Keep its contents unchanged.
- `local/asg-dev/checkpoints/20261027-persistent-media-wifi/`: preceding keep-ready
  pair archive. The current phone deployment checkpoint is
  `local/asg-dev/checkpoints/20261027-183903-capture-defaults-delivery-fix/`; its hashes and
  evidence are recorded below. Keep older checkpoint contents unchanged.

Inspect the checkpoint's APK names and manifest first. Current archives use
`phone-tested.apk`; some older examples/checkpoints use `glasses-lab-tested.apk`
or versioned names. Set the two paths to the actual verified files:

```powershell
$CheckpointDir = 'C:\Users\Ross\MentraOS-smart-glasses\local\asg-dev\checkpoints\YOUR_CHECKPOINT'
Get-ChildItem -LiteralPath $CheckpointDir -Filter '*.apk'
$ArchivedPhoneApk = Join-Path $CheckpointDir 'phone-tested.apk'
$ArchivedAsgApk = Join-Path $CheckpointDir 'asg-tested.apk'
if (!(Test-Path -LiteralPath $ArchivedPhoneApk) -or !(Test-Path -LiteralPath $ArchivedAsgApk)) { throw 'Choose the actual APK filenames from this checkpoint.' }
& $Adb -s $PhoneSerial install -r -g $ArchivedPhoneApk
if ($LASTEXITCODE -ne 0) { throw 'Archived phone APK installation failed.' }
python asg_client/scripts/dev_device.py install --serial $GlassesSerial --adb $Adb --apk $ArchivedAsgApk --trial-seconds 60 --keep-running
& $Adb -s $PhoneSerial shell am start -n dev.ross.glasses/.MainActivity
```

Use the same package/key. If Android rejects an older version code, rebuild saved
source with a newer code and the same key **after checking data compatibility**.
Do not clear/uninstall Glasses Lab to bypass an update error.

The audio database is `recordings.sqlite`, schema 1. The 0.4 publisher adds
`media-publications.sqlite`, schema 1. Unchanged schema numbers do not make old
code compatible with relocated media: 0.3 expects private files; 0.2 does not
display indexed audio sessions. After private staging is reclaimed, shared
media may still open in other apps while old Glasses Lab cannot find it. Prefer
a tested 0.4-or-later rollback pair after migration, and preserve both indexes
and media before experimenting with older code.

## Inspect versions and collect focused logs

```powershell
python asg_client/scripts/dev_device.py inspect --serial $GlassesSerial --adb $Adb
& $Adb -s $GlassesSerial shell dumpsys package com.mentra.asg_client.thirdparty | Select-String 'versionName=|versionCode=|User 0:'
& $Adb -s $PhoneSerial shell dumpsys package dev.ross.glasses | Select-String 'versionName=|versionCode='
& $Adb -s $GlassesSerial shell dumpsys media.camera | Select-String 'Active Camera Clients' -Context 0,1
```

Local builds can share a version name. Compare the exact single-APK phone hash:

```powershell
Get-FileHash -Algorithm SHA256 ./companion_android/app/build/outputs/apk/debug/app-debug.apk
$PhoneApkPath = ((& $Adb -s $PhoneSerial shell pm path dev.ross.glasses) -replace '^package:', '').Trim()
& $Adb -s $PhoneSerial shell sha256sum $PhoneApkPath
```

ASG's `state.json` records `developmentApkSha256`. Current glasses state/logs are
under `local/asg-dev/devices/2cbaca25d954/`; `inspect` locates the correct directory
for different hardware. Copy trial logs before a later trial overwrites them.

```powershell
$LogDir = Join-Path (Get-Location) ('local/asg-dev/manual-logs/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $LogDir | Out-Null
$GlassesAppPid = (& $Adb -s $GlassesSerial shell pidof com.mentra.asg_client.thirdparty)
if (!$GlassesAppPid) { throw 'Custom ASG is not running. Inspect first.' }
& $Adb -s $GlassesSerial logcat -d -t 1500 "--pid=$GlassesAppPid" | Set-Content -LiteralPath (Join-Path $LogDir 'asg.log') -Encoding utf8
$PhoneAppPid = (& $Adb -s $PhoneSerial shell pidof dev.ross.glasses)
if (!$PhoneAppPid) { throw 'Open Glasses Lab first.' }
& $Adb -s $PhoneSerial logcat -d -t 500 "--pid=$PhoneAppPid" 'GlassesLab:I' 'AndroidRuntime:E' '*:S' | Set-Content -LiteralPath (Join-Path $LogDir 'phone.log') -Encoding utf8
```

Keep logs locally and redact credentials/personal content before sharing. SDK
logs can contain hotspot credentials/request tokens. Record the action, hashes,
firmware, and actual result alongside failures.

## Troubleshooting

| Symptom | Next step |
| --- | --- |
| Phone missing/unauthorized | Enable USB debugging, unlock/approve PC, check data cable/driver |
| Build fails but APK exists | Treat it as stale until build/signature checks succeed |
| Signature mismatch | Restore the original key; a new key cannot update the existing package |
| Phone changes absent | Edit standalone app/included SDK, rebuild, select phone serial, compare hash |
| Stock ASG version 3.2.1 appears | Trial may have restored stock; deploy custom ASG with `--keep-running` |
| Root/preflight failure | Resolve root ADB, battery, temperature, camera, or OTA state; retain guards |
| BLE works but no call/media audio | Enable Calls/Media audio and check Android's selected route |
| Audio pauses during music | Firmware may interrupt glasses mic; finish capture before playback |
| Video stays queued | Check storage, custom ASG, BLE readiness, Nearby Wi-Fi, and Android Wi-Fi approval; retry |
| Video Stop uncertain | Reconnect/use Stop; request identity and the active recording's selected limit are preserved |
| Phone copy missing | Inspect phone media and retained source; do not purge glasses to clear the error |
| Gallery empty | Check Pictures/Movies albums; audio belongs in Music/file apps; inspect publication status |
| Android stopped app | Open it and inspect recovered media; do not assume capture restarted |
| Trial loses cable/terminal | Reconnect, ensure no other deployment runs, then use guarded recovery |
| Restore rejects identity/version/state | Preserve backups and investigate; do not edit state to bypass mismatch |

## Validation status

This dated record is not a live device readout. Update it with each tested pair.
The [implementation plan](notes/superpowers/plans/2026-10-26-glasses-lab-independent-companion.md)
tracks remaining gates.

**Completed before 0.4:**

- Mentra App `com.mentra.mentra` uninstalled October 27. Base/split APKs remain
  backed up; Ross confirmed no app-only media needed saving.
- Firmware `MentraLive_20260923.0`, BES `26.9.29.0`; stock ASG 3.2.1/build 302010073
  and recovery 1.2.0/build 11 retained, disabled during custom use.
- ASG `57450965-dev` passed guarded deployment and automatic startup after a
  normal Android reboot in about 35 seconds. Locked bootloader/green verified
  boot unchanged; earlier rollback checks restored stock package states.
- Glasses Lab 0.3 passed 49 JVM and eight phone instrumentation tests; ASG passed
  27 focused tests. Host deployment tests passed in earlier work.
- Initial 0.3 audio build saved **45:22.25**, ten valid WAVs, 87,112,440 bytes,
  no indexed gap, and released the wake lock on Stop. Screen off **with USB
  attached**, idle state ACTIVE: not unplugged/Doze evidence. Later lifecycle
  fixes were installed afterward.
- Earlier device checks covered photo delivery, reconnect, short glasses audio,
  a 49-second 720p video with non-silent soundtrack, and user-confirmed two-way
  calling. These do not establish the new automatic offload/purge path.

**Earlier 0.4 migration-pair hardware evidence on October 27:**

- The initial migration preserved **15 public files** (three photos and twelve
  WAV segments), **92,721,981 bytes**, verified against the PC backup and again
  after restarting the app. Both finished audio sessions retained their index,
  including the 45-minute recording. Canonical-path matching now handles Android's
  `filesDir` aliases when selecting committed WAVs for publication.
- Live glasses offload then produced **eight completed receipts** totaling
  **72,873,757 bytes**. Each maps to a verified phone publication with matching
  identity, size, and SHA-256; every corresponding glasses source and quarantine
  `.payload` was absent. All **23 public files** passed checksum verification.
  Glasses media usage fell from **70,908 KiB to 532 KiB**.
- **Six IMU sidecars were retained immediately after the first offload.** Their
  later loss during inherited startup cleanup is documented below. Two generated video
  thumbnails were accidentally imported as photos during this first offload.
  Future manifests classify them as auxiliary thumbnails; the existing two phone
  copies have not been deleted. Retained auxiliary counts/bytes are now explicit
  full-gallery totals, rather than inferred from one manifest page.
- Companion Device Manager association reports **Ready** and Android battery
  access was set to **Unrestricted**. Software checks showed A2DP playback of
  approximately eight seconds of video and opening/playing the long WAV session.
  Ross was not wearing the glasses, so heard audio quality is **not verified**.
- The audio Quick Settings tile recorded **93.2 seconds**, **2,982,400 PCM bytes**,
  with **1,490,383 nonzero samples** and peak magnitude **12,895**. It reached
  **FINISHED**, was published and checksum-verified, and stopped while the phone
  was locked. The screen was off with USB attached: this is not unplugged/Doze
  endurance evidence. The subsequent snapshot contains **24 verified public
  files**, **168,578,182 bytes**, and three FINISHED audio sessions; no completed
  private capture files remain.
- Earlier software evidence for the migration pair was 62 companion JVM tests,
  16 selected Android tests, and 82 focused ASG tests with one Windows symlink
  case skipped. The earlier phone hash was
  `98BF80042A06E20879C25963FFAA65EE7FB54EEC44DD83FA0E05B190424F13D0`;
  ASG `57529418-dev` was
  `B801D6626EA3C5970612AE9B0DCDD7255F6862223905E7D1A725FB8CD1FDDF26`.
  These are historical identifiers; the later keep-ready and current
  capture-defaults pairs are recorded below.

**Earlier keep-ready pair — October 27, 2026:**

- Phone SHA-256 remains
  `993B7A75393F823800B190F0B5BA99B8F2B54AC1005308F657D6873A8DA73187`:
  **75 JVM tests and all 18 Android tests passed**, including UI binding.
- Final ASG build **57536376-dev**, SHA-256
  `B40CD8F54479C19179BAF872E5B9C3ED9A02EDE3C300896814E0BB4AFA853434`,
  is signed and passed **104 tests across 14 suites**, with one host symlink skip.
  **Its guarded 60-second deployment passed**, leaving the fork active as home.
  All 13 retained files (three capture auxiliaries and ten receipt journals)
  remained present with identical sizes and SHA-256 across deployment/restart.
- The earlier Wi-Fi/photo/video hardware checks used the same phone APK with ASG
  **57534392-dev**, SHA-256
  `85A75C2D828EDCEF4142CD2A0851A984BA3D6036B315725BB44D3D7DE64E1CFD`.
  Wi-Fi was approved at **13:20**, and locked-screen lease renewal was observed
  for approximately **eight minutes**. The phone's bounded 180-second wake lock
  and ASG idle lease renew every 60 seconds; both consume battery.
- At **13:22**, the photo tile captured a **1920×1080 JPEG / 254,135 bytes** while
  the phone was locked/screen off. It was automatically saved, verified, and
  reclaimed from the glasses.
- At **13:27**, the locked-screen video tile recorded **18.871 seconds** of
  **H.264 1280×720** plus **AAC 44.1 kHz mono**, **19,472,812 bytes**. Full video
  decoding and nonzero decoded audio were confirmed. Automatic phone publication
  and exact-source reclamation completed without another connection prompt.
- The subsequent snapshot verifies **26 public files / 188,305,129 bytes**, with
  no private staging files. **Ten completed glasses receipts / 92,600,704 bytes**
  match verified phone files; corresponding sources and `.payload` files are gone.
- **Three auxiliary files remain:** the new photo IMU sidecar, new video IMU
  sidecar, and video thumbnail. The six older IMU sidecars disappeared during an
  earlier ASG restart because inherited startup cleanup unconditionally removed
  orphan capture folders. Final ASG `57536376-dev` fixes this in the fork by
  preserving every nonempty directory. The final guarded deployment verified
  that all three current auxiliaries and ten receipt journals survived restart
  unchanged. The six older sidecars were not recovered; their earlier loss was
  outside the verified-receipt protocol.
- At **13:41:16**, BLE disconnect released the phone wake lock after approximately
  **21 minutes** of retained connection, with no automatic Wi-Fi approval prompt.
  The official phone package `com.mentra.mentra` was confirmed absent.
- Ross **skipped the call test**. Subjective hearing/volume quality remains
  unconfirmed because the glasses were not being worn. Decodable/nonzero audio
  does not establish heard quality. USB remained attached during these screen-off
  checks; unplugged/Doze behavior is still untested. The earlier 93.2-second audio
  tile test belongs to the older `98BF…` phone / `B801…` ASG pair.

**Initial capture-defaults trial — October 27, 2026:**

- Both APKs were installed while capture and transfers were idle. Phone SHA-256:
  `AD3EA2465A3B834E9ACD1B5B2B4B543D7EC104FD2EC30726C5663238EB01C0A7`;
  ASG **57550478-dev** SHA-256:
  `5145EB02D8553F0C1B0C9F4D88EB8C8B147A791726C6B5AE3883FB159EA2817D`.
  The companion passed **88 JVM tests**, ASG passed **91 focused tests across
  11 suites**, and the standalone SDK checks passed **15 tests**. All **five
  isolated preference instrumentation tests passed** on the phone.
- The guarded ASG deployment preserved all **13 retained files** unchanged:
  three capture auxiliaries and ten receipt journals.
- Settings saved Low photos and **1080p/15 FPS/three-minute** videos. Both reached
  Applied to glasses, and the persisted ASG values matched. Force-stopping and
  relaunching the idle phone app preserved both profiles; it automatically
  reconnected and returned to Ready to capture. Earliest replay ordering has
  not yet been verified.
- One phone-triggered 1080p/15 FPS/three-minute video automatically ended at
  **180.472611 seconds**. Its output is **1920 × 1080**, averaging **about
  14.60 FPS** (`1185750/81203`), with AAC 44.1 kHz mono audio. Camera state was
  idle afterward. Evidence:
  `local/asg-dev/capture-defaults-evidence/phone-1080p15-auto3min/summary.json`.
- **Initial device failure:** saving 720p/5 FPS/five-minute defaults during that
  video timed out; idle Retry also failed and ASG retained its previous defaults.
  The phone's active-video snapshot retained the original profile. The delivery
  fix and successful mid-recording retest are recorded below. Initial evidence:
  `local/asg-dev/capture-defaults-evidence/active-video-snapshot.json`.
- A phone-triggered Low photo produced a **960 × 720 JPEG / 255,419 bytes**.
  Its published phone copy matched SHA-256
  `a1ea494194a1aef73012b65d81fbc201f25773118a53020b6da11a3e2ddeb8a6`.
  The subsequent audit verified all **27 published files / 188,560,548 bytes**;
  database integrity passed and no private staging files remained.
- With the companion force-stopped and its process absent, an injected `cs_pho`
  event through ASG's `IntentCommandReceiver` produced a **960 × 720 Low JPEG /
  254,507 bytes**, SHA-256
  `ad989d9720c65510f42f9eebc56260bf2ff4d1b55dca850db053ef7b660af095`.
  Its original was retained until the final verified offload below. This verifies the offline
  software button path; it is not physical-button evidence. Details:
  `local/asg-dev/capture-defaults-evidence/offline-button-low/summary.json`.
- Restarting the idle ASG process while the companion remained stopped retained
  Low: another simulated button photo produced **960 × 720 / 255,102 bytes**.
  Evidence: `local/asg-dev/capture-defaults-evidence/offline-button-low-after-asg-restart/summary.json`.
  The first phone and simulated-button JPEGs had identical quantization tables
  (`local/asg-dev/capture-defaults-evidence/low-photo-quality-summary.json`). Their
  metadata does not report an explicit JPEG-quality number. This supports Low
  encoding parity for those two files.
- A user-confirmed physical short press produced a **960 × 720 Low JPEG /
  212,255 bytes**, SHA-256
  `6f89da8b6ee075b1438ef552c418ae02c9f670013935b6243d9b407886a5720a`.
  Dimensions and both JPEG quantization tables match the phone Low photo.
  Evidence: `local/asg-dev/capture-defaults-evidence/physical-button-low.json`.
  This establishes one physical Low comparison, not the other photo sizes or
  physical-button video behavior.
- The remaining photo parity checks, video profiles and automatic duration stops,
  same-link ASG restart, offline edits/reconnect, and the remaining defaults acceptance matrix
  are still open. These results establish deployment, final phone-restart
  readiness, initial settings application, one three-minute phone video,
  one Low phone-photo transfer, one physical Low photo comparison, and two
  simulated offline button photos before and after an ASG process restart.
  The settings-delivery timeout led to the fixed phone build below.

**Current capture-defaults delivery fix — October 27, 2026:**

- Installed phone SHA-256:
  `E16CBCB83C68AF75A751719B3989AF8E06BD23E3EBA943BE9618F686A48AC61C`.
  It passed **88 JVM tests** with no failures/skips and APK signature verification.
  ASG remains **57550478-dev**,
  `5145EB02D8553F0C1B0C9F4D88EB8C8B147A791726C6B5AE3883FB159EA2817D`.
  The five preference instrumentation passes above belong to the initial phone
  trial; they were not rerun on this phone build.
- The SDK video-defaults command now wakes the glasses before delivery, matching
  photo and duration settings. Reconnect applied and persisted 720p/5 FPS/five
  minutes. Settings also opens without automatically focusing a storage input
  and showing the keyboard. An explicit Recording allowance tap opened the
  keyboard; no storage values were changed. Evidence:
  `local/asg-dev/capture-defaults-evidence/settings-focus-check.json`.
- During a phone video at 720p/5 FPS/five minutes, saving 1080p/15 FPS/three
  minutes reached Applied to glasses and updated ASG preferences. The active
  snapshot remained 720p/5 FPS/five minutes, and manual Stop worked. The completed
  **79,388,837-byte** clip was **1280 × 720 / 77.673578 seconds**, averaging
  **about 5.002 FPS** (`34920000/6981073`), with AAC 44.1 kHz mono audio.
  Evidence: `local/asg-dev/capture-defaults-evidence/fixed-active-video-snapshot.json`
  and `local/asg-dev/capture-defaults-evidence/fixed-phone-720p5-manual-stop/summary.json`.
- A user-confirmed physical-button video at 1080p/15 FPS/three minutes
  automatically ended at **180.239022 seconds**. Its **354,548,852-byte** file is
  **1920 × 1080**, averaging **about 14.60 FPS** (`1183950/81091`), with AAC
  44.1 kHz mono audio. While the camera was active, changing future defaults to
  720p/5 FPS/five minutes was confirmed on ASG; the physical recording retained
  its original three-minute limit. Evidence:
  `local/asg-dev/capture-defaults-evidence/physical-button-1080p15-auto3min/summary.json`
  and `local/asg-dev/capture-defaults-evidence/physical-video-updated-defaults.json`.
- A phone-triggered High photo produced **3264 × 2448 / 1,753,019 bytes** and
  passed publication/checksum verification. Evidence:
  `local/asg-dev/capture-defaults-evidence/phone-high.json`.
- All new photos and videos completed verified offload. The final audit verified
  **35/35 published files / 980,054,447 bytes**, database integrity, and no private
  staging files. The 15 original backup files still match, and all three earlier
  audio sessions remain FINISHED. The glasses receipt audit passed **19 receipts /
  884,350,022 bytes**, with no errors and corresponding source/quarantine payloads
  absent. Evidence paths are listed below.
- Testing ended idle with initial defaults restored and Applied to glasses:
  **High photos and 720p/30 FPS/five-minute videos**, confirmed on both devices.
  Evidence: `local/asg-dev/capture-defaults-evidence/final-state.json`, including
  actual installed APK hashes, persisted profiles, and idle capture state.
- The observed settings-delivery failure is resolved by these device retests.
  The broader photo/video, duration, lifecycle, and failure matrix remains open.

The pre-update **24 files / 168,578,182 bytes** and private databases are backed
up under `local/asg-dev/checkpoints/20261027-before-media-connection/`, referencing
the earlier `98BF…` / `B801…` source/APK pair. The preceding keep-ready checkpoint is
`local/asg-dev/checkpoints/20261027-persistent-media-wifi/`. The initial capture-defaults
checkpoint, `local/asg-dev/checkpoints/20261027-175239-capture-defaults/`, preserves
the preceding APKs, phone private data, both initial trial APKs, and their hashes.
The current checkpoint is
`local/asg-dev/checkpoints/20261027-183903-capture-defaults-delivery-fix/`; it
preserves the initial trial phone APK, updated phone APK, private phone data, and
hashes. The unchanged ASG APK remains in the initial checkpoint. Inspect each
checkpoint's recorded contents and keep older checkpoints immutable.

Evidence under `local/asg-dev/media-validation/`:

- `20261027-190056/summary.json` and `publications.json`: final 35-file audit,
  including all new photo/video publications, matching checksums, and no private
  staging files.
- `20261027-190135-275522-glasses-receipts/receipt-validation.json`: all 19 receipts
  verified, no errors, and corresponding source/quarantine payloads absent.
- `20261027-175320-startup-guard/summary.json`: current ASG deployment retained
  all 13 files unchanged; `changedOrMissingFiles` is empty.
- `20261027-180120/summary.json` and `publications.json`: all 27 public files
  verified after the Low phone-photo smoke check. Image dimensions and checksum
  are recorded separately in `local/asg-dev/capture-defaults-evidence/phone-low.json`.
- `20261027-134113-startup-guard/summary.json`: earlier keep-ready ASG deployment retained
  all 13 files unchanged (three capture auxiliaries and ten receipt journals);
  `changedOrMissingFiles` is empty.
- `20261027-132845/summary.json` and `publications.json`: all 26 public files
  verified after the locked-screen photo/video tests; `tile-video-validation.json`
  records complete video decode, audio properties, and capture conditions.
- `20261027-123057/publications.json`: 23 verified public files after first offload.
- `20261027-124001-355259-glasses-receipts/receipt-validation.json`: all eight
  completed receipts, matched hashes/bytes, and absent source/quarantine payloads.
- `20261027-125020/summary.json`: the tile audio recording and 24-file snapshot.
- `20261027-115828/summary.json` and `20261027-120039/summary.json`: initial
  private-library migration and restart checks.

**Remaining hardware gates:** Wi-Fi expiry and foreground reconnect after
disconnect; unplugged stationary idle/Doze and battery cost; user-heard
playback/volume quality; the skipped call/interruption test; and
actual low-storage recovery. Configurable defaults still need Medium/Maximum,
High button parity and the cold/warm photo matrix; the remaining resolution/FPS
combinations and longer automatic stops; earliest replay, same-link ASG restart,
offline edits/reconnect, and failure edges. Low phone/physical-button parity,
High phone capture, three-minute video stops from both triggers, changes to
defaults during capture, manual Stop, and verified offload passed as detailed
above. These checks do not complete the full matrix.
Photo/video tile capture and automatic offload passed on the preceding pair as
described above. Direct-to-phone long-video recording has **not been prototyped**.
Earlier endurance evidence remains in `local/asg-dev/extended-audio-validation/`
and must not be attributed to a later APK.

## Emergency stock recovery

Normal recovery targets a known working **custom pair**. Preserved stock
ASG/recovery provides an additional fallback if custom software cannot run.
Stop captures/transfers and let any existing trial finish its restoration:

```powershell
python asg_client/scripts/dev_device.py restore --serial $GlassesSerial --adb $Adb
python asg_client/scripts/dev_device.py inspect --serial $GlassesSerial --adb $Adb
```

Expected success: **Stock app and saved package states restored**. This disables
the fork while retaining its data, restores original package/home states, and
starts saved stock services. It does not install a phone companion or replace
firmware. The official phone app is absent, and stock ASG cannot serve our
separate media namespace. This is recovery, not routine app switching.

Preserve state/backups if restore refuses changed hardware, firmware, versions,
or missing state. Do not flash partitions, unlock the bootloader, or erase media
as an APK troubleshooting step.

References: [companion guide](companion_android/README.md),
[ASG deployment guide](asg_client/docs/development-on-stock-firmware.md),
[Mentra Live platform reference](asg_client/docs/mentra-live-spec.md).
