# Verified local media sync for the personal fork

The separately installed `com.mentra.asg_client.thirdparty` build supports a
local-only companion protocol on the existing camera server, port 8089. Stock
`com.mentra.asg_client` does not advertise or expose these endpoints. Its existing
gallery acknowledgment and seven-day recoverable-trash policy are unchanged.

The phone saves and verifies a durable copy before requesting reclamation. A
receipt deletes exactly one matching completed file in the fork's own media
namespace. It does not delete a capture directory recursively, unrelated stock
media, unacknowledged siblings, or all recoverable trash.

## Session authorization

### Recording readiness and the physical button

`recording_profile_version: 1` advertises shared app/button recording settings. In phone-offload mode,
the companion persists a pending session before POST `/api/lab/v1/recording-ready` using the existing
lab bearer authorization. The body contains `profileRevision`, `grantId`, `sessionId`, integer
`maxSessionBytes`, and integer `ttlSeconds` (1–180; 0 revokes). A typical lease is 90 seconds renewed
every 30 seconds with the same immutable identity/profile/allowance. Success `data` echoes those four
identity/allowance fields, `expires_in_seconds` equal to the requested TTL, and `consumed: false`.

App or button start consumes the grant once and records under its predetermined session ID. Renewing
or revoking a consumed identity returns HTTP 409 `grant_consumed`; the phone queries recording status
instead of allocating another allowance. The last consumed identity is persisted before capture.
Unused revocation is idempotent even after a profile change and cannot revoke a different grant.
Expiry, BLE authorization loss, or hotspot reservation loss removes readiness. Neither reconnection
nor a delayed renewal rearms a consumed grant. Recording already underway remains bounded by its
original byte allowance/backlog and does not require continuing grant renewal.

Other conflict reasons include `profile_mismatch`, `grant_mismatch`, `grant_expired`, `phone_not_ready`,
and `recording_busy`. A start that consumes a grant but fails setup persists a failed terminal with
zero completed segments. The terminal snapshot supplies the profile, origin and grant identity so
the phone can reconcile missed start/stop events without treating an empty manifest as proof of saving.

The phone generates 32 random bytes, encodes them as 64 lowercase hexadecimal
characters, and supplies `sync_token` on the existing BLE command:

```json
{"type":"set_hotspot_state","enabled":true,"sync_token":"<64 lowercase hex characters>"}
```

All `/api/lab/v1/` requests require `Authorization: Bearer <sync_token>`. Authority
is held only in the ASG process, bound to the current hotspot SSID/password on
first use, and expires after 30 minutes of API inactivity. Disabling the hotspot,
changing its reservation, or restarting ASG invalidates it. The paired phone must
provision a new token to resume. A BLE disconnect also invalidates the token and
any idle lease. HTTP errors never return the token, and generic
command/transport logs omit raw payloads; the existing structured BLE trace
sanitizes token-bearing fields.

`GET /api/v3/capabilities` advertises `data.lab_sync_version: 1` only in this fork.
Clients must negotiate that capability; a successful APK build or version suffix
does not establish that a particular glasses installation supports reclamation.

Fork builds with `data.lab_sync_idle_lease_max_seconds: 180` also support a
renewable hotspot idle lease. The phone can retain its user-approved Wi-Fi
network request and renew every 60 seconds while its sync service is running.
This keeps the existing Android 11 local-only hotspot reservation, whose random
credentials cannot be replaced through a supported custom-credentials API on
this platform. A later reservation can still require Android approval.

## Endpoints

Success responses use `{"status":"success","data":{...}}`; error responses use
`{"status":"error","message":"..."}` and a non-200 status.

| Endpoint | Behavior |
| --- | --- |
| `GET /api/lab/v1/manifest?limit=50&cursor=...` | Lists up to 100 completed JPEG/PNG/MP4/WAV/M4A files per page, including eligible recoverable-trash files. |
| `GET /api/lab/v1/hash?file=...&location=live` | Computes a fresh SHA-256 and rechecks generation/active state before returning it. |
| `GET /api/lab/v1/file?file=...&location=live` | Downloads that exact logical file. Supports `Range`, `If-Range`, and `If-Match`; generation changes produce HTTP 412 with `If-Match`. |
| `POST /api/lab/v1/receipt` | Reclaims one matching, verified source. Requires the complete receipt below. |
| `GET /api/lab/v1/auxiliary-cleanup` | Previews eligible IMU JSON and video thumbnails from already-reclaimed captures; requires idle capture state. |
| `POST /api/lab/v1/auxiliary-cleanup` | Explicit user purge using `{ "confirm": true, "cleanup_id": "<preview digest>" }`; rechecks idle state and the exact preview. |
| `POST /api/lab/v1/lease` | Authenticated JSON `{ "ttl_seconds": 180 }` grants/renews an idle lease; integers 0–180 are accepted and zero revokes it. |

