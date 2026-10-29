---
status: active
owner: Ross
---

# Glasses Lab camera defaults implementation plan

**Goal:** Add a Camera defaults section for still-photo size, shared by phone and
glasses-button capture and retained across disconnect/restart. Ensure the same
choice selects the same resolution and JPEG-quality policy from either trigger.

**Architecture:** Durable photo profile → shared service-owned settings worker →
SDK reconnect cache and acknowledged glasses button size. Phone photos carry an
immutable size snapshot and opt into the glasses' local photo-quality policy.

**Tech stack:** Kotlin Android UI/SDK, SharedPreferences, coroutines, Java ASG
camera pipeline, JVM/Robolectric and Android instrumentation tests.

**Spec source of truth:** [Camera defaults design](../specs/2026-10-27-glasses-lab-camera-defaults-design.md).
Companion work: [video defaults plan](2026-10-27-glasses-lab-recording-defaults.md).

## Baseline and implementation order

At the planning baseline reviewed October 27, 2026, phone photos requested High,
saved locally with no webhook, and later offloaded through Wi-Fi. Button defaults
and cache started at Max. The two paths used different size and JPEG-quality
policies even at the same tier.
The broad SDK persistent-photo API is deprecated but the underlying button-size
command remains implemented. Camera FOV and advanced tuning have additional
global/per-request semantics described in the spec.

Initial controls: Low, Medium, High, Maximum; initial choice High. Exact output
dimensions remain hardware-dependent. Switching phone High to local High can
increase resolution/file size; do not describe it as preserving current output.
Compression, FOV, exposure, and processing controls are follow-up work.

Implement the shared persistence/lifecycle foundation once across both plans.
Unlike the video plan alone, photo parity requires an ASG extension and a
compatible glasses APK before the companion enables the new photo contract.
Apply photo and video settings independently; either can succeed while the other
waits. Preserve the existing modified files and untracked companion work.

Implementation was requested after this plan was written. Software changes and
build verification are in place; device acceptance remains open.

## Implementation evidence (October 27)

- The companion cards, profile persistence, SDK cache initialization, stable
  button-size API, Local request quality, capability checks, and shared sync worker
  are implemented. The video plan records companion build/test evidence.
- ASG carries quality through the existing immutable `PhotoCaptureSettings`,
  already retained by queued/active captures. This replaces the proposed extra
  queue/active constructor fields. Existing feedback/enqueue calls already carry
  those settings, so they needed no duplicate quality parameter. Resolution,
  JPEG quality, warm reuse, and diagnostics use quality independently of origin.
- The glasses debug APK built and signing verification passed. **91 focused ASG
  tests across 11 suites passed**, with no failures or skips. Explicit camera and
  handler filters were used because the build script's default test filters cover
  different features. ASG version: `57550478-dev`.
- Standard Expo/public SDK verification is blocked by missing Bun and generated
  `mobile/android`. The actual SDK camera tests ran through the standalone SDK
  module using a temporary init script: **15 tests passed**, including cache,
  quality-profile, and existing request regressions. SDK Kotlin/Java compilation
  passed. Reports: `local/asg-dev/sdk-defaults-verification/sdk/reports/tests/testDebugUnitTest/`.
  This verifies the standalone SDK host, not the Expo/public-Maven build shape.
- Both updated APKs are installed; the video plan records the initial trial,
  subsequent phone delivery fix, exact hashes, guarded deployment, and five
  passing isolated preference instrumentation tests on the initial phone build.
  Deployment preserved all 13 retained glasses files unchanged. The photo
  Low measurements below used the initial `AD3EA246…` phone and unchanged `5145EB02…`
  ASG build; the currently installed phone is `E16CBCB8…`.
- Saving Low in Settings reached Applied to glasses and persisted `low` on ASG.
  A phone-triggered Low photo produced a **960 × 720 JPEG / 255,419 bytes**. Its
  published phone copy matched SHA-256
  `a1ea494194a1aef73012b65d81fbc201f25773118a53020b6da11a3e2ddeb8a6`.
  Evidence: `local/asg-dev/capture-defaults-evidence/phone-low.json`.
  The subsequent media audit verified all **27 published files / 188,560,548
  bytes**, database integrity, and no private staging files remaining:
  `local/asg-dev/media-validation/20261027-180120/summary.json`.
