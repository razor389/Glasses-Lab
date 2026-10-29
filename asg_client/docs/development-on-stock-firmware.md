# Customize Mentra Live on stock firmware

Build `asg_client` as `com.mentra.asg_client.thirdparty` and switch between it and
the stock ASG app over USB. This changes the Android application running on the
glasses. It does not replace MTK Android firmware, BES firmware, or the bootloader.

## Build on Windows

Requirements: Python 3.11+, Java 17 JDK, Android SDK platform 34 and build-tools
34.0.0. StreamPackLite must be checked out. Commands run from the repository root.

```powershell
./asg_client/scripts/build-dev.ps1 -JavaHome 'C:\path\to\jdk17' -AndroidSdkRoot 'C:\path\to\android-sdk' -RunTests
```

If `local/asg-dev/toolchain.json` contains `javaHome` and `androidSdkRoot`, use:

```powershell
./asg_client/scripts/build-dev.ps1 -RunTests
```

The script forces the third-party package even on machines with Mentra signing
credentials. It keeps a stable development signing key and build caches under
the ignored `local/asg-dev/` directory. Keep `debug.keystore`: subsequent builds
need that key to update the fork without deleting its app data. The script
verifies the APK signature and never calls ADB.

`-RunTests` runs development safety, OTA command, factory reset, and system
controller suites. Use `-RunTests -TestFilter '*'` for the entire ASG JVM suite.
Output: `asg_client/app/build/outputs/apk/debug/app-debug.apk`.

On other host OSes, use the Gradle wrapper with `-PASG_THIRD_PARTY=true` and a
stable debug keystore. The Python device tool works on Windows, macOS, and Linux.

## Inspect and trial

Use USB, close any recording/stream, and avoid starting a phone update during a
switch. Obtain the USB serial from `adb devices -l`.

```powershell
$deviceSerial = 'YOUR_USB_SERIAL'
python asg_client/scripts/dev_device.py inspect --serial $deviceSerial
python asg_client/scripts/dev_device.py install --serial $deviceSerial
```

Pass `--adb 'C:\path\to\adb.exe'` if needed. The default checks PATH, then
`C:\platform-tools\adb.exe`. The device must already provide root ADB; the tool
does not restart ADB, unlock the device, or change debugging settings.

If the root check reports a shell user (`uid=2000`) on these root-capable glasses,
run `adb -s YOUR_GLASSES_SERIAL root`, then confirm
`adb -s YOUR_GLASSES_SERIAL shell id` reports `uid=0` before retrying. This
restarts the glasses' ADB daemon only; it does not unlock the bootloader. Target
the glasses explicitly, especially when the phone is also connected.

Before switching, the tool verifies the APK's package and development marker,
checks battery/temperature and unfinished OTA sessions, and saves verified copies
of the installed stock/recovery APKs with their versions and SHA-256 hashes. It
records package enabled states and the current home activity. State is bound to
the glasses' BES MAC and firmware, rather than the often-generic USB serial.

The tool installs the fork, disables stock recovery before stock ASG, and starts
the fork's foreground service directly, so startup works with the display asleep.
Its default 60-second trial checks process/foreground-service survival
and temperature, saves logs, then restores stock automatically. `--trial-seconds`
accepts 10–300 seconds. This smoke check does not establish camera, audio, phone
pairing, or sleep/wake correctness: test those on the actual glasses.

## Leave a tested build active and iterate

```powershell
python asg_client/scripts/dev_device.py install --serial $deviceSerial --keep-running
```

After observation, this leaves the fork as the home app. Stock ASG and recovery
remain installed, with data preserved, but disabled. Rebuilding and running this
command again restores the previous stock baseline first and then updates the
fork using `adb install -r`, preserving its own app data.

Snapshots, state, and trial logs are under `local/asg-dev/devices/<BES-MAC>/`.
Keep this directory until stock is restored. Treat it as private device data.