Lease responses contain `api_version: 1` and `expires_in_seconds`. Lease traffic
does not update HTTP transfer activity or media progress. The normal two-minute
HTTP idle policy continues to run, but does not stop a hotspot with a valid
lease. If renewals stop, the lease expires within three minutes; the normal idle
check runs every ten seconds. Recent actual transfers can still hold the hotspot
under the ordinary policy. Hotspot stop, reservation/token change, BLE disconnect,
or ASG restart invalidates the lease. The idle monitor never renews authority by
reading lease state. Stock builds retain their existing idle behavior.
On Mentra Live, immediate phone-disconnect revocation uses BES `sr_phble` / 
`phone_ble` presence reports; UART reconnect/renegotiation is not a phone
disconnect. Firmware without these presence reports still has bounded lease
expiry when phone renewals stop.

The manifest has `api_version`, `files`, `has_more`, `next_cursor`,
`recovery_pending`, `retained_auxiliary_files`, and `retained_auxiliary_bytes`.
Auxiliary totals describe the whole live/trash gallery on every page; clients
must not add the same totals once per page. They include retained IMU/RAW and
other non-transferable sidecars, partial artifacts, and generated video
`thumb.jpg` files. Receipt metadata, photo persistence markers (reported through
`recovery_pending`), SDK transient storage, and the separate thumbnail cache are
excluded from auxiliary totals. Each file has `capture_id`, `name`, `location` (`live` or
`trash`), `size`, `modified`, `generation`, `mime_type`, `role`, `download_url`,
`hash_url`, and an optional `request_id`. Generation is the quoted ETag for the
logical path, size, and modification time; content identity is separately checked
with SHA-256. Cursors are opaque `location:name` keys. Restart at the first page
on the next sync pass so new captures are discovered.

Generated `VID_.../thumb.jpg` entries have role `thumbnail`. The companion must
not publish them as ordinary camera photos or send a media receipt for them;
automatic sync retains them. They can be removed by the explicit auxiliary cleanup action.

Paths remain gallery-relative, for example `VID_123/base.mp4` or `IMG_123.jpg`.
The location is a fixed enum, not a filesystem path. Partial files, hidden
metadata/pending files, symbolic-link paths, active video captures, captures
awaiting video integrity validation, and unsettled files are excluded. Photo
writer leases and pending-persistence markers also prevent unfinished JPEG/EXIF
output from being reclaimed. Failed photo persistence retains its marker for
recovery; age alone does not make it eligible.

Hash responses include `file`, `location`, `size`, `generation`, and `sha256`
alongside the manifest metadata. The phone must verify the received bytes and its
persisted phone copy, then submit:

```json
{
  "receipt_id":"phone-live-<stable unique identifier>",
  "file":"VID_123/base.mp4",
  "location":"live",
  "size":123456,
  "generation":"\"<etag>\"",
  "sha256":"<64 lowercase hex characters>"
}
```

`receipt_id` accepts 1–128 ASCII letters, digits, hyphens, or underscores. A
successful receipt returns `receipt_id`, `reclaimed_bytes`, and
`already_reclaimed`. Reuse the same receipt ID and exact fields after a timeout.
Reusing an ID for another file/generation fails. Replaying a completed receipt
cannot delete a new file that happens to have the same name.

## Segmented video preview

