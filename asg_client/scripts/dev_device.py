#!/usr/bin/env python3
"""Reversible ASG development trials. Never flash, remount, reboot, or uninstall stock."""

import argparse
import hashlib
import json
import re
import shlex
import shutil
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

STOCK = "com.mentra.asg_client"
DEV = STOCK + ".thirdparty"
RECOVERY = "com.mentra.recovery"
LEGACY = "com.augmentos.otaupdater"
MAIN = "com.mentra.asg_client.MainActivity"
SERVICE = "com.mentra.asg_client.service.core.AsgClientService"
REPO = Path(__file__).resolve().parents[2]
DEFAULT_STATE = REPO / "local/asg-dev/devices"


def apk_manifest(path):
    """Read binary Android XML without executing the APK or needing an Android SDK."""
    with zipfile.ZipFile(path) as archive:
        data = archive.read("AndroidManifest.xml")
    u16 = lambda p: struct.unpack_from("<H", data, p)[0]
    u32 = lambda p: struct.unpack_from("<I", data, p)[0]
    strings, nodes = [], []
    offset = 8
    while offset < len(data):
        kind, header_size, size = struct.unpack_from("<HHI", data, offset)
        if size < 8 or offset + size > len(data):
            raise ValueError("Invalid APK manifest chunk")
        if kind == 1:
            count, _, flags, start = struct.unpack_from("<IIII", data, offset + 8)
            for i in range(count):
                p = offset + start + u32(offset + header_size + 4 * i)
                if flags & 256:
                    p += 2 if data[p] & 128 else 1
                    length = data[p]
                    p += 1
                    if length & 128:
                        length = ((length & 127) << 8) | data[p]
                        p += 1
                    strings.append(data[p:p + length].decode("utf-8"))
                else:
                    length = u16(p)
                    p += 2
                    if length & 32768:
                        length = ((length & 32767) << 16) | u16(p)
                        p += 2
                    strings.append(data[p:p + length * 2].decode("utf-16-le"))
        elif kind == 258:
            tag = strings[u32(offset + 20)]
            start, width, count = struct.unpack_from("<HHH", data, offset + 24)
            attrs = {}
            for i in range(count):
                p = offset + 16 + start + width * i
                name, raw = u32(p + 4), u32(p + 8)
                value_type, value = data[p + 15], u32(p + 16)
                if raw != 0xFFFFFFFF:
                    value = strings[raw]
                elif value_type == 3:
                    value = strings[value]
                elif value_type == 18:
                    value = bool(value)
                attrs[strings[name]] = value
            nodes.append((tag, attrs))
        offset += size
    return nodes


def apk_identity(path):
    return next(attrs for tag, attrs in apk_manifest(path) if tag == "manifest")


def validate_dev_apk(path):
    nodes = apk_manifest(path)
    identity = next(attrs for tag, attrs in nodes if tag == "manifest")
    if identity.get("package") != DEV or identity.get("sharedUserId"):
        raise ValueError("Refusing APK: expected independent .thirdparty package")
    if not str(identity.get("versionName", "")).endswith("-dev"):
        raise ValueError("Development versionName must end in -dev so the phone can identify it")
    marker = any(tag == "meta-data" and attrs.get("name") == STOCK + ".DEVELOPMENT_BUILD"
                 and attrs.get("value") in (True, "true") for tag, attrs in nodes)
    if not marker:
        raise ValueError("APK lacks the development-build marker; rebuild with build-dev.ps1")
    for tag, attrs in nodes:
        if tag == "permission" and str(attrs.get("name", "")).startswith(STOCK + "."):
            if not attrs["name"].startswith(DEV + "."):
                raise ValueError("APK declares a permission owned by stock: " + attrs["name"])
        if tag == "provider" and not str(attrs.get("authorities", "")).startswith(DEV + "."):
            raise ValueError("APK provider authority is not isolated: " + str(attrs))
    return identity


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


