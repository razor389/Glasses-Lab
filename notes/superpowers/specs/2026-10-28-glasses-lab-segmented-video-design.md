---
status: active
owner: Ross
---

# Glasses Lab shared video settings and offload

Use one saved video profile for starts from the phone and the glasses button.
An offload toggle extends recording beyond the glasses' single-file limit by
sending completed segments to the phone while capture continues. The phone owns
duration and recording-storage preferences; the glasses retain a bounded
temporary queue. This remains experimental. Source and JVM checks do not
establish continuous picture/sound or endurance on the actual device pair.

Implementation checklist: [segmented video plan](../plans/2026-10-28-glasses-lab-segmented-video.md).
Preserve the [shared recording defaults](2026-10-27-glasses-lab-recording-defaults-design.md)
and [independent companion](2026-10-26-glasses-lab-independent-companion-design.md).

## User behavior

Settings has one **Recording defaults** section: 720p/1080p resolution,
5/15/30 FPS, a 3/5/10/15/20/30/60/120-minute maximum or **No time limit**, and
**Offload while recording**. Off saves locally on the glasses for later transfer;
on transfers verified parts while recording. Both start surfaces use the same
whole profile. One Save action persists its revision and applies it atomically;
the app only shows Applied after the glasses confirm every actual field and
revision. A stale acknowledgement cannot confirm newer settings. Changes affect
the next recording; the active session retains its original snapshot.

Fresh installations default to 720p/30 FPS/five minutes with offload disabled.
Migration preserves the previous quality and local duration, or the enabled
phone-preview duration when that mode was selected. In particular, the existing
enabled/60-minute choice is retained. The v2 profile and incremented revision
commit together before the two old preview keys are removed from their separate
preference file. Interrupted cleanup is retryable; v2 is authoritative. Migration
does not rewrite an active recording's stored profile.

Offload mode needs the phone's approved media Wi-Fi connection and a short-lived
capacity grant for either start surface. Before granting readiness, the phone
checks capabilities and available recording allowance, including publication
headroom. Offload mode keeps an approved connection ready even if the general
Keep media Wi-Fi ready preference is off; it does not create a background Android
approval prompt. That preference remains saved for local-mode transfers.
Unsupported or unavailable connections produce an explicit failure; they must
not silently start a local clip.
The existing combined audio/video allowance and phone free-space reserve apply.
No older phone media is automatically removed to make room.

Offline local-mode button starts use the last confirmed whole profile. Local
recordings remain subject to the glasses' file and storage ceilings even with a
longer selected duration or No time limit. Physical Stop remains available
without a readiness grant. The phone adopts an authoritative button-started
session reported by the glasses; receiving a raw button event never sends a
second Start. Video includes microphone audio and existing indicators and cues.

The front MTK privacy LED stays latched on throughout capture. The separate
inward BES RGB white status indication has a timed lease; segmented and
unified-profile local sessions renew it every ten minutes, guarded by the active request identity. Battery
polling remains active. Local video does not currently implement an app-level
thermal-stop monitor; temperature and power endurance require measurement.

The library groups ordered segments as one video session. Underlying phone media
contains separate MP4s; this iteration does not promise one joined MP4 export.
Explicit Stop, call interruption, app recovery, or a glasses restart never
silently starts a replacement recording. Incomplete sessions remain identifiable
and previously saved segments remain available.

## Capture and delivery

Keep the camera and encoder running while Android MediaRecorder rolls to the next
output file. Default segment size is **64 MiB** and the glasses payload backlog
is **256 MiB**. These are internal transport defaults, independent of the user's
session duration and phone allowance. The size-based rollover API is available
from Android API 26; picture/audio continuity at the actual MTK file boundaries
still needs measurement.

Each segment has its own capture directory and zero-based sequence. While writing,
its MP4 is a `.partial` file excluded from gallery sync. After close, verify its
tracks, sync the file, and atomically publish the completed name. Only finalized
segments can be listed, downloaded, hashed, or reclaimed while the next segment
is being recorded. This mode does not generate IMU sidecars or thumbnails; older
ordinary captures and their sidecars retain their existing policy.

The companion uses authenticated local Wi-Fi and the existing verified-media
protocol: download → verify → publish durable phone copy → verify publication →
receipt for that exact source generation. Lost replies are retryable. Failed
transfer or publication never authorizes glasses cleanup. The session index must
persist segment identity before reclamation and reconcile final segment count,
so receipt loss, app restart, or finalization delay cannot truncate a session.

Transfer throughput must exceed average encoded output for sustained recording.
If transfer stalls, existing segments remain on the glasses until the bounded
backlog or low-storage guard stops capture. The backlog includes retained segments
from earlier sessions and held receipt payloads. Small completed receipt journals
remain for idempotency and still consume disk; global low-space checks remain
necessary. Battery, temperature, radio reliability, and phone background limits
still constrain duration.