- Force-stopping and relaunching the idle phone app preserved Low photos and
  1080p/15 FPS/three-minute videos; it automatically reconnected and returned to
  Ready to capture. Earliest replay ordering and same-link ASG readiness remain
  unverified.
- With the companion force-stopped and its process absent, an injected `cs_pho`
  event through ASG's `IntentCommandReceiver` exercised the software button path.
  It produced a **960 × 720 Low JPEG / 254,507 bytes**, SHA-256
  `ad989d9720c65510f42f9eebc56260bf2ff4d1b55dca850db053ef7b660af095`.
  The original was retained until the successful offload recorded below. Evidence:
  `local/asg-dev/capture-defaults-evidence/offline-button-low/summary.json`.
  This verifies offline software button handling, not a physical button press.
- With the companion still stopped, restarting the idle ASG process retained
  Low: a second simulated button photo produced **960 × 720 / 255,102 bytes**.
  Evidence, including its checksum:
  `local/asg-dev/capture-defaults-evidence/offline-button-low-after-asg-restart/summary.json`.
  The first phone and simulated-button JPEGs also have identical quantization
  tables: `local/asg-dev/capture-defaults-evidence/low-photo-quality-summary.json`.
  Their metadata does not encode an explicit JPEG-quality number; this supports
  Low encoding parity for those two files, not an on-device quality-95 assertion.
- A user-confirmed physical short press produced a **960 × 720 Low JPEG /
  212,255 bytes**, SHA-256
  `6f89da8b6ee075b1438ef552c418ae02c9f670013935b6243d9b407886a5720a`.
  Both dimensions and JPEG quantization tables match the phone Low photo.
  Evidence: `local/asg-dev/capture-defaults-evidence/physical-button-low.json`.
  This establishes one physical Low comparison; trigger identity comes from
  the user's confirmation, not an EXIF origin field.
- On the fixed `E16CBCB8…` phone build, a High photo produced **3264 × 2448 /
  1,753,019 bytes** and was published with a matching checksum. Evidence:
  `local/asg-dev/capture-defaults-evidence/phone-high.json`.
- All new photos and videos completed verified offload. The final audit verified
  **35/35 published files / 980,054,447 bytes**, database integrity, and no private
  staging files. All **19 receipts / 884,350,022 bytes** passed verification with
  no errors and corresponding glasses source/quarantine payloads absent.
  Evidence: `local/asg-dev/media-validation/20261027-190056/summary.json` and
  `local/asg-dev/media-validation/20261027-190135-275522-glasses-receipts/receipt-validation.json`.
  Testing ended idle with **High photos and 720p/30 FPS/five-minute videos**
  restored and confirmed on both devices:
  `local/asg-dev/capture-defaults-evidence/final-state.json`.
- Configured JPEG quality on device, Medium/Maximum, High button parity,
  same-link restart/replay, cold/warm behavior, latency/memory,
  offload for Maximum photos, and the remaining matrix below are still open. The plan
  remains active.

Checked implementation tasks below record software or test evidence; they do
not mark the separate physical acceptance matrix complete.

## File map

Companion paths below are relative to
`companion_android/app/src/main/java/dev/ross/glasses/`.

| File | Action | Responsibility |
|---|---|---|
| `PhotoCaptureProfile.kt` | Create | Size validation, initial High profile, display and explicit request mapping. |
| `RecordingSettings.kt` | Extend | Independent durable photo profile/schema/revision; preserve video, storage, and Wi-Fi preferences. |
| `CaptureDefaultsSyncController.kt` | Create once with video work | Serialized typed photo/video operations, separate revision/status/retry state and capability checks. Replaces the proposed video-only synchronizer. |
| `GlassesController.kt` | Modify | Hydrate both caches before connection, route lifecycle events, expose photo save/status/retry and `canTakePhoto`, snapshot each photo. |
| `RecordingSettingsActivity.kt` | Modify | Camera defaults card alongside video defaults, independent draft/save/status, binding-safe observation. |
| `MainActivity.kt` | Modify | Selected photo-size summary and photo-specific eligibility. |
| `CompanionService.kt`, `QuickControls.kt`, `CaptureTileService.kt` | Modify | Photo/video readiness and understandable pending state for system controls, preserving audio and Stop. |
| `PhotoSession.kt` | Extend only if needed | Tie in-flight snapshot to request ID; retain stale-event protection. No new durable photo-stop recovery. |

