---
status: active
owner: Ross
---

# Glasses Lab independence and extended recording implementation plan

**Goal:** Use our phone companion and ASG builds for daily operation, remove the
Mentra App from the phone, support audio sessions limited by configured phone
storage rather than duration, and then deliver long video recording with sound.

**Spec source of truth:** [independent companion design](../specs/2026-10-26-glasses-lab-independent-companion-design.md).

**Architecture:** A native Android foreground service owns BLE and recording.
Phone storage consists of bounded media segments and a durable session index.
Video first evaluates the existing ASG streaming publishers with a local phone
receiver; reliable local capture/offload remains a fallback.

**Tech stack:** Existing Kotlin companion and native Bluetooth SDK, Java ASG,
coroutines, SQLite metadata, existing LC3 decoding, and existing ASG encoded
video/audio streaming. Receiver dependencies are selected by the video prototype.

Implementation began October 26, 2026. On October 27 the official phone package
`com.mentra.mentra` was uninstalled; Glasses Lab and our ASG are the baseline.
Stock ASG/recovery remain installed but disabled. Firmware remains unchanged.
The current 0.4 implementation adds shared phone-media storage, automatic local
Wi-Fi offload, authenticated per-file reclamation, a simpler capture/library UI,
and notification/Quick Settings/launcher controls. Video still uses five-minute
clips; extended local streaming remains a later phase. Do not interpret code
checkboxes as passed hardware acceptance gates.

### October 27 migration, hardware evidence, and keep-ready follow-up

- Official Mentra App uninstall is complete. Glasses Lab and our ASG remain the
  baseline; vendor firmware and installed-but-disabled stock/recovery packages
  are preserved. The pre-migration source/APK/key and private-data checkpoints
  remain unchanged.
- The initial 15-file phone migration passed SHA-256 checks against the backup
  and after restart: three photos, twelve WAVs, 92,721,981 bytes. Both indexed
  audio sessions survived, including the 45-minute recording. Canonical segment
  lookup fixes Android `filesDir` aliases; finalization notifications coalesce
  so the final audio segment is not lost from a publication pass.
- Hardware offload and reclamation passed for **eight files / 72,873,757 bytes**.
  All eight receipt journals are `complete`, map to a verified phone publication,
  and have absent source and quarantine payloads. **23 public files** passed
  checksum verification; glasses media usage fell **70,908 KiB → 532 KiB**.
- Six IMU sidecars remained after the first offload but were later lost to the
  inherited startup cleanup described below. The first migration published two old
  video thumbnails as photos. Future manifests classify these as auxiliary;
  existing phone copies are retained. Explicit auxiliary file/byte totals describe
  the whole gallery and are not summed once per page. Partial/active or ambiguous
  files remain protected by persistence markers and verified-receipt journals.
- Companion association reports Ready; Android battery access is Unrestricted.
  A2DP software checks played about eight seconds of video and opened/played the
  long WAV session. Ross was not wearing the glasses; audible quality is pending.
- Audio tile capture passed for **93.2 seconds / 2,982,400 PCM bytes**, with
  **1,490,383 nonzero samples**, peak **12,895**, FINISHED state, public publication,
  and matching checksum. Stop worked while locked. Screen off with USB attached
  is not unplugged/Doze evidence. The next snapshot has **24 verified public
  files / 168,578,182 bytes**, three FINISHED audio sessions, and no completed
  private capture files.
- Evidence is under `local/asg-dev/media-validation/`: `20261027-123057/`,
  `20261027-124001-355259-glasses-receipts/`, and `20261027-125020/`. Initial
  migration/restart evidence remains in `20261027-115828/` and `20261027-120039/`.
- Earlier migration-pair tests passed 62 companion JVM, 16 selected Android, and
  82 focused ASG cases (one Windows symlink skip). Earlier phone hash:
  `98BF80042A06E20879C25963FFAA65EE7FB54EEC44DD83FA0E05B190424F13D0`.
  Earlier ASG `57529418-dev` hash:
  `B801D6626EA3C5970612AE9B0DCDD7255F6862223905E7D1A725FB8CD1FDDF26`.
  These are historical evidence, not the latest keep-ready pair's hashes.