## Restore stock

```powershell
python asg_client/scripts/dev_device.py restore --serial $deviceSerial
```

Restoration stops and disables the fork, restores saved enabled states and home,
and starts stock ASG and the previously running recovery worker. It retains
development app data and never downloads a newer stock APK. The shell wrappers
`dev-setup.sh` and `restore-stock.sh` use this tool and require `--serial`.

Exceptions and Ctrl+C during a trial attempt restoration. A disconnected cable
or terminated host process can prevent that attempt; reconnect and run `restore`.
The state file is saved before any package changes. If the state file is lost,
stock packages are still installed and can be re-enabled over ADB. The APK
backups are app backups, not full system/firmware backups.

## Development safeguards and customization points

`DevelopmentSafety` gates maintenance by the running package identity. Forks
cannot use existing OTA/reset paths to install APKs, flash MTK/BES, overwrite
stock recovery backups, launch/deploy stock recovery, or inject privileged shell
commands. The fork leaves ADB configuration unchanged. These safeguards live
in source; they are not a sandbox that makes arbitrary new code safe.

Personal fork builds also omit Sentry credentials, disable source/symbol uploads
to Mentra's project, and reject remote crash-report initialization. Local
file/console diagnostics remain available. Explicit local photo, gallery, and
streaming commands do not require a Mentra account.

Development photos/videos use `/storage/emulated/0/asg_media.thirdparty`, separate
from stock `/storage/emulated/0/asg_media`. Gallery sync/deletion operates on that
separate library. Camera/microphone runtime permissions and storage/overlay app
ops are granted to the fork during installation.

Useful places to begin editing, under `app/src/main/java/com/mentra/asg_client/`:

| Behavior | Source |
| --- | --- |
| Camera-button actions | `service/core/handlers/K900CommandHandler.java`, `service/core/handlers/subscribers/ButtonEventSubscriber.java` |
| Capture/recording | `camera/`, `io/media/core/MediaCaptureService.java` |
| Camera tuning and timings | `AsgConstants.java`, `camera/policy/` |
| Device sounds | `audio/` and bundled audio assets |
| Phone commands | `service/core/handlers/` |
| Streaming | `io/streaming/` |
| LEDs and peripherals | `hardware/`, `io/hardware/` |

Keep thermal limits, recording cleanup, and maintenance safeguards while
experimenting. BLE radio behavior, low-level audio routing, and hardware button
classification also depend on BES firmware; application changes cannot replace
those firmware components.

## Validation on stock Mentra Live

Tested on Android 11/API 30, arm64, MTK `MentraLive_20260923.0`, BES `26.9.29.0`,
stock ASG 3.2.1 (302010073), and recovery 1.2.0. This device already had root ADB
and permissive SELinux; other firmware or permission configurations need their
own compatibility checks.

The signed development APK built successfully and passed 26 focused Android
unit tests. A 60-second USB trial maintained the foreground service, exchanged
BES version/capability messages, and established the UART link at 1,152,000 baud.
Stock ASG and recovery then resumed with their original APK hashes, enabled
states, and home activity. Verified boot remained green and the bootloader locked.
A subsequent five-minute trial connected the standalone `companion_android`
app on a Galaxy A14 5G (Android 15), reported the development ASG version and
battery level, and delivered a photo over BLE to the phone's private storage
and preview. The camera was idle afterward. Phone disconnect/reconnect and
rotation also worked, and the trial automatically restored stock ASG/recovery.
Later trials recorded an 18.5-second WAV from the glasses microphone over BLE and
a 49-second 720p H.264 video with a non-silent AAC soundtrack. The camera was idle
after stopping. The user also confirmed both directions of a normal phone call
through the glasses. Phone Wi-Fi video download, in-app playback, volume controls,
hardware button events, and sleep/wake still need device tests. See the
[companion guide](../../companion_android/README.md) for recording controls,
limits, and the remaining validation steps.