Other paths are relative to the repository root.

| File or directory | Action | Responsibility |
|---|---|---|
| `mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/MentraBluetoothSdk.kt` | Modify | Stable size-only acknowledged API and cache-only initializer; preserve deprecated API behavior. |
| `mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/camera/CameraModels.kt` | Modify | Optional typed Local quality profile, default legacy behavior, request parsing/validation. |
| `mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/sgcs/MentraLive.kt` | Modify | Serialize optional quality profile; reuse video plan's one readiness-completion event. |
| `asg_client/app/src/main/java/com/mentra/asg_client/AsgConstants.java` | Modify | New protocol capability/version constants, following ASG instructions. |
| `asg_client/app/src/main/java/com/mentra/asg_client/service/core/handlers/SettingsCommandHandler.java` | Modify | Advertise versioned local-photo-quality support in the button-photo ACK. |
| `asg_client/app/src/main/java/com/mentra/asg_client/service/core/handlers/PhotoCommandHandler.java` | Modify | Validate opt-in profile and request shape before capture; route it into local save. |
| `asg_client/app/src/main/java/com/mentra/asg_client/io/media/core/MediaCaptureService.java` | Modify | Carry quality profile consistently through feedback prediction and camera enqueue. |
| `asg_client/app/src/main/java/com/mentra/asg_client/camera/model/QueuedPhotoRequest.java`, `ActivePhotoCapture.java` | Modify | Immutable quality policy separate from origin, retaining constructor compatibility. |
| `asg_client/app/src/main/java/com/mentra/asg_client/camera/CameraNeoService.java` and `camera/lifecycle/PhotoSession.java` | Modify | Resolve quality for capture/reuse/feedback while preserving origin and callbacks. |
| `asg_client/app/src/main/java/com/mentra/asg_client/camera/policy/PhotoQualityProfile.java` | Create | Explicit internal Default/SDK/Local quality resolution without changing legacy tier constants. Put new constants in `AsgConstants` as required. |
| `asg_client/app/src/main/java/com/mentra/asg_client/camera/lifecycle/CameraOpener.java`, `camera/policy/PhotoResolutionPolicy.java` | Extend narrowly if needed | Accept resolved quality policy while preserving existing overloads/behavior. |
| Companion/SDK/ASG test trees | Add/extend | Persistence, synchronization, serialization, request validation, quality resolution, queue and warm-session behavior. |
| `companion_android/README.md`, `asg_client/docs/mentra-live-spec.md`, `asg_client/docs/ASG_CLIENT_API.md` | Update after implementation | Controls, changed photo output policy, protocol field/capability and compatibility. |

## Phase 1 Shared foundation and persistence

- [x] Reconcile the video checklist with this plan before coding. Create one
  `CaptureDefaultsSyncController` with independent typed photo/video state and one
  service lifecycle. Share the transport/scheduler plumbing, not one combined
  applied flag or revision.
- [x] Add `PhotoCaptureProfile(size)` with four canonical choices and High initial
  value. Persist profile/schema/revision together in a photo-specific namespace.
  Handle missing/corrupt values deterministically and report failed saves.
- [x] Define the SDK mapping and UI summary separately from Android views. Keep
  Local quality policy fixed for this feature; do not expose a misleading
  compression/JPEG-quality selector.
- [ ] Define per-photo snapshots as request ID, size, quality policy, desired
  revision, and device/session. Local profile saves cannot mutate that snapshot.

**Checks:** all four sizes; malformed data; failed local persistence; independent
photo/video revisions; no changes to existing storage or media Wi-Fi preferences.

## Phase 2 Make equal size choices mean equal capture policies

- [ ] Add an optional typed quality profile to Android `PhotoRequest`. Keep the
  default omitted on the wire and append the parameter compatibly to existing
  construction. Serialize Local as `qualityProfile: "local"`. Cover map parsing,
  request copying/routing, and the actual `MentraLive` JSON serializer.