Ross explicitly chose **Keep media Wi-Fi ready**. The follow-up design retains
one approved local Network and session token between passes, using a 180-second
ASG idle lease renewed every 60 seconds. The phone now holds a bounded 180-second wake lock,
renewed every 60 seconds while retaining the approved connection; hardware idle
and power measurements remain outstanding. Only explicit
**Connect media Wi-Fi** in the visible, unlocked app may prompt; periodic sync
must not produce another popup every five minutes. After disconnect/expiry,
reconnection requires the foreground app. Pending captures remain safe.

This mode consumes phone and glasses battery. Local glasses Wi-Fi has no internet;
internet use can consume mobile data. Transfer connections use the selected
network without binding the whole app. Automatic sync defers during playback;
owner-safe playback tokens cover both players and continuous audio handoffs.

- [x] Implement verified MediaStore publication, automatic exact-source offload,
  retained-source recovery, and canonical committed-audio identity.
- [x] Validate real library migration and the eight per-file reclamation receipts.
- [x] Validate one locked-screen audio tile start/stop and public publication.
- [x] Establish Companion Device Manager association and Unrestricted battery access.
**Latest artifacts and acceptance — October 27, 2026:**

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

The pre-update **24 files / 168,578,182 bytes** and private databases are backed
up under `local/asg-dev/checkpoints/20261027-before-media-connection/`, referencing
the earlier `98BF…` / `B801…` source/APK pair. The current checkpoint destination is
`local/asg-dev/checkpoints/20261027-persistent-media-wifi/`; inspect its manifest
for verified contents and artifact hashes. Keep every older checkpoint immutable.

- [x] Validate approved-network reuse, eight minutes of locked-screen renewal,
  and photo/video tile capture with automatic verified offload and no new prompt
  on phone `993B…` / ASG `85A7…`.
- [x] Build/sign final ASG `57536376-dev` with 104 passing tests and one host skip.
- [x] Deploy final ASG with the guarded 60-second trial: all 13 files (three
  retained auxiliaries and ten receipt journals) survived restart with identical
  sizes and SHA-256. The fork remains active as home.
- [ ] Validate Wi-Fi expiry/disconnect and explicit foreground reconnect.
- [ ] Test unplugged stationary idle/Doze and measure power on both devices.
- [ ] Confirm heard playback/volume quality and the user-skipped call/interruption
  checks; test real low-storage recovery.
Current checkpoint destination: `20261027-persistent-media-wifi/`. Inspect its
manifest for verified contents; do not overwrite any earlier checkpoint.

Final deployment evidence:
`local/asg-dev/media-validation/20261027-134113-startup-guard/summary.json`
reports `retentionVerified: true` and no changed or missing files.

Evidence: `local/asg-dev/media-validation/20261027-132845/summary.json`,
`publications.json`, and `tile-video-validation.json`.

Long video remains **five-minute local capture with sound**. Direct-to-phone
long-video receiving/segmentation has not been prototyped; the phases below remain
future work. The full Expo SDK integration compile remains blocked by missing
Bun; standalone companion builds compile the shared native SDK sources.

### October 26 audio baseline evidence

- Before-change source/APK/key checkpoint:
  `local/asg-dev/checkpoints/20261026-121139-before-extended-audio/`.
  Includes custom phone app data and the official phone app's base/split APKs.
  Ross confirmed that no official-app media needs saving.
- Custom ASG `57450965-dev` passed the guarded 60-second install observation and
  was left active using `--keep-running` on October 26. Stock ASG/recovery remained installed and
  disabled. A normal Android reboot launched the fork automatically in 34.7
  seconds; firmware, green verified boot, and locked bootloader were unchanged.
  Evidence: `local/asg-dev/extended-audio-validation/glasses-reboot.json`.
- Initial `0.3.0-dev` endurance APK SHA-256:
  `1C35A1CF7F41FAE3551C40A6357C022E73B28CAE1C99D6EAB17D7AFD76F634DA`.
  Tested on Samsung SM-A146U / Android 15. Signature verification passed.
- 49 JVM tests passed, including 45 minutes of generated PCM. Eight Android tests
  passed on the final phone build: isolated database/recovery cases, main-screen
  binding, manifest ownership, and delayed interruption/pause transitions.
  The launch test covers a pre-binding volume-control crash found and fixed on
  the device before recording tests.
- Live short test: 115.85 seconds, Pause, Resume, then 33 seconds with Home/screen
  off. Both 16 kHz mono WAVs contain non-silent samples; SQLite records FINISHED
  and the 65.135-second pause gap. Wake lock released on pause and Stop.
  Evidence: `local/asg-dev/extended-audio-validation/short-test/`.