class Device:
    def __init__(self, adb, serial):
        self.adb, self.serial = adb, serial

    def run(self, *args, check=True, timeout=60):
        result = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=timeout)
        if result.returncode and (check or result.stderr.strip()):
            raise RuntimeError(f"ADB {args!r} failed: {result.stdout} {result.stderr}")
        return result.stdout.strip()

    def shell(self, *args, **kwargs):
        return self.run("shell", shlex.join(str(arg) for arg in args), **kwargs)

    def identity(self):
        if self.run("get-state") != "device":
            raise RuntimeError("Selected device is not online")
        identity = {key: self.shell("getprop", key) for key in
                    ("ro.product.device", "ro.build.fingerprint", "ro.custom.ota.version",
                     "persist.mentra.live.mac")}
        if "mentralive" not in identity["ro.product.device"].lower():
            raise RuntimeError("This tool only supports Mentra Live")
        if not re.fullmatch(r"(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}", identity["persist.mentra.live.mac"]):
            raise RuntimeError("Cannot identify glasses uniquely: BES MAC property is missing")
        return identity

    def package(self, name):
        paths = [line[8:] for line in self.shell("pm", "path", name, check=False).splitlines()
                 if line.startswith("package:")]
        if not paths:
            return None
        dump = self.shell("dumpsys", "package", name)
        enabled = re.search(r"User 0:.*?\benabled=(\d+)", dump)
        version = re.search(r"\bversionCode=(\d+)", dump)
        if not enabled or not version:
            raise RuntimeError("Cannot read package state for " + name)
        return {"paths": paths, "enabled": int(enabled[1]), "versionCode": int(version[1]),
                "running": bool(self.shell("pidof", name, check=False))}

    def set_enabled(self, name, value):
        command = {0: "default-state", 1: "enable", 2: "disable", 3: "disable-user",
                   4: "disable-until-used"}[value]
        self.shell("pm", command, "--user", "0", name)
        if self.package(name)["enabled"] != value:
            raise RuntimeError("Package enabled-state verification failed: " + name)

    def battery(self):
        dump = self.shell("dumpsys", "battery")
        return {key: int(re.search(r"^\s*" + key + r": (\d+)", dump, re.M)[1])
                for key in ("level", "temperature")}

    def check_no_ota(self):
        path = "/data/user/0/" + STOCK + "/shared_prefs/ota_session.xml"
        raw = self.shell("sh", "-c", 'if [ -f "$1" ]; then cat "$1"; else echo MISSING; fi',
                         "sh", path)
        if raw == "MISSING":
            return
        for element in ET.fromstring(raw):
            if element.get("name") == "ota_session_data":
                session = json.loads(element.text)
                if session.get("session_id") and session.get("status") not in ("idle", "failed", "complete"):
                    raise RuntimeError("Stock has an unfinished OTA session. Finish it before switching apps.")

    def check_camera_idle(self):
        camera = self.shell("dumpsys", "media.camera")
        if not re.search(r"Active Camera Clients:\s*\[\s*\]", camera):
            raise RuntimeError("Camera is active or its state is unknown. Stop capture/streaming before switching.")


