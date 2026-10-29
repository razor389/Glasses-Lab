# Glasses Lab for Android

Glasses Lab (`dev.ross.glasses`) is the sole phone companion for our custom ASG
in this checkout. The official Mentra App was uninstalled on October 27, 2026.
No Mentra account or cloud service is required. The glasses retain their existing
vendor firmware; our phone and ASG APKs are the development baseline.

See the [personal development workflow](../README.personal-dev.md) for the complete
Windows edit → build → test → deploy process, media backups, and custom rollback.

Version **0.4.0-dev** implements:

- Prominent Photo/Video/Audio controls, one active recording card, and a filtered
  chronological library. Connection, volume, and diagnostics are secondary controls.
- Automatic photo/video offload over the glasses' local Wi-Fi link, with default
  publication into Android phone media and exact-source reclamation after verification.
- Audio from the glasses microphone over BLE, with Pause/Resume, five-minute WAV
  segments, and no application session-duration ceiling.
- A recording-storage allowance, free-space reserve, and recoverable transfer queue.
- Notification controls, three Quick Settings tiles, and launcher shortcuts.
- Shared camera/video defaults in Settings, retained through reconnect and app restart.
- Standard Bluetooth media/call status, volume controls, and a quiet test tone.

Phone-media migration and live glasses offload have passed the checks below,
including ten verified reclamation receipts on the preceding keep-ready pair.
That pair has locked-screen Wi-Fi/photo/video evidence. The capture-defaults
trials add deployment, phone restart, photo parity, and video evidence; the latest
phone build fixes a settings-delivery failure found during those checks.
[Validation](#validation) records
what was actually tested. Video starts with **720p/30 FPS with sound, up to five
minutes**, and Settings can select another supported profile. Earlier hardware
evidence is identified separately from the capture-defaults checks below. Long
direct-to-phone video streaming is a later milestone.

## Build and install

From the repository root:

```powershell
./companion_android/build.ps1 -RunTests -BuildDeviceTests
adb devices -l
./companion_android/install.ps1 -Serial YOUR_PHONE_SERIAL
```

The Windows build reads ignored `local/asg-dev/toolchain.json` and uses the
existing `local/asg-dev/debug.keystore`. Keep that signing key for future updates.
The scripts use project Gradle wrappers; required Android SDK components/licenses
must be available, and dependency downloads need internet access.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Wait for a successful build and
signature verification; a failed build can leave an old APK at that path.
The installer requires an explicit USB serial, rejects Mentra Live glasses,
installs only the phone package with `-r -g`, and opens Glasses Lab. Normal updates
with the same key preserve app data. No phone root is required.

The APK minimum is Android 9/API 28, but the current shared-media and local Wi-Fi
workflow requires **Android 10/API 29 or newer** and an ARM phone. The physical
validation baseline is the Samsung SM-A146U on Android 15; other phones are not
established by the existing tests.

Instrumentation restarts the app process. Run it only with capture/transfers
stopped:

```powershell
adb -s YOUR_PHONE_SERIAL install -r -t companion_android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s YOUR_PHONE_SERIAL shell am instrument -w -r dev.ross.glasses.test/androidx.test.runner.AndroidJUnitRunner
```

Repository/publication tests use separate databases and unique test-media albums.
The full suite also includes a real main-screen launch test, which may restore
the saved connection but does not start capture. The selected October 27 tests
listed below intentionally excluded that launch test before user-media migration.
`-RebuildApp` clears generated app build output when stale Kotlin compilation
artifacts need rebuilding; it does not clear source or phone data.

### Connect the Android phone over USB

On Samsung, open **Settings → About phone → Software information** and tap
**Build number** seven times. Then enable **Developer options → USB debugging**.
Reconnect the data cable, keep the phone unlocked, and accept **Allow USB debugging**
for this PC. Developer mode alone does not enable debugging. See the
[Android developer-options guide](https://developer.android.com/studio/debug/dev-options).

`adb devices -l` must list the phone separately with status `device`. `unauthorized`
means approval is still needed. Windows File Explorer visibility alone does not
establish ADB; check the cable/driver using the
[Android device-connection guide](https://developer.android.com/studio/run/device)
if it does not appear. Deploy Glasses Lab to the phone serial, not the glasses.

## Use the custom baseline

If the glasses already run the intended custom ASG, leave it running. For an ASG
update, use the guarded workflow rather than a bare install:

```powershell
python asg_client/scripts/dev_device.py install --serial YOUR_GLASSES_SERIAL --trial-seconds 60 --keep-running
```

Wait for **Fork left active as home** before disconnecting USB. The tool preserves
the stock packages/recovery state and observes the custom app. See the
[ASG deployment guide](../asg_client/docs/development-on-stock-firmware.md) for
root ADB, checks, and emergency restoration. A trial without `--keep-running`
returns to stock at expiry; it is not the normal daily baseline.

Open Glasses Lab, grant the requested Nearby devices/Location/Notifications and
Nearby Wi-Fi permissions, and select the glasses through **Connect glasses** or
**Connection → Find glasses**. Wait for **Connected & ready**. **Diagnostics**
shows the custom ASG version. Setup for the new automatic offload protocol also
requires its advertised capability, not just a `-dev` suffix.

Use **Photo**, **Video**, or **Audio** and **Library** for saved results. In
**Settings**, approve **Enable background association** for the selected glasses.
For the keep-ready pair, explicitly use **Connect media Wi-Fi** with the app
visible and phone unlocked to approve the local network. No official phone app
needs to be disconnected, reopened, or reinstalled.

## Keep media Wi-Fi ready

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
measurement on the final pair. Turning the preference off releases the retained
connection after work finishes; it does not erase queued media.

## Capture, automatic offload, and phone media

**Photo:** the BLE command asks ASG to save an original at the selected size locally.
Automatic authenticated Wi-Fi sync then downloads it, publishes/verifies the
phone-media copy, and requests reclamation of that exact glasses source. This
keeps a source through transfer/publication failure. The old BLE photo relay and
`LocalPhotoReceiver` remain legacy/test code, not the default capture path.

**Video:** tap **Video**, then **Stop recording**. ASG captures H.264 video and AAC
microphone sound with recording indicators/cues enabled. Completed clips enter
automatic local offload. The keep-ready design obtains hotspot credentials over
BLE, retains the explicitly approved Wi-Fi network/token between transfers, and
uses that network only for local transfer connections. New background polling
does not request Android connection approval.
Failed/deferred items remain visible in the queue with retry controls.

### Camera and recording defaults

Settings has independent **Camera defaults** and **Recording defaults** cards:

| Setting | Choices | Initial value |
| --- | --- | --- |
| Photo size | Low, Medium, High, Maximum | High |
| Video resolution | 720p, 1080p | 720p |
| Video frame rate | 5, 15, 30 FPS | 30 FPS |
| Maximum video length | 3, 5, 10, 15, 20 minutes | 5 minutes |

Save stores the choice on the phone. The card shows whether it is waiting for a
connection, applying, applied to the glasses, or needs a retry. Once applied,
phone buttons, notification actions, shortcuts, tiles, and the glasses button
use the same defaults. Offline glasses use their last received defaults. New
defaults apply to the next capture; a current video retains its original limit.
Unsaved choices survive rotating the settings screen.

Photo capture requires the matching ASG local-quality capability. Its phone and
button paths use the same resolution/JPEG-quality policy; High phone photos may
therefore be larger than before. Actual JPEG dimensions depend on the supported
camera outputs, and High/Maximum can coincide. A missing capability appears in
the camera card instead of silently capturing with a different size policy.

Pending camera defaults block new phone photos; pending recording defaults block
new phone videos. Each remains independent of the other, audio, and Stop. No
capture is queued for later when settings or the connection are unavailable.
Video resolution/FPS and duration are two settings commands: a physical press
during application can use old or partially updated values until both confirm.

**Audio:** tap **Audio**, then Pause/Resume or Stop. The glasses microphone supplies
16 kHz mono 16-bit PCM over BLE. The ordered writer rotates five-minute WAV files
without restarting the microphone. Library shows one session; completed parts
are published into phone media. There is no 30-minute timer or total-session WAV
ceiling. Storage is about 115 MB/hour. The phone microphone is not used.

| Media | Default shared location |
| --- | --- |
| Photos | `Pictures/Glasses Lab/` |
| Video | `Movies/Glasses Lab/` |
| Audio parts | `Music/Glasses Lab/` |

Photos/video appear in compatible gallery apps; audio is normally found in
audio/file apps. Library supports the shared URIs and retained legacy private
files. Audio playback/seek spans ordered parts; export produces a streaming ZIP
with original segments and gap metadata.

Publication first uses a pending MediaStore row, syncs/re-reads the bytes, verifies
SHA-256/length, and durably records the public mapping. Only verified private
staging copies can then be reclaimed. The phone submits exact generation-matched
glasses receipts only after its copy is verified. The fork checks each receipt;
it does not globally empty trash or change stock-gallery retention. A failed or
ambiguous operation preserves the source. Ordinary older phone media is never
automatically deleted to make room.

The private audio index and publication journal remain important even though
media is shared. Back up both shared media and private state before experiments.
Older 0.3 builds expect private files and cannot automatically follow 0.4's public
URI mappings; see [rollback compatibility](../README.personal-dev.md#roll-back-to-a-known-working-custom-pair).

## Storage allowance and interruptions

**Settings → Recording allowance (GB)** sets an optional audio/video budget;
blank means available space above the reserve. **Phone space to keep free (GB)**
defaults to 1 GiB (about 1.074 GB in the decimal UI), with a 0.068 GB UI minimum.
Published, private, partial, and export recording bytes count. Photos are outside
the optional recording budget but still obey actual free-space reserve checks.

Offload must have room for receiving and publishing, including temporary copies.
It preflights the complete operation and continues checking capacity while writing.
When space is low, recording/transfer stops or is deferred, completed media is
retained, and the source stays on the glasses. Free space or change the allowance,
then retry. A deleted gallery item is respected rather than silently recreated.

Speech gating is disabled during audio capture and restored afterward. A transient
BLE loss saves the current segment and can resume the same authorized session
after reconnect with an indexed gap. Calls or loss of microphone input pause
audio and require Resume. On this firmware, media playback can interrupt the
glasses microphone; finish capture before listening.

The service owns BLE and recording independently of screens, with a renewable
wake lock during active work and notification Stop. Closing an Activity does not
end a recording. Explicit Stop/force-stop is respected. Process restart recovers
files and connection intent without automatically starting capture. Video request
identity and its profile survive process loss so reconnect can stop an uncertain
capture; that recording's selected limit remains active on the glasses. Legacy
recovery entries without a profile report an unknown limit.

## Phone playback, calls, and quick controls

Enable **Media audio** and **Calls** for the glasses in Android Bluetooth settings.
These A2DP/HFP routes are separate from BLE readiness. Expand **Audio & calls**
for status, media/call volume, and **Play a quiet test tone**. Normal calls use the
Phone app or glasses call gesture. Playback uses Android's selected media route.

The connection notification offers eligible Photo/Video/Audio actions while idle and appropriate
Pause/Resume/Stop controls during work. Add **Glasses photo**, **Glasses video**,
and **Glasses audio** from Android's Quick Settings tile editor. Video/audio tiles
become Stop controls for their matching recording. Long-press the launcher icon
for capture shortcuts. Controls require a ready connection and do not queue a
capture to happen unexpectedly after reconnect.

The audio tile has started a 93.2-second recording and stopped it while locked
on this Samsung with USB attached. Photo/video tiles subsequently passed locked-
screen capture and automatic offload on ASG `57534392-dev`. Unplugged/Doze use and
subjective hearing remain untested; Ross skipped the latest call test.
They are not physical screen-off volume/power-key mappings. A locked screen's
notification/tile policy and Android foreground-service restrictions still apply.
The setup UI reports notification, association, and battery-optimization status.

## Where to customize

- `MainActivity.kt`, `LibraryActivity.kt`, `RecordingSettingsActivity.kt`, `LabUi.kt`:
  capture/library/settings UX. Library creates 100 media cards initially, with Show more.
- `CompanionService.kt`, `CompanionActivity.kt`: connection/capture ownership and binding.
- `GlassesController.kt`, `RecordingController.kt`: commands, automatic offload,
  capture presets, microphone lifecycle, and recovery.
- `PhoneMediaPublisher.kt`, `MediaPublicationStore.kt`: durable MediaStore publication,
  verification, retry journal, and guarded private-copy reclamation.
- `SegmentedAudioWriter.kt`, `WavWriter.kt`, `PcmRecording.kt`, `RecordingRepository.kt`:
  ordered audio segments, checkpoints, index, and recovery.
- `RecordingStoragePolicy.kt`, `RecordingSettings.kt`: capacity accounting and limits.
- `AudioSessionActivity.kt`, `AudioSessionExporter.kt`, `PlaybackActivity.kt`:
  shared/private playback and session export.
- `GlassesMediaConnection.kt`, `MediaConnectionState.kt`, `VideoDownloader.kt`:
  retained approved Wi-Fi ownership, bounded lease, renewal, and disconnect.
- `MediaOffloadClient.kt`, `VideoFileTransfer.kt`, `RemoteMediaCatalog.kt`:
  authenticated transfer, scratch recovery, source identity, and auxiliary totals.
- `MediaPlaybackOwners.kt`: owner-safe playback exclusion for periodic transfers.
- `QuickControls.kt`, `CaptureTileService.kt`: system capture controls.
- `BluetoothAudio.kt`: media/call profile observation, volume, and test tone.
- `LocalPhotoReceiver.kt`: legacy loopback photo relay and related tests.
- `sdk/build.gradle`, `lc3/build.gradle`: shared native SDK and codec integration.

The host compiles native sources from `mobile/modules/bluetooth-sdk/android/`,
excluding Expo adapters and the SDK LAN photo server. React Native `mobile/`
screens do not affect this app. Third-party dependencies and vendor firmware
remain part of the system. Keep dependency versions aligned with the shared SDK.

The manifest flag `com.mentra.bluetoothsdk.host_manages_connection_lifecycle=true`
lets our service own foreground lifetime and explicit reconnect intent; other SDK
hosts retain default behavior. SDK analytics are disabled. The full Expo SDK
compile check remains blocked by missing Bun; our standalone build compiles the
shared Android SDK sources but does not prove the separate Expo host works.

## Validation

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
Photo/video tile capture and automatic offload passed on the
preceding pair with the earlier five-minute video limit as described above.
Direct-to-phone long-video recording has **not been prototyped**.
Earlier endurance evidence remains in `local/asg-dev/extended-audio-validation/`
and must not be attributed to a later APK.


The immutable earlier checkpoints include
`local/asg-dev/checkpoints/20261027-114059-phone-media/` and
`local/asg-dev/checkpoints/20261027-verified-phone-media/`. The preceding keep-ready
pair is archived at `local/asg-dev/checkpoints/20261027-persistent-media-wifi/`;
the current phone deployment checkpoint is
`local/asg-dev/checkpoints/20261027-183903-capture-defaults-delivery-fix/`.
Inspect their recorded contents and do not overwrite earlier backups.

Earlier 0.3 endurance saved **45:22.25** in ten valid segments (87,112,440 bytes)
with no indexed gap and a released wake lock on Stop. USB remained connected and
Android idle stayed ACTIVE. Later lifecycle fixes followed that run. Earlier
user-confirmed two-way calls and video-with-sound evidence remain useful, but do
not establish new-pair call interruption or audible playback quality.

Track outstanding gates in the
[implementation plan](../notes/superpowers/plans/2026-10-26-glasses-lab-independent-companion.md).
