---
status: active
owner: Ross
---

# Glasses Lab segmented video implementation plan

Spec: [video to phone](../specs/2026-10-28-glasses-lab-segmented-video-design.md).

Implement one shared recording profile for app and glasses-button starts, with
local/offload mode, bounded glasses segments, verified offload during capture,
phone duration/storage policy, and one library session. Hardware acceptance
remains open; this plan is not completed. Unified capture trials and the earlier
phone-only five-minute trial are recorded separately below.

## Source work

- [x] Add explicit personal-fork segmented capability and validated start options.
- [x] Keep MediaRecorder active across size-based rollover; finalize, verify,
  and expose each segment separately, with payload backlog and session limits.
- [x] Preserve failed/partial files and receipt-based source reclamation.
- [x] Include session identity/index in the manifest, and terminal count/reason
  in recording status and recovery responses.
- [x] Extend the native SDK with optional `SegmentedVideoOptions` and terminal
  metadata accessors; preserve the old command shape when absent.
- [x] Add initial phone preview settings, start preflight, concurrent offload, storage
  accounting, persistent session grouping, and ordered segment playback.
- [x] Document the contract, limits, and experimental acceptance status.

## Unified settings correction

- [x] Replace the separate preview card with one Recording defaults profile:
  resolution, FPS, 3/5/10/15/20/30/60/120/0 minutes, and Offload while recording.
- [x] Migrate v1 plus old preview settings to v2, retaining enabled/60 minutes,
  atomically committing the profile/revision before idempotent legacy cleanup.
  Preserve the active session snapshot and unrelated storage/Wi-Fi preferences.
- [x] Add canonical SDK profile/request support and one complete settings ACK.
  Test mode/duration/revision mismatches and old in-flight ACKs against new saves.
- [x] Integrate atomic glasses profile persistence,
  common app/button start coordinator, authenticated one-use readiness grants,
  status adoption/recovery, and approved Wi-Fi retention; run automated checks below.
- [x] Install the unified pair and verify migrated phone preferences.
- [x] Verify the single settings section and full-profile acknowledgement.
- [x] Verify app starts and human physical glasses-button starts in both modes.
- [ ] Verify explicit unavailable/expired-grant failure on hardware.

### Unified build and deployment checkpoint — October 28

- [x] Installed Glasses Lab **0.6.0-dev / code 6**; **105 companion JVM tests
  passed**. Final phone APK SHA-256:
  `0B72370BF414EA70F19948AE56924A86B0A6693621AC4D2701F408392347261A`.
- [x] **15 focused native SDK tests passed** on the final source.
- [x] Installed ASG version code **57624134**; **115 focused tests passed**.
  Final ASG APK SHA-256:
  `06F03E4361AB34FF000BC650265805F2A63DC77E64954ABE56278EBBE1CFF3DD`.
  The guarded 60-second installation check passed at **24.9°C / 100% battery**,
  and the personal fork remained active. This is deployment evidence, not
  recording endurance or button-parity evidence.
- [x] **32 phone instrumentation tests passed** on the preceding unified phone
  build (`138101…`). Migration cases are instrumentation tests, separate from
  the JVM count above.
- [x] Final installed phone APK `0B72370B…7261A` passed **32/32 instrumentation
  tests** in **10.166 seconds**.
- [x] Read back the actual phone migration: video
  `2,1280,720,30,60,phone`, revision **6**; photo **High**, revision **2**.
  The existing offload/60-minute choice and photo preference were preserved.
- [x] Read back the glasses' persisted complete revision **6** profile and
  confirm it matches the phone: **1280×720 / 30 FPS / 60 minutes / phone mode**.
  With the phone unlocked, Settings showed one Recording defaults section with
  those choices and **Applied to glasses**.

The baseline before this deployment is preserved at
`local/asg-dev/unified-recording-evidence/before-unified-install/`.
The following four trials used these exact installed APK hashes. The earlier
five-minute capture remains evidence for its separately identified build pair.

### Unified app/button capture trials — October 28

Both app starts used the app's Video control. For both button starts, the user
physically long-pressed the glasses button and confirmed doing so. Both button recordings were stopped through the
app's Stop control. The shared profile selected **1280×720 / 30 FPS / 60 minutes**;
offload mode used revision **6**, and local mode used revision **7**.

| Start / mode | Session | Phone files | Verified bytes | Summed container duration |
| --- | --- | ---: | ---: | ---: |
| App / offload | `labvideo-1793214790548-8429afd4` | 2 parts | 97,922,839 | 96.775490 s |
| Physical button / offload | `labvideo-1793215111261-f250256f` | 3 parts | 158,386,767 | 156.659823 s |
| App / local | `labvideo-1793215801895-98e675b2` | 1 file | 14,686,036 | 14.148567 s |
| Physical button / local | `button_594d4d56-db15-479d-8512-7ba4249491bd` | 1 file | 17,024,652 | 16.455811 s |