def write_state(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def assert_same_device(device, state):
    if device.identity() != state["identity"]:
        raise RuntimeError("Device identity/firmware differs from the saved state; refusing restoration")


def start_asg(device, package):
    # These glasses normally have a sleeping display. Activity first-draw waits (-W)
    # can time out without invoking onCreate; start the service independently.
    output = device.shell("am", "start-foreground-service", "-n", package + "/" + SERVICE,
                          "-a", "MY_ACTION_START_FOREGROUND_SERVICE")
    if "Error" in output:
        raise RuntimeError(output)
    output = device.shell("am", "start", "-n", package + "/" + MAIN)
    if "Error" in output:
        raise RuntimeError(output)
    wait_for_service(device, package, "AsgClientService")


def wait_for_service(device, package, service):
    for _ in range(30):
        dump = device.shell("dumpsys", "activity", "services", package)
        if service_is_foreground(dump, service):
            return
        time.sleep(1)
    raise RuntimeError("Foreground service did not start: " + package + "/" + service)


def service_is_foreground(dump, service):
    blocks = re.split(r"(?m)(?=^\s*\* ServiceRecord\{)", dump)
    for block in blocks:
        lines = block.strip().splitlines()
        if lines and lines[0].startswith("* ServiceRecord{") and service in lines[0]:
            if "isForeground=true" in block:
                return True
    return False


def save_diagnostics(device, path):
    services = device.shell("dumpsys", "activity", "services", DEV)
    (path.parent / "last-trial-services.txt").write_text(services, encoding="utf-8")
    pid = device.shell("pidof", DEV, check=False).split()
    log = "Development process is not running; no process-scoped log could be captured."
    if pid:
        log = device.run("logcat", "-d", "-t", "3000", "--pid=" + pid[0], timeout=30)
    (path.parent / "last-trial.log").write_text(log, encoding="utf-8")
    return services


def restore(device, path):
    state = json.loads(path.read_text(encoding="utf-8"))
    assert_same_device(device, state)
    # Stop the fork first so two processes can never own the serial port during restoration.
    if device.package(DEV):
        device.shell("am", "force-stop", DEV)
        device.set_enabled(DEV, 3)
    for name, original in state["packages"].items():
        if original:
            installed = device.package(name)
            if not installed or installed["versionCode"] != original["versionCode"]:
                raise RuntimeError("Stock package changed unexpectedly; saved APKs are in " + state["backupDir"])
            device.set_enabled(name, original["enabled"])
    device.shell("cmd", "package", "set-home-activity", "--user", "0", state["home"])
    start_asg(device, STOCK)
    original_recovery = state["packages"].get(RECOVERY)
    if original_recovery and original_recovery["running"]:
        output = device.shell("am", "start", "-n", RECOVERY + "/.ui.LauncherActivity")
        if "Error" in output:
            raise RuntimeError(output)
        wait_for_service(device, RECOVERY, "RecoveryService")
    if not device.shell("pidof", STOCK, check=False):
        raise RuntimeError("Stock was enabled but is not running; restoration needs attention")
    state["status"] = "restored"
    write_state(path, state)
    print("Stock app and saved package states restored. Development app/data retained, disabled.", flush=True)


def snapshot(device, path, identity):
    packages = {name: device.package(name) for name in (STOCK, RECOVERY, LEGACY)}
    if not packages[STOCK] or packages[STOCK]["enabled"] not in (0, 1):
        raise RuntimeError("Start from an enabled stock ASG installation; restore the previous trial first")
    home_lines = device.shell("cmd", "package", "resolve-activity", "--brief", "-a",
                              "android.intent.action.MAIN", "-c", "android.intent.category.HOME").splitlines()
    home = home_lines[-1] if home_lines else ""
    if not home.startswith(STOCK + "/"):
        raise RuntimeError("Expected stock ASG as the current home activity")
    backup_dir = path.parent / ("backup-" + time.strftime("%Y%m%d-%H%M%S"))
    backup_dir.mkdir(parents=True, exist_ok=False)
    for name, package in packages.items():
        if not package:
            continue
        if len(package["paths"]) != 1:
            raise RuntimeError("Split stock APKs need a separate backup procedure")
        target = backup_dir / (name + ".apk")
        device.run("pull", package["paths"][0], str(target), timeout=180)
        apk = apk_identity(target)
        if apk.get("package") != name or apk.get("versionCode") != package["versionCode"]:
            raise RuntimeError("Backup package/version verification failed: " + name)
        package["sha256"] = digest(target)
    state = {"identity": identity, "packages": packages, "home": home,
             "backupDir": str(backup_dir.resolve()), "status": "prepared"}
    write_state(path, state)
    print("Verified APK backups and restoration state saved in " + str(backup_dir), flush=True)
    return state


def install(device, path, args, identity):
    apk = Path(args.apk).resolve()
    validate_dev_apk(apk)
    if "uid=0" not in device.shell("id"):
        raise RuntimeError("This workflow requires the already-available root ADB connection")
    battery = device.battery()
    if battery["level"] < 30 or battery["temperature"] >= 430:
        raise RuntimeError("Wait for at least 30% battery and a temperature below 43 C")
    device.check_no_ota()
    device.check_camera_idle()
    if path.exists() and json.loads(path.read_text(encoding="utf-8"))["status"] != "restored":
        restore(device, path)
    state = snapshot(device, path, identity)
    try:
        # A disabled previous fork stays stopped across replacement broadcasts.
        if device.package(DEV):
            device.shell("am", "force-stop", DEV)
            device.set_enabled(DEV, 3)
        result = device.run("install", "-r", "-g", str(apk), timeout=180)
        if "Success" not in result:
            raise RuntimeError(result)
        # Stop watchdogs before the application they monitor. Preserve their APKs and data.
        for name in (RECOVERY, LEGACY, STOCK):
            if state["packages"][name]:
                device.set_enabled(name, 3)
                device.shell("am", "force-stop", name)
        for name in (STOCK, RECOVERY, LEGACY):
            if device.shell("pidof", name, check=False):
                raise RuntimeError("Stock process is still running: " + name)
        device.set_enabled(DEV, 1)
        device.shell("appops", "set", DEV, "MANAGE_EXTERNAL_STORAGE", "allow")
        device.shell("appops", "set", DEV, "SYSTEM_ALERT_WINDOW", "allow")
        start_asg(device, DEV)
        state["status"] = "active"
        state["developmentApkSha256"] = digest(apk)
        write_state(path, state)
        print(f"Development app running; observing for {args.trial_seconds} seconds.", flush=True)
        original_pid = device.shell("pidof", DEV, check=False)
        if not original_pid:
            raise RuntimeError("Development process exited")
        deadline = time.monotonic() + args.trial_seconds
        while time.monotonic() < deadline:
            time.sleep(2)
            current_pid = device.shell("pidof", DEV, check=False)
            if not current_pid:
                raise RuntimeError("Development process exited")
            if current_pid != original_pid:
                raise RuntimeError("Development process restarted during the trial")
            if device.battery()["temperature"] >= 430:
                raise RuntimeError("Device reached 43 C; ending trial")
        services = save_diagnostics(device, path)
        if not service_is_foreground(services, "AsgClientService"):
            raise RuntimeError("Development foreground service did not remain active")
        if args.keep_running:
            device.shell("cmd", "package", "set-home-activity", "--user", "0", DEV + "/" + MAIN)
            print("Fork left active as home. Use restore to return to stock; logs saved beside state.json.", flush=True)
            return
    except BaseException:
        try:
            save_diagnostics(device, path)
        except Exception as error:
            print("Could not save trial diagnostics: " + str(error), file=sys.stderr)
        try:
            restore(device, path)
        except Exception as error:
            print("Automatic restore failed: " + str(error) + "\nReconnect USB and run restore.", file=sys.stderr)
        raise
    restore(device, path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("inspect", "install", "restore"):
        command = commands.add_parser(name)
        command.add_argument("--adb", default=shutil.which("adb") or "C:/platform-tools/adb.exe")
        command.add_argument("--serial", required=True, help="Explicit USB ADB serial (no TCP connections)")
        command.add_argument("--state-dir", type=Path, default=DEFAULT_STATE)
        if name == "install":
            command.add_argument("--apk", default=str(REPO / "asg_client/app/build/outputs/apk/debug/app-debug.apk"))
            command.add_argument("--trial-seconds", type=int, choices=range(10, 301), default=60, metavar="10..300")
            command.add_argument("--keep-running", action="store_true", help="Leave fork active after observation")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.serial):
        parser.error("Use a USB serial, not a network ADB endpoint")
    device = Device(args.adb, args.serial)
    identity = device.identity()
    key = identity["persist.mentra.live.mac"].replace(":", "").lower()
    path = args.state_dir.resolve() / key / "state.json"
    if args.command == "inspect":
        print(json.dumps({"identity": identity, "battery": device.battery(), "stateFile": str(path),
                          "packages": {name: device.package(name) for name in (STOCK, RECOVERY, DEV)}}, indent=2))
    elif args.command == "restore":
        restore(device, path)
    else:
        install(device, path, args, identity)


if __name__ == "__main__":
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        print("Stopped: " + str(error), file=sys.stderr)
        sys.exit(1)
