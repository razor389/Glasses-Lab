---
status: active
owner: Ross
---

# Glasses Lab recording defaults implementation plan

**Goal:** Add a Recording defaults section whose resolution, FPS, and duration
choices apply to both phone-started and physical-button videos. Preserve those
choices through disconnection, app restart, and ASG restart, and show when the
glasses have confirmed them.

**Architecture:** Durable phone profile → service-owned synchronization → SDK
reconnect cache and acknowledged glasses preferences. Phone capture uses an
explicit immutable snapshot of the same profile; the glasses button uses its
persisted copy.

**Tech stack:** Kotlin native Android UI, SharedPreferences, coroutines, shared
Android Bluetooth SDK, existing Java ASG settings commands, JUnit and Android
instrumentation.

**Spec source of truth:** [Shared video recording defaults](../specs/2026-10-27-glasses-lab-recording-defaults-design.md).

Coordinate with the [camera defaults plan](2026-10-27-glasses-lab-camera-defaults.md).
Create one `CaptureDefaultsSyncController` for both features, with separate
profiles, revisions, confirmations, and retry state. Photo and video application
share lifecycle/transport plumbing; one domain's failure must not block the other.
The camera plan adds an ASG photo-quality extension; video defaults alone retain
the existing ASG protocol described below.

## Baseline and scope

At the planning baseline reviewed October 27, 2026, the companion was `0.4.0-dev`
and its video request was hardcoded to 1280 × 720, 30 FPS, and five minutes.
Settings included storage, keep-ready media Wi-Fi, background association, and
temporary exports. The foreground service owns the connection and recording;
the settings Activity is asynchronously bound to it.

The existing [independent companion plan](2026-10-26-glasses-lab-independent-companion.md)
also covers larger audio, media, and future streaming work. This plan targets the
current local MP4 recording path and preserves that ongoing work. The checkout
contains existing edits and an untracked companion tree; implementation must not
reset, overwrite, or sweep those changes into unrelated commits.

Initial UI choices are 720p/1080p, 5/15/30 FPS, and 3/5/10/15/20 minutes. Initial
profile is 720p/30 FPS/five minutes. The FPS list is based on the repository's
[historical captured-video measurements](../../../asg_client/docs/fps-thermal-test/data/SUMMARY.md),
not the Java validator's upper bound of 60. All six resolution/FPS combinations
still require current-build acceptance. No sound-cue, microphone-mute, upload
retention, unlimited-duration, or streaming control is included.

Implementation was requested after this plan was written. The app, shared SDK,
and synchronization changes are implemented; physical acceptance remains open.

## Implementation evidence (October 27)

- Both settings cards and independent photo/video eligibility are implemented.
  `CaptureDefaultsSyncController` serializes settings commands, confirms exact
  acknowledgements, retries transient failures, and refreshes confirmation after
  readiness replay or ASG session changes. Rapid saves wait for the in-flight
  command and then apply the newest profile.
- Video requests use the selected profile, and ID/profile are persisted together
  for recovery. Active-limit messages use that snapshot; legacy ID-only recovery
  does not invent a time limit. Stop remains independent of synchronization.
- Final companion build and all **88 JVM tests passed**; the instrumentation APK
  compiled and APK signing verification passed. All **five isolated preference
  instrumentation tests passed** on the development phone. Build log:
  `local/asg-dev/capture-defaults-companion-build.log`.
- The standard Expo/public SDK compile script is blocked by missing Bun and the
  absent generated mobile project. The standalone companion compiled shared SDK
  sources. Separate SDK test verification is recorded in the camera plan.
- The initial trial installed both APKs while capture and transfers were idle.
  Initial phone SHA-256:
  `AD3EA2465A3B834E9ACD1B5B2B4B543D7EC104FD2EC30726C5663238EB01C0A7`;
  ASG `57550478-dev` SHA-256:
  `5145EB02D8553F0C1B0C9F4D88EB8C8B147A791726C6B5AE3883FB159EA2817D`.
  The guarded ASG deployment preserved all 13 retained files unchanged.
  Checkpoint: `local/asg-dev/checkpoints/20261027-175239-capture-defaults/`;
  retention evidence:
  `local/asg-dev/media-validation/20261027-175320-startup-guard/summary.json`.