- [ ] Validate ASG input before file allocation or queue admission: accept Local
  only for ordinary photo, `save=true`, no webhook. Reject unknown values/types
  and incompatible request shapes with a correlated error. Omitted/default
  retains existing SDK behavior, including text and upload/BLE paths.
- [ ] Carry an explicit resolved quality policy through local save, queued
  request, and active capture. Preserve existing constructors/overloads for other
  callers. Legacy button requests derive Local; legacy SDK requests derive SDK.
- [ ] Use that policy for JPEG size and quality. Both shared-profile routes use
  button/local targets and JPEG quality 95; Maximum still resolves through the
  supported JPEG output list. Do not change the legacy SDK tier constants.
- [ ] Include quality policy in configured-camera compatibility and warm/cold
  feedback prediction. A warm SDK-sized session must not supply stale dimensions
  to a Local request; queued A/B sizes keep their own policies.
- [ ] Keep origin independent: phone requests retain `source=sdk` and their
  request IDs even with Local quality. Add quality-profile information to resolved
  capture diagnostics so the dimensions/quality are explainable. Preserve
  terminal callbacks, capture IDs, sidecars, and capture persistence.
- [ ] Add `localPhotoQualityVersion: 1` to successful `button_photo` ACK values
  only with this implementation present. Keep the existing effective-size ACK and
  one size-setting command; no new persistent quality preference is necessary.

**Checks:** legacy SDK and button target/quality behavior; all Local tiers;
invalid profiles; unchanged text/manual/upload behavior without opt-in;
immutable queued profile; warm reuse/reopen; correct diagnostic origin;
capability present on new ASG and absent on old responses.

## Phase 3 SDK defaults and service synchronization

- [ ] Add the narrow acknowledged size setter and cache-only size initializer.
  The new setter uses the existing wire command without sending scan tuning or
  `resetCaptureTuning`. Retain the deprecated general setter for compatibility.
- [ ] Seed photo and video reconnect caches from durable preferences after SDK
  creation and before selecting/connecting the saved device. A photo save must
  never clear video cache values, FOV, scan tuning, or other settings.
- [ ] Use the shared worker to send photo size. Confirm request/setting/status,
  effective canonical size, capability version, desired revision, and current
  device/connection generation. Missing capability is an explicit incompatibility
  state, not a retryable timeout. Acknowledged SDK cache values alone are not proof.
- [ ] Reuse the video plan's readiness-completion signal and same-link ASG restart
  handling. Invalidate both confirmations and cached capability knowledge on
  disconnect, device change, process restart, or changed ASG session.
- [ ] Coalesce A/B saves, guard stale ACKs and post-ACK SDK cache updates, and
  restore the latest desired cache before becoming idle. Apply domains fairly;
  one failure/backoff must not prevent the other from reaching confirmation.
- [ ] Bound retries and active service/wake-lock work. Save offline without
  connecting, honor explicit Disconnect, and retain a manual Retry applying action.

**Checks:** cold-start replay; late A response after B save; wrong/missing ACK
fields; older capability; same-link restart; disconnect/device switch; two domains
pending with one failing; no worker starvation or capture-on-reconnect.

## Phase 4 Photo entry points and settings UI

- [ ] Keep base capture readiness separate from `canTakePhoto` and
  `canStartVideo`. Photo readiness additionally requires its latest profile and
  capability confirmation. Do not add photo readiness to a shared predicate that
  video/audio also depend on.
- [ ] Guard `takePhoto` centrally, then snapshot the profile immediately before
  admission. Populate explicit size and Local quality while retaining save-only
  routing, sound, and existing privacy behavior. Pending settings do not create
  a capture job or a deferred shutter action.
- [ ] Preserve request correlation on disconnect/late responses and unknown
  outcome handling. Reconcile media through the existing offload path; never
  automatically retry the shutter because an ACK timed out.
- [ ] Add photo readiness to the system-control snapshot alongside video
  readiness. Main button, notification, launcher shortcut, and tile all reach the
  same guard. Stop and audio remain independent of both defaults states.
- [ ] Add Camera defaults near Recording defaults in Settings, each with its own
  draft, Save action, application status, and retry. Preserve drafts through
  rotation/status refresh and use service-bound observation rather than Activity
  BLE jobs. Explain a queued photo keeps the size chosen when it began.