- Live endurance: **45:22.25 captured**, 87,112,440 file bytes, ten finalized WAVs
  (nine exactly five minutes). Every header/sample count validated and every file
  had non-silent PCM. Session FINISHED, no indexed gaps, no partials, and the
  capture wake lock released on Stop. All monitoring samples showed screen off
  and the capture lock held. USB was attached; Android idle state remained ACTIVE,
  so this does not establish unplugged idle/Doze behavior. This run preceded the
  final SDK-service opt-out and interruption fixes; repeat shorter lifecycle
  checks on the final APK and retain that distinction for future endurance runs.
  Evidence: `local/asg-dev/extended-audio-validation/screen-off-progress.jsonl`
  and `audio-1793038875734-353aaa98/validation.json` in the same directory.
- October 26 phone APK installed with existing recordings retained:
  `06ABD3C1D6BA3C55F37A68D431E1488A8F046026F6502C6A14809CE092091821`.
  Final ASG APK built with 27 focused tests and signature verification:
  `F0913A8F2BA2EF3BF67C2300DEE8A69D85AC7C8ADE325D5CF7442582B6B4508D`.
  Its guarded deployment and normal-reboot check completed successfully.
- APK pair/source/signing-key checkpoint after that deployment:
  `local/asg-dev/checkpoints/20261026-141507-independent-audio/`.
  The subsequent foreground-reopen fix restores persisted connection intent from
  a visible screen, respecting explicit Disconnect and never starting capture.
  It passed the 49 JVM tests, signature verification, and all eight Android tests
  after installation. Opening the app then started only our foreground service
  (notification 41) and reached "Glasses ready" automatically. All ten endurance
  files remain, no partials exist, and no capture wake lock was acquired.
  Association/playback/call checks still require the unlocked phone.
  Evidence: `extended-audio-validation/device-tests-reopen.log`
  and `foreground-reopen-build.log` under `local/asg-dev/`.
- October 26 APK/source/key checkpoint:
  `local/asg-dev/checkpoints/20261026-142927-foreground-reopen/`.
  Reopen observations are in `extended-audio-validation/reopen-service.txt` and
  `reopen-events.txt` under `local/asg-dev/`. The phone remained locked, so these
  observations do not establish human playback, call quality, or association.

### Implementation decisions

- Use Android `SQLiteOpenHelper` with WAL and transactions for the small durable
  index, rather than adding Room's annotation-processing dependency to this
  standalone host. Repository device tests exercise actual Android SQLite. This
  is schema version 1; future upgrades must preserve sessions and media.
- Preserve legacy flat WAV/MP4 identities and pending video IDs. Version 0.4 maps
  completed media to verified MediaStore URIs while retaining logical source
  paths in the journal/index. Legacy files appear alongside indexed sessions;
  private staging is reclaimed only after its public copy is verified.
- Phone-app removal completed October 27 at Ross's renewed request. Multi-hour
  endurance and video transport selection remain outstanding. No vendor firmware
  or partitions were changed.
- The SDK's existing service and Bluetooth-toggle auto-reconnect needed a native
  host opt-out. Glasses Lab sets `host_manages_connection_lifecycle` in its
  manifest and owns both behaviors. Other hosts retain the SDK default. The
  repository's full Expo SDK compile check was attempted and is blocked by
  missing Bun (`local/asg-dev/mobile-sdk-compile.log`); the custom host compiles
  the SDK sources and has a device manifest/lifecycle test.
- Fork ASG builds now omit Sentry configuration/source uploads and reject remote
  crash-report initialization by package identity. Local crash logs remain.
  The existing manifest already disabled automatic Sentry initialization.

### Video receiver preflight (implementation still gated)