- Settings saved Low photos and 1080p/15 FPS/three-minute videos. Both reached
  Applied to glasses, and the persisted ASG values matched. After force-stopping
  and relaunching the idle phone app, it automatically reconnected and returned
  to Ready to capture with both profiles preserved. This verifies process
  reconstruction and final readiness; earliest replay ordering remains open.
- One phone-triggered 1080p/15 FPS/three-minute video automatically ended at
  **180.472611 seconds**. The file is **1920 × 1080**, with measured average FPS
  **1185750/81203 (about 14.60)** and AAC 44.1 kHz mono audio. Camera state was
  idle afterward. Evidence:
  `local/asg-dev/capture-defaults-evidence/phone-1080p15-auto3min/summary.json`.
- Changing phone defaults to 720p/5 FPS/five minutes during that recording kept
  the persisted active-video snapshot at 1080p/15 FPS/three minutes. However,
  settings application timed out and ASG retained its previous defaults. This
  also occurred during an idle Retry. This trial did not establish successful
  mid-recording application; the delivery fix and successful retest follow.
  Evidence: `local/asg-dev/capture-defaults-evidence/active-video-snapshot.json`.
- The SDK video-defaults sender now requests the glasses wake path, matching the
  photo and duration senders. The settings screen also starts with neutral focus
  so it does not open the storage keyboard automatically. The rebuilt companion
  passed **88 JVM tests** with no failures/skips and APK signature verification.
  Its installed SHA-256 is
  `E16CBCB83C68AF75A751719B3989AF8E06BD23E3EBA943BE9618F686A48AC61C`;
  ASG remains `5145EB02D8553F0C1B0C9F4D88EB8C8B147A791726C6B5AE3883FB159EA2817D`.
  Current checkpoint:
  `local/asg-dev/checkpoints/20261027-183903-capture-defaults-delivery-fix/`.
- The fixed settings screen was checked on device: the keyboard stayed closed
  on entry and opened after explicitly tapping Recording allowance. No storage
  values were changed. Evidence:
  `local/asg-dev/capture-defaults-evidence/settings-focus-check.json`.
- On the fixed phone build, reconnect applied and persisted 720p/5 FPS/five
  minutes. During a phone video using that profile, saving 1080p/15 FPS/three
  minutes reached Applied to glasses and updated the ASG preferences while the
  active-video snapshot stayed at 720p/5 FPS/five minutes. Manual Stop succeeded.
  The completed clip was **1280 × 720**, **77.673578 seconds**, averaging
  **about 5.002 FPS** (`34920000/6981073`), with AAC 44.1 kHz mono audio.
  Evidence: `local/asg-dev/capture-defaults-evidence/fixed-active-video-snapshot.json`
  and `local/asg-dev/capture-defaults-evidence/fixed-phone-720p5-manual-stop/summary.json`.
- A user-confirmed physical-button video at 1080p/15 FPS/three minutes
  automatically ended at **180.239022 seconds**. Its **354,548,852-byte** file is
  **1920 × 1080**, averaging **about 14.60 FPS** (`1183950/81091`), with AAC
  44.1 kHz mono audio. While the camera was active, changing future defaults to
  720p/5 FPS/five minutes was confirmed on ASG; the physical recording still
  stopped at its original three-minute limit. Evidence:
  `local/asg-dev/capture-defaults-evidence/physical-button-1080p15-auto3min/summary.json`
  and `local/asg-dev/capture-defaults-evidence/physical-video-updated-defaults.json`.
- All new test media, including both three-minute videos, completed verified
  phone publication. The final audit verified **35/35 published files /
  980,054,447 bytes**, database integrity, and no private staging files. The
  receipt audit verified **19 receipts / 884,350,022 bytes** with no errors and
  corresponding glasses source/quarantine payloads absent. Evidence:
  `local/asg-dev/media-validation/20261027-190056/summary.json` and
  `local/asg-dev/media-validation/20261027-190135-275522-glasses-receipts/receipt-validation.json`.