- [ ] Show Saved on phone / Applying / Applied to glasses / Could not apply / Needs
  compatible glasses software accurately. A pending photo change should not make
  an already-applied video profile appear pending.
- [ ] Review the current 8 MiB phone-space preflight against measured High/Max
  captures. Retain actual-size transfer reserve checks and source preservation;
  the preflight is not a guarantee that every larger JPEG will fit. Avoid broad
  media-storage changes unless measurements expose a concrete issue.

**Checks:** every photo entry point, pending explanation, offline save, binding and
rotation, changed defaults during a queued shot, separate photo/video drafts and
readiness, audio/Stop unaffected, low-space failure retains the glasses original.

## Phase 5 Build and verify after implementation

- [ ] Add focused companion JVM tests for persistence mapping, sync/capability
  races, independent eligibility, and photo snapshot correlation. Reuse the video
  plan's coroutine test setup instead of adding a second framework.
- [ ] Extend ASG policy, queue/session, warm-feedback, and handler tests. Include
  legacy compatibility and a real path from parsed JSON to the resolved
  resolution/quality policy, not only a test that compares enum values.
- [ ] Add SDK serializer/cache tests. The standalone companion includes SDK main
  sources but does not automatically run the SDK's own test source tree; run
  those tests through their supported host or cover the actual path in isolated
  companion instrumentation. Restore shared cache state after tests.
- [x] Build and run the companion suite and build its device-test APK:

  ```powershell
  ./companion_android/build.ps1 -RunTests -BuildDeviceTests
  ./asg_client/scripts/build-dev.ps1 -RunTests
  ```

- [ ] Run the repository compile checks for both changed Android components
  (`./scripts/check-android-compile.sh`) in its supported environment. Record any
  tooling blocker; the companion host alone does not validate the Expo/public
  Maven build configuration.
- [ ] Run focused instrumentation with isolated preferences/fake transport first.
  Run device capture acceptance only on the intended idle development devices,
  recording APK identifiers. Existing tests can restore a connection; avoid
  unintentionally starting a real capture while running UI tests.
- [ ] Update README, ASG product/protocol docs, and both implementation checklists
  with actual evidence. Use the current personal development APK workflow; this
  feature does not require firmware changes.

## Physical acceptance matrix

| Scenario | Required evidence |
|---|---|
| Each size × phone/button × cold/warm | Compare resolved JPEG dimensions and configured quality for the same camera configuration; inspect the final saved JPEG, orientation, source/request metadata, cues, and privacy indicators. Record targets versus actual outputs. |
| High and Maximum | Measure capture latency, peak memory symptoms, file size, and successful phone publication; determine whether they resolve to the same output on this device. |
| Alternating/queued sizes | Queue A, save B, capture B. Confirm A retains its settings and B uses the new size, including reuse/reconfiguration across SDK and button origins. |
| Offline button use | Apply a non-default size, disconnect the phone, restart glasses, and take a button photo. Confirm retained size and later verified offload. |
| App/ASG restart | Inspect earliest cache replay after phone process recreation and repeat with a same-link ASG restart; confirmation/capability must be re-established. |
| Failure and compatibility | Lost/wrong ACK, rapid saves, device switch, and missing capability remain honest UI states with no deferred capture. Simulate old responses in tests before any deliberate old-APK device check. |
| Photo/video independence | Save both profiles, fail one domain, and confirm the other can apply/capture. Photo edits must not alter a current video or disable Stop. |
| Entry points and delivery | Main button, notification, shortcut, tile, and physical button use the selected size; media Wi-Fi, verified receipt, sidecars, and low-space retention work for larger JPEGs. |

## Completion criteria

- [ ] All offered sizes satisfy the shared-size/quality contract on supported ASG.
- [ ] Existing callers without Local opt-in retain their prior behavior.
- [ ] Shared sync lifecycle and independent photo/video controls pass the combined
  failure/restart tests; no duplicate synchronizers or readiness events remain.
- [ ] Actual build, unit, instrumentation, and hardware evidence is recorded.
- [ ] Review against the starting working tree; preserve unrelated changes.
- [ ] Mark completed and archive only after required implementation and acceptance
  work passes. Commit/deploy only under the later implementation task's authority.
