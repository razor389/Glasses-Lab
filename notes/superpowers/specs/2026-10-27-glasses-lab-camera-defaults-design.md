---
status: active
owner: Ross
---

# Glasses Lab camera defaults

Add a **Camera defaults** card for still photos beside the planned **Recording
defaults** card. One saved photo-size choice applies to the phone Photo button,
notification, launcher shortcut, Quick Settings tile, and glasses camera button.
The glasses retain their applied size for capture without the phone.

Implementation checklist: [camera defaults plan](../plans/2026-10-27-glasses-lab-camera-defaults.md).
Reuse the service, persistence, and reconnect design in the
[video defaults spec](2026-10-27-glasses-lab-recording-defaults-design.md).
Implementation evidence and remaining device acceptance are recorded in the plans.

## Initial controls

The proposed first release controls **Photo size**: Low, Medium, High, Maximum.
Use High as the initial choice, retaining the current phone request's tier name.
Provide **Save camera defaults**, application status, and **Retry applying**.
Explain: "Used for photos taken from this phone or the glasses button. Changes
apply to the next photo. Larger photos use more storage and take longer to
transfer."

The implementation must make these choices mean the same resolution/quality
policy for both triggers. Current source targets are:

| Choice | Current phone/SDK target | Current button/local target | Proposed shared target policy |
|---|---|---|---|
| Low | 640 × 480 | 960 × 720 | Button/local |
| Medium | 1280 × 960 | 1440 × 1088 | Button/local |
| High | 1920 × 1440 | 3264 × 2448 | Button/local |
| Maximum | Largest supported JPEG output | Largest supported JPEG output | Largest supported JPEG output |

These are targets, not guaranteed output dimensions. The camera chooses a
supported JPEG size; Maximum depends on the actual camera output list. Keep tier
labels in the UI until dimensions have been measured for the current device and
camera configuration. High and Maximum may select the same supported output.

Use the existing local JPEG quality policy, currently 95, for both triggers. It is
an internal policy rather than a second slider. Moving phone High to local High
can increase actual resolution, file size, memory use, and latency. This is an
intentional behavior change, despite retaining the initial tier name; validate
it on hardware before release. The glasses' previous independent Max default
also becomes High after synchronization.

## What the current code actually does

Source reviewed October 27, 2026, against the working tree and current
`companion_android` app. Existing edits are part of that baseline.

| Source | Finding |
|---|---|
| [`GlassesController.takePhoto`](../../../companion_android/app/src/main/java/dev/ross/glasses/GlassesController.kt) | Requests `PhotoSize.HIGH`, `save=true`, empty webhook URL, `compress=NONE`, and `sound=true`. |
| [`CameraModels`](../../../mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/camera/CameraModels.kt) | Defines `PhotoRequest`, `PhotoSize` (low/medium/high/max), and optional fields in `PhotoCaptureDefaults`. |
| [`MentraBluetoothSdk`](../../../mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/MentraBluetoothSdk.kt) | `setPhotoCaptureDefaults` still sends an acknowledged persistent `button_photo` update, but is deprecated in favor of per-request tuning. Per-request tuning alone cannot configure an offline hardware button. |
| [`DeviceStore`](../../../mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/DeviceStore.kt) and [`MentraLive`](../../../mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/sgcs/MentraLive.kt) | Cache defaults to Max and replays photo size and any cached scan tuning during readiness. The cache needs hydration before connecting. |
| [`SettingsCommandHandler`](../../../asg_client/app/src/main/java/com/mentra/asg_client/service/core/handlers/SettingsCommandHandler.java) and [`AsgSettings`](../../../asg_client/app/src/main/java/com/mentra/asg_client/settings/AsgSettings.java) | Persist button size and acknowledge the effective stored size. Size-only updates leave capture tuning alone. |
| [`ButtonEventSubscriber`](../../../asg_client/app/src/main/java/com/mentra/asg_client/service/core/handlers/subscribers/ButtonEventSubscriber.java) | Reads the saved size when initiating a local photo, subject to existing gallery-mode behavior. |
| [`PhotoCommandHandler`](../../../asg_client/app/src/main/java/com/mentra/asg_client/service/core/handlers/PhotoCommandHandler.java) | Routes the companion's save-without-webhook request into `takePhotoForLocalSave`; compression does not enter that capture call. |
| [`MediaCaptureService`](../../../asg_client/app/src/main/java/com/mentra/asg_client/io/media/core/MediaCaptureService.java) | Local-save phone photos still use the SDK sizing flag; physical-button photos use the local sizing flag. Both are queued for capture. |
| [`PhotoResolutionPolicy`](../../../asg_client/app/src/main/java/com/mentra/asg_client/camera/policy/PhotoResolutionPolicy.java), [`CameraOpener`](../../../asg_client/app/src/main/java/com/mentra/asg_client/camera/lifecycle/CameraOpener.java), and [`PhotoSession`](../../../asg_client/app/src/main/java/com/mentra/asg_client/camera/lifecycle/PhotoSession.java) | Resolve different size and JPEG-quality policies from `isFromSdk`. That flag also labels diagnostic source and participates in camera reuse decisions. |

Simply sending the same size enum through both paths is insufficient. The plan
includes a small, explicit extension to the Android SDK and ASG capture path.

## Shared photo-size contract

Persist `PhotoCaptureProfile(size)` with its own schema/revision in a dedicated
namespace, using the same save-failure handling as the video profile. The profile
is app-wide and applies to the selected device. Live confirmation is scoped to
device identity and connection generation, and is never trusted across process
recreation without fresh acknowledgement.

The proposed SDK additions are:

- A narrow `setButtonPhotoSize(size)` acknowledged API using the existing
  `button_photo_setting` wire command. Keep the deprecated broad scan-preset API
  available for existing callers; shared button size has its own documented API.