- Testing ended idle with initial defaults restored and confirmed on phone and
  glasses: **High photos, 720p/30 FPS/five-minute videos**.
  Installed APK hashes, persisted values, and idle state are recorded in
  `local/asg-dev/capture-defaults-evidence/final-state.json`.
- The other video combinations and longer duration checks,
  same-link ASG restart/replay, offline edits/reconnect, and the remaining matrix
  below are unverified. The camera plan records photo evidence. This plan stays
  active; the observed settings-delivery failure is fixed and passed the above
  reconnect and mid-recording retests.

Checked implementation tasks below record software or test evidence; they do
not mark the separate physical acceptance matrix complete.

## File map

Paths in the first table are relative to
`companion_android/app/src/main/java/dev/ross/glasses/`.

| File | Action | Responsibility |
|---|---|---|
| `VideoRecordingProfile.kt` | Create | Validated profile, allowed choices, defaults, display summary, explicit SDK request conversion. |
| `RecordingSettings.kt` | Modify | Durable profile/schema/revision in a separate preference namespace; retain existing storage and Wi-Fi settings. |
| `CaptureDefaultsSyncController.kt` | Create once with camera work | Testable serialized sync state with independent photo/video revisions and confirmation, device/session guards, and bounded retries. Define the small typed transport interface here. |
| `GlassesController.kt` | Modify | Own synchronizer, hydrate SDK cache before connection, connect readiness/raw events, expose save/retry/status and video-specific start eligibility. |
| `RecordingController.kt` | Modify | Consume an approved profile snapshot, persist it with `active_video`, use active-duration copy, preserve stop/recovery behavior. |
| `RecordingSession.kt` | Modify as needed | Associate immutable active-video metadata with its ID without changing audio transitions. |
| `RecordingSettingsActivity.kt` | Modify | Add controls, local-save feedback, application status, retry, binding-safe observation and draft restoration. |
| `MainActivity.kt` | Modify | Video-specific eligibility and dynamic recording summary/duration copy. |
| `CompanionService.kt` | Modify | Propagate video readiness to system controls; bound settings work lifetime without delaying Stop. |
| `QuickControls.kt`, `CaptureTileService.kt` | Modify | Distinguish video-start readiness from generic capture readiness; keep active Stop available. |

| Repository path | Action | Responsibility |
|---|---|---|
| `mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/MentraBluetoothSdk.kt` | Modify narrowly | Typed cache-only recording-default initialization; retain existing acknowledged setter behavior. |
| `mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/sgcs/MentraLive.kt` | Modify narrowly | Emit raw readiness completion after cached settings replay is queued, including session identity when available. |
| `companion_android/app/build.gradle` | Modify if needed | Coroutines test dependency aligned to the existing coroutine version. |
| `companion_android/app/src/test/java/dev/ross/glasses/` | Add/extend focused tests | Profile, sync races, eligibility, and active-session snapshot behavior. |
| `companion_android/app/src/androidTest/java/dev/ross/glasses/` | Add focused tests | Preferences, cache initialization, settings UI/binding, and process reconstruction. |
| `companion_android/README.md` | Modify after implementation | Explain shared defaults and replace fixed five-minute product descriptions. |

ASG is a reviewed dependency, not a planned source-edit target. Relevant readers
are `ButtonEventSubscriber`, `SettingsCommandHandler`, `AsgSettings`, and
`VideoCommandHandler`. The existing wire commands are sufficient for the bounded
profile choices and the application guarantee in the spec. A new atomic settings
command would be a separate scope decision.

## Phase 1 Define and persist the profile

- [x] Add `VideoRecordingProfile` with width, height, FPS, and maximum minutes.
  Validate resolution as a pair and use the explicit choice sets from the spec.
  Keep the generic SDK's broader numeric range separate from the app's options.
- [x] Add one app-wide desired profile and revision to `RecordingSettings`, in a
  dedicated namespace. Default old installs to 720p/30 FPS/five minutes; preserve
  existing storage/Wi-Fi preferences and recording/media data.