Fork builds implementing continuous file rollover advertise
`segmented_recording_version: 1` in capabilities and the authenticated manifest.
The companion must check it before requesting the optional
[segmented recording command](../ASG_CLIENT_API.md#segmented-recording-personal-fork-preview).
The feature remains a preview. A five-minute current-pair trial verified earlier
segment publication and reclamation while capture continued, then automatic Stop
with five fully decodable files. USB remained attached; boundary continuity,
interruption/full-storage recovery, and unplugged endurance remain unproven.

Completed segment rows add `session_id` and zero-based `segment_index`. Both
derive from the unique capture directory identity
`VID_<nonce>_SEG_<base64url-session-id>_<index>/base.mp4`. Each segment is a
separate capture; the active segment does not hide already finalized siblings.
Capture writes `.mp4.partial`, validates closed tracks, syncs the file, and
atomically publishes the completed name before it is eligible for this protocol.
Active, failed, interrupted, and unsettled files remain blocked. This mode does
not generate IMU sidecars or thumbnails; existing captures retain their policy.

The exact-source receipt and publication order is unchanged. The phone persists
session/segment identity with its verified publication before issuing a receipt,
and records the terminal finalized count to avoid declaring a session complete
while its last segment is still pending. Retry restarts manifest enumeration;
repeated receipts and recovered publications must not duplicate segments.

The default glasses payload backlog is 256 MiB with 64 MiB segments. Recording
stops when transfer cannot keep up with this bounded queue. Retained segments
from earlier sessions and quarantined receipt payloads count against the queue;
they are not silently deleted. Completed receipt journals remain for idempotency
and still consume small amounts of disk, so global low-space checks remain
necessary. Verify sustained offload, reclaimed bytes, failure recovery, boundary
continuity, and battery/heat before treating the preview as dependable long video.

## Recovery and storage

The reclaimer verifies readiness, size, generation, and SHA-256 before moving a
source into a private quarantine under `_gallery_metadata/verified_receipts`.
It first persists the exact receipt, atomically moves only that file, verifies
again, persists the verified state, then removes the payload and commits its
completion. File contents and directory entries are synced. A failed durability
operation stops cleanup; the phone must retain its copy and retry.

An authenticated manifest request retries incomplete durable receipts with the
same verification barriers. Thus a process restart after quarantine does not
require the phone to rediscover a now-hidden source. Altered payloads, unknown
journals, missing journal authority, and active captures fail closed. Held files
are counted in `recovery_pending`, which the companion must show as a recovery
problem. Quarantine is never renamed over the original pathname, where a new
capture may now exist. Retained receipt metadata makes duplicate requests safe.

Media receipts alone do not authorize deletion of IMU JSON, RAW/DNG files, or other
non-media sidecars. The separate user-confirmed auxiliary cleanup endpoint can delete
`imu.json` and video `thumb.jpg` only in capture directories whose primary `base.jpg`
or `base.mp4` has a completed receipt and no longer exists. Any unknown member, RAW,
partial, pending marker, active writer, or unsettled file protects the entire directory.
The endpoint never deletes receipts or recovery payloads. Preview/commit responses contain
`api_version: 1`, `cleanup_id`, `files`, `bytes`, `retained_files`, and `deleted`.
The digest covers sorted paths, locations, generations, and content hashes. A changed
preview or busy capture returns HTTP 409; older builds return 404. An interrupted purge
may have removed some extras; fetch another preview instead of replaying blindly.
The companion disables cleanup during photo/audio/video activity, calls, and transfers,
and blocks new app captures/transfers while the request runs. ASG additionally shares
video admission's monitor and holds the photo-persistence barrier through cleanup.
The action permanently discards the selected extras without archiving them.
Personal-fork startup cleanup removes only truly empty capture
directories; nonempty sidecar-only, partial, corrupt-media, and pending-marker
captures survive restarts. Stock startup orphan cleanup is unchanged.
A phone storage limit or failed publication must never generate
a receipt or erase a glasses original.

## Validation

Focused JVM suites cover receipt identity/hash/generation mismatches, active and
settling captures, live/trash isolation, repeated receipts after source replacement,
interrupted quarantine/completion, directory-sync failure, orphan quarantine,
token expiry/reservation changes, and fork-only endpoint authorization. Photo
writer tests cover nested leases and interrupted persistence markers.

Physical acceptance must also measure free space before/after reclamation,
verify the published phone file opens independently, interrupt a transfer, repeat
a receipt after lost response, and confirm other glasses media is unchanged.
No firmware, bootloader, recovery, or partition change is part of this protocol.