## Endpoint contract

The personal fork advertises `recording_profile_version: 1` for shared profile
support and `segmented_recording_version: 1` for segmented capture. Do not infer
support from an APK name or version. BLE `set_recording_profile` validates and
commits the whole profile before a `settings_ack` with setting
`recording_profile`, status `applied`, and flat actual values:

| Field | Contract |
| --- | --- |
| `revision` | Durable phone profile revision, echoed exactly. |
| `width`, `height`, `fps` | Shared resolution and frame rate. |
| `maxRecordingTimeMinutes` | 3/5/10/15/20/30/60/120 or zero for no time limit; storage guards still apply. |
| `mode` | `local` or `phone`. |
| `segmentMaxBytes` | Default 67,108,864; allowed 8–256 MiB. |
| `maxBacklogBytes` | Default 268,435,456; at least three segments and at most 4 GiB. |

The SDK exposes `UnifiedRecordingProfile`, `cacheRecordingProfile`, and
`setRecordingProfile`. Reconnect stages the canonical profile instead of replaying
the legacy video-quality/duration pair. App Start sends `start_video_recording`
with `requestId`, `useRecordingProfile: true`, and `profileRevision`. It saves
with sound and supplies no competing quality/duration/segment overrides. The
glasses reject a revision mismatch. The button calls the same start coordinator
using the stored profile. The older optional `SegmentedVideoOptions` SDK request
path remains available for compatibility; it is not the companion's shared path.

`POST /api/lab/v1/recording-ready` uses the existing authenticated lab sync
session. Its body contains `profileRevision`, `grantId`, `sessionId`,
`maxSessionBytes`, and `ttlSeconds` (at most 180; zero revokes). The phone
determines a unique session ID of 1–128 UTF-8 bytes and persists the pending grant
before publishing it. Available capacity, less staging headroom, bounds its
immutable session quota. App and button starts consume that same authority once.
Renewal of the same grant may refresh its TTL but cannot change profile, quota,
or session, or rearm a consumed grant. Token/session invalidation removes its
authority. An issued grant can remain usable until expiry if network loss makes
revocation impossible; saved preferences alone never constitute readiness.

The phone continues checking capacity during transfer/publication and reconciles
pending grants and active sessions through `get_video_recording_status`, which
also supports a query without a request ID. Start/status reports include actual
`requestId`/`sessionId`, `origin` (`app` or `button`), `segmented`, and the complete
`profile`; phone-mode status also carries `grantId` and `maxSessionBytes`.

Manifest rows for completed segments add `session_id` and `segment_index` to the
existing exact-source identity, generation, size, and hash metadata. Session and
segment identity come from the encoded capture-directory name; filenames are not
user-controlled paths.

Terminal `video_recording_status` reports `sessionId`, `segmentCount`, and
`stopReason`. `segmentCount` counts contiguous finalized segments, not prepared
files. A clean terminal event has `recording_stopped` and `success: true`; recorder
or finalization failure has `error` and `success: false`. The final event follows
final-file validation. Matching idle stop/status queries can replay persisted
terminal information, including nested `data`; SDK accessors support both shapes.
Reasons include explicit stop, duration/session-byte/backlog/storage limits,
battery, and recorder/finalization failures. Unknown reasons remain visible.

## Acceptance boundary

The earlier October 28 phone-only implementation's five-minute trial completed
four rollovers, verified and reclaimed
segments while capture continued, and automatically stopped with five playable
files. Full decoding and packet-timing checks passed; USB remained connected.
The later unified APK pair passed four short trials: app and human physical
glasses-button starts in both local and offload modes. All final files passed
size/hash verification and full decoding; the button/offload session also
published a verified part while still recording. Migration, matching persisted
profiles, the single settings section, and full-profile acknowledgement were
verified. USB remained connected. See the active plan for exact build identities,
trial evidence, and limits; the earlier five-minute trial used a different pair.

Before treating this as dependable long recording, test several rollovers with a
continuous visible clock and audio source, play the phone files across boundaries,
and verify timestamps and audio/video synchronization. Record while offloading,
verify exact reclaimed bytes, disconnect Wi-Fi, exhaust an allowance, lose a
receipt reply, restart each endpoint, and ensure no unverified source disappears.
Still verify unavailable/expired/consumed grants, permission loss during renewal,
reconnect/status adoption, and Stop when readiness is absent. Check long local
duration choices against the actual glasses file/storage ceiling.
Test the locked, unplugged phone under idle/Doze and measure glasses heat and both
batteries. Ordinary video, physical buttons, photos, and segmented audio must
continue to work. Keep this spec and plan active until that evidence is recorded.