- [x] Commit the entire profile and revision together. Increment the revision only
  after a successful save; report persistence failure without beginning BLE sync.
  Define deterministic recovery for malformed or unsupported stored values.
- [x] Keep profile encoding/validation and request mapping independent of Android
  UI so they can be exercised with JVM tests.
- [x] Define applied confirmation as `(device identity, connection generation,
  revision, profile)`. Do not restore a persisted applied flag as live confirmation
  after process recreation.

**Checks:** all allowed combinations; invalid resolution pairs/FPS/durations;
missing preferences; malformed stored profile; atomic save failure; revisions;
unchanged existing settings; exact mapping into `VideoRecordingRequest`.

## Phase 2 Preserve defaults through SDK initialization and replay

- [ ] Add a documented cache-only SDK method, tentatively
  `cacheVideoRecordingDefaults(defaults, maxRecordingTimeMinutes)`. It updates the
  canonical `button_video_settings`, legacy scalar keys, and duration without BLE,
  hardware side effects, or an application acknowledgement. Do not reuse
  `DeviceStore.apply`, which performs hardware actions.
- [ ] Call it from the companion after creating the SDK and before
  `setDefaultDevice`, `connect`, or `connectDefault` can replay settings. Load the
  durable desired profile first. Calls that do not opt into this method retain
  existing SDK initialization behavior.
- [ ] Reuse the cache-write implementation from the existing acknowledged setters
  where appropriate without changing their success/failure semantics. During
  live updates, serialize cache writes with the profile synchronization worker.
- [ ] In `MentraLive`'s `glasses_ready` branch, emit a raw event after
  `sendUserSettings()` has queued its replay, with `sid` when present. Name it
  explicitly as a readiness/replay event, not a settings-applied event.
- [ ] Consume this signal through the existing `onRawEvent` callback. Consume
  `glasses_session_changed` to invalidate earlier confirmation even when the
  ordinary ready Boolean remains true. Coalesce ordinary readiness and replay
  signals for the same connection episode.

**Checks:** a fresh SDK process replays the saved profile instead of ten minutes;
cache initialization sends no BLE; both canonical and legacy keys agree; same-link
ASG restart schedules fresh acknowledgement; existing SDK hosts have unchanged
default behavior. Exercise the actual cache/replay path with a transport spy or
isolated instrumentation test, not only a fake companion adapter.

## Phase 3 Implement confirmed synchronization in the service

- [ ] Create the synchronization controller with an injected transport, scope,
  and retry scheduler. Expose desired revision, status, last confirmed profile,
  retryable error, and whether the current profile permits a phone video start.
- [ ] Coordinate with photo synchronization in the camera plan. Keep separate
  state per domain, attempt each even if the other fails, and release the shared
  worker during retry delays. Hydrate both caches before connection and reuse one
  readiness-completion event and session-change handler.
- [ ] Scope attempts to the selected device and connection generation. Capture
  both before the first send and check them after every suspension point. Cancel
  or invalidate pending work on disconnect, device switch, and service teardown.
- [x] Send `setVideoRecordingDefaults` and `setMaxVideoRecordingDuration`
  sequentially. Validate returned `setting`, explicit successful `status`, and
  width/height/FPS/minutes values. SDK request-ID matching is necessary but does
  not replace checking those payload values. Do not use `ready` or
  `hardwareApplied` as the success test for these stored-default commands.
- [ ] Mark applied only after both acknowledgements match the latest desired
  revision. Track partial completion for diagnostics, but expose it as pending.
  Retry the full profile idempotently; never claim a failed second write rolled
  the first one back.
- [ ] Coalesce repeated saves. If B replaces A while A is waiting, ignore A for
  latest-profile confirmation and apply B next. Account for existing SDK setters
  updating their cache after acknowledgement; restage the newest desired profile
  before the worker exits so an older callback cannot leave stale replay values.
- [ ] Use bounded retry delays, proposed 1, 3, and 10 seconds while the current
  connection remains ready. Validation/unsupported-value failures wait for user
  correction; exhausted transient failures expose Retry applying. New saves and
  fresh readiness restart the bounded attempt cycle.