All seven final phone files matched their recorded size and SHA-256 and passed
full ffmpeg decoding. They contain **1280×720 H.264 video and AAC audio**, with
approximately **29.6 actual FPS** from the nominal 30-FPS profile.

During the button/offload recording, a phone database snapshot still reported
`RECORDING` while one verified part was already published: **63,746,887 bytes /
63.135057 seconds**, with receipt evidence. App Stop subsequently completed the
session successfully, with all three parts saved. This establishes live phone
publication for the physical-button start path. The app/offload camera was
observed active, but its initial live-copy check at roughly 75 seconds found no
indexed part. That session was stopped and its two final files passed validation;
**do not count it as a separate live-copy proof**.

The first media Wi-Fi connection attempt encountered an Android approval popup;
an explicit foreground retry was approved and the connection then worked. No
source change was needed. **USB remained attached for all four trials**, with
the phone temporarily kept awake while plugged in. These
short recordings do not establish unplugged/Doze operation, long-run battery or
heat, frame-perfect boundary continuity, or gapless playback.

Evidence under `local/asg-dev/unified-recording-evidence/`:

- `app-offload/final/` and `button-offload/final/`: final session/file validation.
- `button-offload/during-recording/`: live `RECORDING` snapshot and verified part.
- `app-local/verified/` and `button-local/verified/`: single-file validation;
  trial metadata is retained in each local trial's parent directory.
- `library-sessions.png` and the other UI screenshots: grouped Library results,
  recording, and settings observations.

After the trials, both devices persisted the same restored profile at revision
**8**: **1280×720 / 30 FPS / 60 minutes / phone mode**. Main showed **Ready to
offload from app or button**, with the approved media Wi-Fi connection ready.
The glasses camera reported `ActiveClients[]`; phone preferences had no
`active_video`, an empty `videos` queue, and all four trial `video_done` flags
were true. The phone was left on Capture, Ready.

The temporary `stay_on_while_plugged_in` setting was restored from **2** to its
original **0**, verified by readback. Its baseline is
`local/asg-dev/unified-recording-evidence/stay-awake-before.txt`.
Library showed one **158.4 MB / 3-part** button session, one **97.9 MB / 2-part**
app session, and the **17.0 MB** and **14.7 MB** local clips. The older
**303.5 MB / 5-part** session remained present.

- [x] App/button parity for selected quality, duration, and mode on this pair.
- [x] Verified phone publication during a physical-button offload recording.
- [x] App Stop successfully ends a physical-button recording.
- [x] Confirm final camera/queue state and restoration of offload/60 minutes and
  temporary phone display settings after the trials.
- [ ] Unavailable/expired/consumed grants, permission loss, full-storage and
  interruption recovery, long local duration ceilings, and unplugged endurance.

## Earlier phone-only build and automated checks

- [x] Focused native SDK tests: **8 passed**, zero failures, October 28, 2026.
  Covers legacy command omission, partial resolution overrides, exact 64-bit byte
  limits, zero-duration semantics, bounds, request copying, and terminal metadata.
  The companion's native SDK Kotlin and Java sources compiled in this run.
  Log: `local/asg-dev/segmented-sdk-tests.log`; XML:
  `companion_android/sdk/build/test-results/testDebugUnitTest/TEST-com.mentra.bluetoothsdk.camera.SegmentedVideoOptionsTest.xml`.
  Tests used temporary local Gradle wiring because the standalone companion maps
  SDK main sources but does not ordinarily include SDK test sources.
- [x] Final ASG verification: **73 tests passed** and the development APK built.
  Installed version code **57618735**; APK SHA-256:
  `87e6ae4c612a2b35a0d880bc7173cd50482ed607b1913ffdb218817ac7fdbf7d`.
- [x] Capture-trial companion verification: **99 JVM tests passed**, zero failures,
  errors, or skipped tests. `build.ps1 -RunTests -BuildDeviceTests` completed,
  including app APK, instrumentation APK, and signing verification. Glasses Lab
  version is **0.5.0-dev**, version code **5**. App APK SHA-256:
  `a56be6c285deec8591cbe2d596114125e288ae099a429dcac5a8ff7ef3dcc855`.
  Results: `companion_android/app/build/test-results/testDebugUnitTest/`.
  Sandbox compiler/cache access errors required an unrestricted build. A stale
  compiled constructor reference in a test was cleared with `-RebuildApp`;
  this was a host build-artifact issue, not a source failure or phone data reset.
- [x] **27 companion instrumentation tests passed** on the development phone
  using the capture-trial package.
- [x] Post-trial phone update changes only stop-reason display copy, including
  mapping `max_duration` to readable text. Its rebuild passed **99 JVM tests**,
  with zero failures/errors/skips. Version remains **0.5.0-dev**, code **5**;
  final phone APK SHA-256:
  `18c2fc047d3f948f03e23dfca0b9da2ebfaf552ffe6f599014d90194d34097bf`.
  The five-minute capture below ran on phone APK `a56be6…cc855` with ASG
  `87e6ae…dbf7d`; no additional recording was performed on this UI-only update.