The checked-out StreamPackLite publisher uses `io.github.thibaultbee:srtdroid:1.7.0`.
Its cached AAR contains ARM64/ARMv7 native libraries and supports listener/receive
operations, but inspection did not find a public API for adopting an Android
`Network`-bound UDP socket. Prove local-hotspot routing with cellular still enabled
before selecting it for the receiver; do not bind the entire phone process.
[Upstream srtdroid](https://github.com/ThibaultBee/srtdroid) documents the Apache-2.0
wrapper and newer 1.9.5 artifacts. Its SRT/OpenSSL dependencies and actual socket
binding API need review before integration. No receiver dependency has been added,
and no video stream has been started during the audio test.

## File map

Paths are relative to the repository root. New class names are proposed.

| Path | Action | Responsibility |
|---|---|---|
| `companion_android/app/src/main/java/dev/ross/glasses/CompanionService.kt` | Create | Service ownership, commands, foreground state, active-work wake lock |
| `.../CompanionAssociation.kt` | Create | Companion association/presence and background reconnect |
| `.../RecordingService.kt`, `LabApplication.kt`, `GlassesController.kt` | Refactor | Replace notification-only ownership; UI/service facade; SDK cleanup |
| `.../RecordingController.kt`, `RecordingSession.kt`, `BluetoothAudio.kt` | Modify | Session transitions, calls, reconnection, observer disposal |
| `.../RecordingRepository.kt`, `RecordingStoragePolicy.kt` | Create | Transactional index, legacy migration, quota/free-space checks |
| `.../SegmentedAudioWriter.kt`, `PcmRecording.kt`, `WavWriter.kt` | Create/modify | Ordered segments, bounded PCM, durable finalization and recovery |
| `.../MainActivity.kt`, `LibraryActivity.kt`, `PlaybackActivity.kt` | Modify | Service state, session library, playback/export and interruption display |
| `.../RecordingSettingsActivity.kt` | Create | Recording budget, free-space reserve, companion status |
| `companion_android/app/src/main/AndroidManifest.xml`, `app/build.gradle` | Modify | Service/association declarations, dependencies, instrumentation setup |
| `.../LocalVideoReceiver.kt`, `VideoRecordingController.kt` | Create later | Local stream ingest, muxing, segmented video persistence |
| `.../VideoDownloader.kt`, `VideoFileTransfer.kt`, `VideoGallery.kt` | Modify later | Resumable gallery fallback and removal of fixed 1 GiB import cap |
| `mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/` | Modify only as needed | Typed capability/control additions and stream lifecycle integration |
| `asg_client/.../service/core/handlers/StreamCommandHandler.java` | Modify later | Local-network readiness and versioned stream capabilities |
| `asg_client/.../io/network/core/BaseNetworkManager.java` and network interfaces/managers | Modify later | Stream-scoped hotspot lifetime |
| `asg_client/.../io/streaming/services/` | Modify later | Local publisher lifecycle, receiver progress, optional bounded spool |
| `asg_client/.../io/server/services/AsgCameraServer.java`, `GalleryTrashManager.java` | Modify if needed | Explicit verified-capture purge for our fork; preserve gallery defaults |
| `asg_client/.../camera/lifecycle/VideoRecordingSession.java` | Modify only for capture/offload fallback | Continuous encoder/file rotation if direct streaming is unsuitable |
| Corresponding unit and Android instrumentation test directories | Create/modify | Failure paths, lifecycle, storage and protocol acceptance |
| `README.personal-dev.md`, `companion_android/README.md`, relevant ASG docs | Update during implementation | Daily workflow, measured limits, rollback, capabilities |

`.../` in companion rows expands to `companion_android/app/src/main/java/dev/ross/glasses/`.
`asg_client/.../` expands to `asg_client/app/src/main/java/com/mentra/asg_client/`.

## Phase 0: Establish a recoverable independent baseline

- [x] Inventory the actual phone/glasses packages and versions at implementation
  time. Distinguish the final locally built companion from the previously installed
  build; do not assume the deferred download fixes are installed.
- [x] Preserve the existing working tree and archive the tested APK pair, source
  revision/diff, hashes, signing identity, and host restoration state. Keep the
  original stock ASG backup and the current recovery package intact.
- [x] Export wanted Mentra App recordings/photos/settings before removal. Save its
  version and reinstallable base/split APK set where available; APK copies are
  not a backup of private app data. Verify exported media opens independently.
- [x] Install the intended custom APK pair through the established serial-checked
  tools and a short rollback trial. Then use the validated keep-running workflow.
- [ ] Isolate the official phone app from BLE using a reversible stop/disable
  during migration. Preserve the existing Android media/call Bluetooth bond and
  establish our own app association where needed.
- [ ] Verify our ASG starts after a glasses cold boot and package update; inspect
  the current boot receiver/bootstrap behavior before changing it. Confirm stock
  ASG/recovery remain installed but inactive and maintenance guards remain active.
- [ ] Audit mandatory network dependencies on both sides. Ensure core functions
  work without Mentra services; disable fork telemetry/remote configuration where
  applicable instead of depending on a server failure to keep it quiet.
- [ ] Capture the baseline matrix: pairing, reconnect, battery/version, photo,
  existing audio, queued video, button events, two-way call, media playback, and
  media/call volume. Mark untested behavior explicitly.

**Gate:** Our app can operate the glasses independently and a tested stock/custom
rollback exists. No bootloader, MTK, BES, partition, or firmware update changes.

## Phase 1: Make the companion reliable with the screen off

- [ ] Move SDK/controller/call-observer ownership into one started/bound
  `CompanionService`. Expose observable immutable state and serialized commands;
  keep Activity recreation and library navigation out of the recording lifetime.
- [x] Preserve the `connectedDevice` foreground-service type and required runtime
  permissions. Start from a visible user action or supported companion entry
  point; handle permission/foreground-start failures before enabling capture.
  Audit the merged manifest; do not add phone microphone capture.
- [x] Add CompanionDeviceManager association/presence with API-appropriate
  fallbacks. Reuse the existing SDK connection and its stream keep-alive handling;
  avoid a second GATT session or duplicate heartbeat producer.
- [x] Persist the selected glasses and user intent to stay connected. Reconnect
  with backoff and cancellation after range loss/Bluetooth toggles, without busy
  polling. Explicit Disconnect cancels reconnect. Phone reboot restores connection
  only through permitted startup/unlock paths, without restarting capture.
- [x] Remove recording termination from ordinary task removal. Keep notification
  Stop available on the lock screen. Implement explicit lifecycle/resource cleanup,
  including listener unregistering and closing Bluetooth profile proxies.
- [x] Replace the one-shot 35-minute wake lock with bounded renewal tied to healthy
  active work. Release on stop, pause, loss of source, errors, and shutdown. Do not
  hold a recording wake lock just to maintain an idle companion connection.
- [x] Handle unexpected process restart by reconciling disk/session state. Respect
  Android force-stop/task-manager Stop; recovery must not rely on `onDestroy` or
  automatically resume recording after the user stopped the app.
- [x] Add setup/status UI for notification/Bluetooth/association permissions and
  any Samsung battery configuration demonstrated necessary in testing.

**Gate:** Lock screen, Home, screen rotation, UI navigation, and removing the UI
task do not terminate an active short recording. Explicit Stop does. Reconnect
and stop controls work independently of visible screens.

## Phase 2: Deliver storage-limited extended audio

### Persistence and policy

- [ ] Add a transactional session/segment index with schema migration and bounded
  queries. Record format, sample counts, segment order, timestamps, gaps, state,
  bytes, device identity, and recovery outcome. Import existing library files and
  pending video IDs without losing media or reissuing captures.
- [x] Implement optional total recording budget plus configurable free-space
  reserve. Default budget is unset; proposed reserve is 1 GiB. Validate settings
  and retain enough headroom to finalize files and persist metadata.
- [x] Count current and partial recordings and temporary recording/export work.
  Check both budget and current filesystem free space throughout recording.
  Handle another app filling storage and real ENOSPC, not just a startup estimate.
- [x] On quota/full-disk stop, save what can be finalized, report the reason, and
  release mic/locks. Do not delete existing user media automatically.

### Recording engine

- [x] Replace the 30-minute controller timer and total WAV byte bound together.
  Keep valid per-segment/container bounds and use 64-bit session counters.
- [x] Rotate five-minute PCM/WAV files in one ordered writer without restarting
  the microphone. Bound the queue and preserve every sample accepted by that
  writer across file boundaries; detect/report write stalls and known source gaps.
- [ ] Add periodic checkpoints, sync/finalize/rename/index ordering, and idempotent
  recovery of partial files and orphaned completed segments. Exercise crashes at
  every persistence transition; never overwrite an unrelated existing file.
- [x] Maintain voice-activity gating disabled during capture and restore prior
  settings afterward. Keep the glasses microphone selected; never fall back to
  the phone microphone without an explicitly designed feature.
- [x] Implement states for recording, waiting for glasses, paused for call,
  finalizing, stopped, and failed. Preserve valid segments through disconnects;
  record gaps. Resume the same user-started session on transient reconnect when
  still authorized, but do not silently resume after call, explicit Stop, or reboot.
- [ ] Preserve calls/media/volume behavior. A call pauses/finalizes capture without
  competing for its route. Detect microphone interruption during playback and
  report it; do not claim simultaneous recording/playback before testing it.

### User experience

- [x] Show one session with captured duration, elapsed time/gaps, size, storage
  allowance, and Stop/Pause/Resume. Keep notification content current with bounded
  update frequency and no dependency on a visible Activity.
- [x] Implement sequential segment playback/seek and explicit delete/export.
  Preserve long-session export as a segment bundle when a single conventional
  WAV would exceed its format limit. Do not build a whole recording in RAM.
- [ ] Update the recording settings and personal workflow documentation. Keep
  optional AAC/Opus compression out of the initial acceptance gate.

**Gate:** There is no programmed session-duration ceiling. Recording stops cleanly
at configured storage boundaries and survives normal screen-off use; the UI
presents a coherent session even though storage is segmented.

## Phase 3: Validate audio and remove the official phone app

- [ ] Unit-test exact sample ordering across boundaries, quota arithmetic,
  changing free space, short writes/full disk, format bounds, concurrent commands,
  gap accounting, interrupted finalization, and recovery without duplicate files.
- [ ] Add Android service/instrumentation coverage for user-start/stop, binding,
  permissions, wake-lock release, task removal, and reconnect state transitions.
- [ ] On the actual Samsung phone, test recording with screen off and unplugged:
  short run, at least 45 minutes (crosses both old caps), then a multi-hour run.
  Include stationary idle/Doze and configured Samsung battery behavior.
- [ ] Run an eight-hour phone endurance/storage test with generated input and aim
  for an eight-hour hardware recording where glasses power and temperature allow.
  Report synthetic and physical results separately, including actual battery limit.
- [ ] Exercise incoming/outgoing calls, media playback, Bluetooth off/on, range
  loss, glasses reboot, process kill, task-manager Stop, phone reboot, and low
  storage. Verify saved media, truthful gaps, no orphaned recording, and recovery.
- [x] Reconfirm wanted official-app media is exported. Uninstall only the verified
  official phone package (`com.mentra.mentra` is the currently documented identity).
  Do not remove our app, clear its data, or uninstall stock glasses packages.
- [ ] Repeat pairing/reconnect, phone/glasses cold boots, photo, audio, calls,
  playback, volumes, and button behavior with the official phone app absent and
  Mentra cloud unavailable. Confirm our own association survives the migration.
- [ ] Publish a tested custom APK pair and document normal updates with the same
  signing identity, preserved recordings, offline restore, and rollback steps.

**Gate:** The Mentra App is absent from the phone; our app is the daily companion.
Extended audio has hardware evidence and documented physical limits. Video work
does not delay this milestone once existing basic video behavior is accounted for.

## Phase 4: Prove direct video recording on the phone

### Proposed receiver direction (not implemented)

The next experiment is a local phone **SRT listener → recoverable 30–60-second
MPEG-TS files → MediaExtractor/MediaMuxer MP4 → existing verified MediaStore
publisher**. This is a design direction only; no receiver or long-video prototype
was implemented in this turn. `srtdroid` 1.7 prebuilt artifacts are a possible
starting point, subject to license, native-library/ABI, and 16 KiB page-size audits.

Before a prototype, address the ASG stream gate that currently requires station
Wi-Fi rather than permitting the local hotspot, timestamp overflow around 3.95
days of uptime, and missing B-frame/DTS handling. Redact stream URL secrets and
route each socket through the selected Android Network without binding the whole
process. Bound the queue to **two pending TS segments**, charge every staging copy
to the shared storage policy, and retain each TS until its MP4 is verified and
published. Report transport loss and gaps explicitly; a stream acknowledgement
alone does not prove recorded footage is safe.

### Transport and network prototype

- [ ] Finish the deferred short-video Wi-Fi download/playback test to establish
  the phone/glasses local network baseline. Retain existing media throughout.
- [ ] Prototype a phone receiver for the existing SRT publisher; compare RTMP if
  receiver availability, packaging, or recovery makes SRT unsuitable. Record the
  selected library/version/license and native ABI implications before integration.
- [ ] Prove glasses-to-phone reachability on the glasses local-only hotspot,
  including no upstream Wi-Fi or internet, fresh credentials, network changes,
  phone screen off, and continued cellular availability. Do not bind the whole
  phone process to the glasses network.
- [ ] Add a versioned local-stream capability and session identity where current
  APIs are insufficient. Check negotiated capabilities rather than inferring
  support from a successful build or a version suffix.
- [ ] Change stream startup readiness to support the validated local topology
  while rejecting unusable destinations. Add authenticated/session-scoped endpoint
  setup; do not expose an unrestricted recorder on every phone interface.
- [ ] Introduce a scoped hotspot lease for active local streaming. Lease progress
  must reflect media delivery/receiver health; BLE command keep-alives alone do
  not prove that the phone is saving footage. Preserve ordinary idle cleanup.

### Recording prototype

- [ ] Receive encoded H.264/AAC (or the measured supported combination) and save
  without phone video re-encoding. Make preview optional. Preserve codec metadata,
  keyframes, timestamps, and audio/video alignment.
- [ ] Select and demonstrate recoverable staging: supported fragmented MP4 or
  MPEG-TS with later remux. Rotate approximately one-minute segments at keyframes,
  represented as one session. Measure startup/finalization loss; do not assume
  ordinary MP4 is usable after a crash before its final metadata is written.
- [ ] Apply the shared storage policy and bounded input queues. Phone disk full,
  writer failure, or stalled receiver stops capture and finalizes valid segments.
  Keep receiver-progress watchdogs effective if the UI or phone process disappears.
- [ ] Coordinate camera/microphone ownership, local button Stop, call priority,
  recording indicators, stream retries, and cleanup on every exit. Do not start
  a separate BLE audio recording to reconstruct video sound.
- [ ] Measure 720p first, then compare bitrate/FPS choices. Record sustained network
  throughput, lost/dropped frames, A/V drift, phone/glasses temperature, battery
  drain, memory growth, and physical glass/phone storage usage.

### Transport decision gate

- [ ] Compare short, 30-minute, and multi-hour screen-off runs, plus deliberate
  Wi-Fi/BLE loss and calls. Playback must show intelligible sound, correct order,
  and no unexplained gaps. Target A/V offset within 100 ms without growing drift;
  record measured results and any revised acceptance threshold explicitly.
- [ ] Demonstrate no accumulating gallery/trash footprint in direct-stream mode,
  bounded temporary files, and recovery of finalized segments after a phone kill.
  Distinguish unsaved network loss from a recoverable local file.
- [ ] Choose: direct streaming if measurements meet reliability/power needs;
  streaming plus a bounded spool if brief outages need protection; or continuous
  local capture/offload if network/publisher limitations make streaming unsuitable.
  Record the decision here before building the larger fallback pipeline.

**Gate:** We have an actual Android receiver and measured hardware behavior, not
just a successful stream command or cloud-player demonstration. No assumption
that Wi-Fi streaming is cooler or uses less battery than local recording.

## Phase 5: Make long video dependable and reclaim storage correctly

- [ ] Productize the selected video path using the same session library, storage
  settings, recovery index, foreground-service lifecycle, and Stop controls as audio.
- [ ] Complete video segment playback/export. Reserve temporary storage for remux
  or sharing; never require the entire session in memory or duplicate it without
  accounting for space. Leave already-playable segments intact on export failure.
- [ ] Add resumable range transfers, generation/ETag validation, hashes, and a
  durable commit/ack ledger for existing local captures and any spool segments.
  Reuse the Mentra App gallery protocol/design without importing its React Native
  runtime into the companion. Replace the 1 GiB importer ceiling with checked
  64-bit lengths and actual storage policy.

### If a glasses spool is selected

- [ ] Tee the same encoded stream into a separate, bounded spool; do not run a
  second camera encoder by default. Start with a proposed two-minute/256 MiB
  upper bound (whichever is reached first), subject to available reserved-safe
  storage and measurements. Expose the agreed limit in app settings.
- [ ] Identify each segment by session, sequence, generation, size, hash, and
  timestamp range. Make receipt/ack/purge operations idempotent across reconnects.
- [ ] Persist and verify the phone file and index before sending its durable
  receipt. The fork verifies matching segment/receipt identity before reclamation.
  Keep unacknowledged segments; a full spool pauses/stops capture with a reason.
- [ ] Reclaim only our acknowledged spool files, retaining enough ledger metadata
  to handle repeated acknowledgments after the payload is gone. Scope all paths
  to our namespace and reject active/incomplete files and arbitrary paths.
- [ ] Verify that actual free disk space rises after reclamation. Do not send
  spool acknowledgments through the existing seven-day gallery trash path.

### Existing gallery and capture/offload fallback

- [ ] Offer explicit verified-copy cleanup for our local captures, with capability
  negotiation/authentication and generation-matched receipts. Keep legacy gallery
  retention as the default; do not globally reduce its seven-day constant.
- [ ] If continuous local capture is selected, implement encoder-preserving
  rotation and download completed segments during the next segment. Simple
  stop/start recording is only a prototype: measure boundary gaps and require
  an agreed result before calling the session continuous.
- [ ] Keep storage headroom and hardware stop protections. Check average offload
  throughput exceeds generated media bitrate with headroom; bound queue growth
  and stop cleanly when it cannot keep up.
- [ ] Exercise wrong/stale/duplicate receipts, interrupted transfers, changed
  source generations, phone commit failure, and purge failure. No unverified source
  or stock-media deletion is acceptable.

**Gate:** Long video with sound works within configured phone storage, and any
glasses storage use is bounded and accounted for. Loss and interruption behavior
is explicit; there is no hidden week-long accumulation of acknowledged footage.

## Phase 6: Daily workflow and release acceptance

- [ ] Run the complete matrix below on the intended APK pair. Attach logs and
  media/sample checks to a dated validation note; redact device credentials.
- [ ] Update the workflow README for an independent companion: first setup,
  association, storage settings, screen-off use, calls, video mode, recovery,
  exporting media, updating APKs, and restoring a known-good custom build.
- [ ] Archive tested APK pairs with hashes and the matching source/diff and schema
  version. Increment app versions; preserve signing identity and package names.
  Document downgrade limitations before changing on-disk formats.
- [ ] Repeat an update-over-existing-install test with saved sessions. Verify
  recordings, quotas, selected device, and pairing survive the update.
- [ ] Mark unsupported or untested hardware combinations explicitly. The supported
  baseline is this Samsung phone and these glasses/firmware versions, not all Android.

## Acceptance matrix

| Scenario | Required result |
|---|---|
| Phone screen off/unplugged/idle | Continuous received audio and active Stop notification; measured power behavior |
| More than 30/35 minutes | No old timer or wake-lock cutoff |
| Segment rotation | No writer-induced PCM loss/duplication; ordered playable session |
| UI closed/swiped away | Active capture continues; reopening reflects service state |
| Explicit Stop/task-manager Stop | Recording does not secretly restart; next launch recovers available files |
| Call starts during recording | Call route works; recording saved/paused with a visible gap |
| Media playback and volume | Standard Bluetooth audio remains usable; mic conflicts are detected/reported |
| BLE/Wi-Fi interrupted | Bounded reconnect; truthful gaps; completed media preserved |
| Phone/glasses reboot | Custom ASG/companion recover normal connection; no automatic recording start |
| Budget reached or disk full | Recoverable finalization, useful error, no automatic deletion of old recordings |
| Process killed during write/rename/index | Recovery is idempotent; earlier segments remain usable |
| Direct video stream without internet | Local sound/video saved; cellular routing remains available |
| Active stream beyond HTTP idle timeout | Hotspot stays up for real stream work, then closes when no longer owned |
| Video crash/reconnect | Valid earlier segments, marked gap, decodable next segment and checked A/V sync |
| Spool/verified gallery purge | Exact acknowledged source reclaimed; other media untouched; free space increases |
| Mentra App absent/cloud unavailable | Core companion features work without it |
| Custom APK update | Existing media/settings/pairing preserved and schema migration tested |

## Build and verification workflow

Use the [existing personal workflow](../../../README.personal-dev.md) for exact
Windows build/deploy commands and serial selection. During implementation:

- Run `./companion_android/build.ps1 -RunTests` for companion changes, plus targeted
  Android instrumentation and physical tests for lifecycle changes.
- Run `./asg_client/scripts/build-dev.ps1 -RunTests` for ASG changes; its default
  filters cover the development-safety suites. Use `-TestFilter` for the relevant
  additional camera/network/storage JVM tests. Build success does not establish
  hardware behavior.
- If shared Bluetooth SDK sources change, run the repository's
  `scripts/check-android-compile.sh bluetooth-sdk` in a supported shell/environment
  as well as the standalone companion build. Do not use Expo clean/clear prebuild.
- Run host deployment-tool tests if those tools change. Preserve explicit device
  targeting, trial rollback, maintenance guards, and namespaced media.
- Do not repeat unrelated checks when no further changes or failures justify them.

No hardware action is part of creating this plan. Long recordings, calls, and
device restarts belong to scheduled implementation validation with the phone and
glasses available; tests must report observations rather than infer success from
compilation or an acknowledged command.