- [ ] Saving while offline stores pending work without initiating a connection.
  Explicit Disconnect suppresses retries/reconnection. Active settings work is
  service-owned and has bounded foreground/wake-lock lifetime; pending offline
  work holds no wake lock.
- [ ] Settings application changes future capture defaults only. It can complete
  during an active recording without modifying or stopping that recording.

**Checks:** first apply; save offline/reconnect; first/second ACK timeout; malformed,
wrong-setting, missing-value and failed ACKs; disconnect between commands; device
switch; same-link restart; rapid A/B saves; late A cache updates; duplicate readiness;
bounded retries; service teardown; no delayed capture or unwanted reconnect.

## Phase 4 Use one profile at every video entry point

- [ ] Add `canStartVideo` independently of the existing photo/audio eligibility.
  It requires ordinary capture readiness and confirmation of the current profile.
  Guard inside the service/controller as well as in the UI.
- [ ] While pending, reject video-start taps with a concise explanation. Do not
  enqueue capture to execute after a later sync or reconnection. Notification,
  launcher, and tile paths must reach this same guard.
- [ ] On an accepted tap, snapshot the applied/current profile and recheck its
  device/session/revision immediately before beginning the recording. Construct
  the request with explicit width, height, FPS, and duration, retaining existing
  `save=true` and `sound=true` semantics.
- [ ] Persist the ID and active profile together before the start command. Do not
  create an uncertain recording merely because a pre-send defaults check failed.
  Retain uncertain state when a recording command actually may have reached the
  glasses and its acknowledgement was lost.
- [ ] On recovery, load the active snapshot with the ID. Handle legacy ID-only
  recovery without inventing the recording's duration. Clear the snapshot only
  with its matching terminal recording, never on an unrelated late event.
- [ ] Generate recording, disconnection, and stop-recovery messages from the
  active snapshot. Generate idle/default summaries from the desired profile with
  its synchronization status. Replace all fixed five-minute labels in the
  recording controller, main screen, and documentation.
- [ ] Keep Stop independent of settings synchronization. Reconnecting with an
  uncertain active recording must retain the existing stop/recovery route.
- [ ] Extend `QuickControls.State` with video-specific readiness and use it only
  for starting video. Update `CompanionService` publication and the video tile's
  unavailable explanation. Audio/photo availability and active Stop must not be
  accidentally disabled by pending video defaults.

**Checks:** main button, notification, tile, and shortcut request the same profile;
pending video settings do not block audio/photo or Stop; no deferred start;
save-new-defaults during capture preserves the active duration; lost-start ACK and
process reconstruction retain the correct ID/profile; late completion cannot
finish a newer session.

## Phase 5 Add the settings section

- [ ] Add Recording defaults above storage with resolution, frame-rate, and
  duration selectors, the shared-trigger explanation, and Save recording defaults.
  Keep 720p/30 FPS/five minutes as the initial selection.
- [ ] Add Saved on phone, Applying, Applied to glasses, and Could not finish
  applying states, plus Retry applying when relevant. Explain that an active
  video keeps its original settings. Never label an unacknowledged profile applied.
- [ ] Store editable selections separately from the observed profile. Preserve
  unsaved edits across rotation; service status refreshes must not overwrite them.
  Keep Save disabled for an unchanged draft or invalid selection.
- [ ] Subscribe in `onCompanionReady` and unsubscribe in
  `onCompanionUnavailable`, following the current asynchronous service-binding
  pattern. Call service-owned commands; do not launch BLE setters from the
  Activity's coroutine scope.
- [ ] Update the settings subtitle to cover recording, storage, and connection.
  Retain labels, accessible descriptions, and touch sizing used by `LabUi`.
- [ ] Show the selected profile and pending state on the main capture screen so
  video eligibility is understandable without opening diagnostics.

**Checks:** opening settings before binding; save without glasses connected;
rotation with unsaved edits; navigation while an ACK is pending; failure/retry;
live applied status; current recording with new saved defaults; existing storage,
background, and media Wi-Fi controls continue working.