- [ ] Run the standard public-SDK/Expo integration check when its prerequisites
  are available; the standalone companion build is not a substitute.

## Physical acceptance

Use guarded ASG deployment and the companion installer after successful builds,
with devices idle and existing media preserved. Archive exact APK hashes and
source before the trial. Add observations here; do not infer them from build
success or earlier single-file transfer trials.

The pre-trial baseline is preserved at
`local/asg-dev/segmented-video-evidence/20261028-121710-651632-snapshot/`,
including glasses APKs, phone APKs/preferences, and `snapshot.json`. This backup
is preparation for the trial, not evidence that segmented capture passed it.

### October 28 five-minute trial, before unified settings

Session `labvideo-1793208759820-b509277c` automatically ended with state
`FINISHED` and reason `max_duration`. Requested profile was 720p/30 FPS with a
five-minute cap. The phone published **five MP4 segments**, totaling
**303,491,729 bytes** and **300.636104 seconds of summed container durations**.
The total exceeds the 256 MiB glasses payload backlog, demonstrating reuse of
the temporary queue during the session. Each file contains H.264 video and
44.1 kHz mono AAC; requested 30 FPS produced approximately 29.6 FPS in these files.

The first 63.111837-second segment was verified on the phone while the session
was still `RECORDING`, and its glasses capture directory was reclaimed while
the next segment remained active. After automatic Stop, the phone's active
request and transfer queue were clear, the glasses camera was idle, and no
capture directories for this session remained. The phone screen was off after
approximately the first two minutes; **USB remained attached** throughout.

All five files matched their recorded size/SHA-256 and independently completed
full ffmpeg decoding. Packet inspection found a keyframe at each video start,
increasing PTS in all 8,887 video and 12,945 AAC packets, no within-file packet
gaps beyond one-microsecond rounding, and no frame intervals above 1.5 times the
observed median. These checks establish decodable segments with consistent
internal timing. They **do not establish frame-perfect or audible continuity
across boundaries**: each file resets timestamps, and no shared visible-clock
or audio reference was captured. Per-file video/audio start offsets ranged from
15.6 to 70.1 ms; end offsets ranged from −84.17 to +53.79 ms. Those are diagnostics,
not a measurement of dropped frames or perceptual synchronization.

Evidence is under `local/asg-dev/segmented-video-evidence/`:

- `20261028-live-first-session/`: first published segment and the still-recording
  phone database snapshot.
- `20261028-five-minute-session/`: final session/index/publication snapshots,
  five saved files, per-file probe/validation, and `summary.json`.
- `20261028-five-minute-session/packet-timing-review.json`: host-only packet
  inspection produced by `local/asg-dev/inspect-segment-timing.py`.

This is a short capture/offload/automatic-stop trial. It does not establish
unplugged/Doze reliability, long-run power/heat, or interruption/full-storage
behavior.

After the user unlocked the phone, the grouped Library card displayed
**303.5 MB / 5 parts**. Playback automatically advanced from part 1 to part 2,
and the Next control navigated to another part. This verifies grouping and the
tested navigation paths, not gapless playback or perceptual audio continuity.

The temporary five-minute setting was then restored through the settings UI:
`phone_video_enabled=true` and `phone_video_minutes=60`, confirmed in saved
preferences. That pre-migration phone state selected offload with a **60-minute
maximum**, subject to storage and other limits. The unified migration must retain
it. Fresh installations default to local mode. At that checkpoint the ASG package
remained the capture-tested hash above, and the phone package added only readable
stop-reason copy. Record subsequent deployment identities separately.

- [x] Four file rollovers, verified phone publication, and automatic five-minute Stop.
- [x] Earlier segment reclamation while the next segment was recording.
- [x] One Library session with five parts, automatic part 1 → 2 advancement,
  and manual Next navigation; final segment count matches the five verified files.
- [ ] At least three boundaries with continuous clock/audio; measure missing or
  duplicated frames, audio continuity, and timestamp drift.
- [ ] Quantitatively measure glasses free-space recovery and peak phone storage.
- [ ] Byte allowance stops, phone free-space changes, and a backlog-full
  stop with unverified sources retained.
- [ ] Wi-Fi/BLE interruption, lost receipt reply, app/ASG restart, final status
  replay, and partial-file recovery without automatic recording restart.
- [ ] Session deletion behavior, full playlist progression, and interruption during playback.
- [ ] Calls, notifications, tiles, physical Stop, local video, photos, and audio.
- [ ] Locked/unplugged/Doze operation, sustained throughput, battery, and heat.

Keep this plan active and the documented experimental status until the remaining
evidence supports the intended recording duration and reliability.