- A cache-only size initializer, called after SDK creation and before saved-device
  selection/connect. It changes `button_photo_size` only and sends no BLE.
- An optional typed photo quality-profile field on `PhotoRequest`, serialized as
  `qualityProfile: "local"` when the companion opts in. Omitted/default retains
  existing SDK behavior. The initial contract accepts Local for ordinary
  `mode=photo`, `save=true`, empty-webhook captures; reject invalid combinations
  before queuing a photo.

ASG advertises support in the acknowledged `button_photo` response through an
additive field, proposed `localPhotoQualityVersion: 1`. This field is new work,
not an existing capability. The phone requires the matching stored size and this
capability before enabling shared-profile photo capture. A missing capability
gets an incompatible-software message, not endless retries or a silent fallback
to the old different-size behavior. Older glasses may still accept the size
write; the UI must not imply that incompatibility rolled that write back.

Carry the quality-profile choice separately from request origin through the ASG
queue and active capture. Use it for resolution selection, JPEG quality, camera
reuse checks, and warm/cold feedback prediction. Keep the original request ID,
source label, callbacks, and local-save transfer path. Do not relabel a
phone-originated request as a physical-button request to obtain local quality.
Existing SDK requests without the new field retain their existing size targets,
JPEG qualities, text behavior, and upload/BLE handling.

No new persistent quality preference is needed on the glasses: the physical
button already uses local quality. Its saved size is the only editable field in
this iteration. Advanced stored button tuning remains independent; the shared
guarantee covers selected size and JPEG-quality policy, not identical exposure
or processing when other tuning differs.

## Coordination with video defaults

Build one service-owned `CaptureDefaultsSyncController`, with separate photo and
video desired profiles, revisions, errors, and confirmation states. This refines
the video plan's previously proposed `VideoDefaultsSyncController`; create the
shared component once. Use a serialized settings worker and typed photo/video
operations rather than two competing workers or a general settings framework.

Hydrate both SDK caches before the first connection replay. Reuse the planned
readiness-completion event and `glasses_session_changed` invalidation for both
profiles. Photo application requires one acknowledged size command plus its
capability check. Video still requires its two acknowledged commands. A photo
failure must not prevent the worker attempting video, or vice versa. Do not hold
the worker through a retry delay for one domain while the other has ready work.

Keep the existing bounded retry, latest-revision, device/session, and stale-cache
rules. A late response for profile A cannot confirm profile B or leave A as the
next reconnect value. Explicit Disconnect suppresses reconnects. Pending offline
preferences hold no wake lock and never queue a future capture.

Separate ordinary capture eligibility from `canTakePhoto` and `canStartVideo`.
Photo pending blocks only new phone photo requests; video pending blocks only new
phone video requests. Audio and Stop retain their ordinary eligibility. Extend
system-control snapshots with photo readiness alongside video readiness; enforce
the actual guard in `GlassesController` for all entry points.

Each accepted photo snapshots size, quality policy, revision, device/session, and
request ID before sending. A settings save during a queued/in-flight photo affects
future photos. The glasses queue must retain the original request values. If an
acknowledgement is lost, report uncertainty and continue existing media discovery;
do not retry the shutter command automatically. Unlike video, a completed still
photo needs no persisted stop-recovery profile.

The physical button remains usable while disconnected or applying defaults. It
uses the size stored when the photo is enqueued. With one size field there is no
video-style two-setting mixed profile, but already queued photos may use the
previous size. An acknowledgement confirms the stored setting, not the size of
every photo already in the queue. Existing preference setters do not propagate
disk-commit failure; reboot persistence requires acceptance testing.

## Other camera options

These are explicit follow-up candidates rather than controls promised by this
initial photo-size plan:

| Option | Work needed before offering a shared default |
|---|---|
| Transfer compression (`none/medium/heavy`) | Current local-save captures keep their original JPEG; a transfer-compression selector would not control saved-photo quality. A separate archival quality feature needs its own encoding/storage design. |
| Field of view / crop position | SDK supports FOV 62–118 and center/bottom/top, but applying it can restart the camera HAL and a temporary override can defer it. Treat it as a global camera setting, coordinate with active photo/video/streaming, hydrate its cache, and distinguish saved from hardware-applied status. |
| ZSL, MFNR, edge enhancement | Both per-request and stored tuning exist, but SDK requests deliberately do not inherit button scan presets. Define defaults/override precedence and test actual hardware behavior before exposing them. |
| Noise reduction / ISP gain | Some Camera2 fields report `not_implemented`; stored changes can also bridge into global HAL tuning. A successful settings ACK is insufficient evidence that a per-shot setting worked. |
| Manual exposure / ISO | Currently per-request; button persistence and capability/range validation need additional design. ISO is used only with manual exposure. |
| Text capture mode | Per-request sensor/crop behavior is distinct from ordinary photos; physical-button mode selection needs an explicit protocol/product extension. |
| Shutter cues | Persistent photo sound exists, but cues stay on for companion photo requests in this iteration, consistent with the video plan. No cue toggle or reset of existing button tuning is bundled into a size save. |

## Acceptance

For each of the four size choices, verify phone and physical-button photos use
the same resolved dimensions and configured JPEG quality under the same camera
configuration. Cover cold and warm capture, alternating sizes, offline button
capture, phone/ASG restarts, failed/late ACKs, settings changes during a photo,
older ASG capability handling, and simultaneous photo/video settings work.
Verify source/request metadata, cues, privacy indicators, and the existing
verified Wi-Fi offload path. The implementation plan lists concrete files and
checks. Software builds and automated tests are recorded separately from physical
capture acceptance; passing a build does not establish actual camera output.