## Phase 6 Validation and documentation

- [ ] Add focused JVM tests for profile validation, sync state/races, start
  eligibility, request mapping, and active snapshot lifecycle. Use fake transport
  acknowledgements and virtual coroutine time; add `kotlinx-coroutines-test`
  aligned with the existing `1.8.1` coroutine dependency if needed.
- [ ] Add instrumentation coverage for preference persistence, cache-only SDK
  initialization, and the bound settings screen. Isolate test preferences and
  inject fake sync where possible so UI tests do not change a real glasses
  profile or start capture. Restore any shared SDK cache touched by a test.
- [x] Build and run the companion checks from the repo root:

  ```powershell
  ./companion_android/build.ps1 -RunTests -BuildDeviceTests
  ```

  This compiles the shared SDK sources through the standalone host and runs the
  companion JVM suite; it builds, but does not execute, the device tests. It does
  not automatically run the SDK module's own test source tree.
- [ ] Because shared Android SDK sources change, also run the repository's
  supported `./scripts/check-android-compile.sh bluetooth-sdk` in its supported
  shell/toolchain when available. Record a tooling blocker rather than claiming
  the standalone host validates the Expo adapter/public-Maven build shape.
- [x] Run the focused Android tests on an idle phone after implementation. The
  existing full instrumentation suite may restore a saved connection; schedule
  it with capture/transfers idle and use explicit device serials.
- [ ] Update the companion README with the new section, available choices,
  synchronization states, and profile-based video limits. Update this checklist
  with actual evidence; do not reuse older test results as proof for this change.

## Physical acceptance matrix

Use the current custom phone/ASG baseline and the existing personal development
workflow. No vendor firmware or stock restoration is required for these checks.
Record actual APK/build identifiers and requested versus measured video settings.

| Scenario | Evidence required |
|---|---|
| Each of 720p/1080p × 5/15/30 FPS | One short phone-triggered and one short physical-button clip per combination; verify resolution, measured FPS, video/audio tracks, and playback. A reasonable initial FPS tolerance is ±1 FPS, consistent with historical 29.6/14.6 measurements. |
| Duration choices | Both ACK payloads match the profile; read persisted ASG values for all five choices. Verify real automatic stops at three minutes from both triggers and at ten minutes from at least one trigger to demonstrate removal of the old five-minute limit. |
| Persistence | Apply a non-default profile, restart the phone process, reconnect, and inspect the earliest SDK replay as well as the final applied state. Repeat with ASG restart on a surviving BLE link. |
| Phone absent | After confirmation, disconnect the phone and record with the glasses button; verify the selected profile and duration survive a glasses restart. |
| Offline edits | Save a different profile offline. Glasses keep their prior received defaults until reconnect; after confirmation, both triggers use the new complete profile. |
| Active recording | Change defaults during a clip; it retains its original limit/configuration. The next clip uses the new profile. Stop remains available throughout. |
| Partial failure | Interrupt between the two setting commands. UI stays pending, phone video start is blocked, Retry/reconnect converges, and no automatic recording begins. |
| System controls | Notification, launcher shortcut, and video tile honor current defaults and pending status; active Stop works even when sync is pending. |
| Existing capture and offload | Audio/photo still work while video defaults are pending; a longer/larger completed MP4 survives the current Wi-Fi/publication/verified-receipt path and plays from phone media. |

The physical button cannot be blocked during the two-command update. Test and
document that temporary old/mixed values are possible until both ACKs complete;
do not imply an atomic transaction or automatic rollback. If strict atomic
application becomes a requirement, revise the spec before adding a combined ASG
command.

## Completion criteria

- [ ] Settings controls, persistence, readiness integration, all phone entry
  points, and physical-button defaults meet the spec.
- [ ] Unit, instrumentation, build, and hardware evidence is recorded with any
  remaining limitations explicitly attached to the affected scenario.
- [ ] Review the final diff against the starting working tree and preserve all
  unrelated work. Commit/deploy only under the implementation task's authority.
- [ ] Mark this plan completed and move it to `plans/archive/` only after the
  required work and acceptance checks actually pass.
