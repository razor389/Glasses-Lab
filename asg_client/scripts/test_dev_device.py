"""Failure-path tests for the host-side app switch. No real ADB calls."""
import copy
import json
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from unittest.mock import patch

import dev_device as dev


class FakeDevice:
    def __init__(self):
        self.calls = []
        self.fail_launch = False
        self.fail_service = False
        self.exit_after_launch = False
        self.home = dev.STOCK + "/.MainActivity"
        self.packages = {
            dev.STOCK: {"versionCode": 302010073, "enabled": 0, "running": True},
            dev.RECOVERY: {"versionCode": 11, "enabled": 1, "running": True},
            dev.LEGACY: None,
            dev.DEV: None,
        }

    def identity(self):
        return {"device": "test-glasses"}

    def package(self, name):
        return copy.deepcopy(self.packages[name])

    def battery(self):
        return {"level": 100, "temperature": 250}

    def check_no_ota(self):
        pass

    def check_camera_idle(self):
        pass

    def set_enabled(self, name, value):
        self.calls.append(("enabled", name, value))
        self.packages[name]["enabled"] = value

    def run(self, *args, **kwargs):
        self.calls.append(args)
        if args[0] == "install":
            self.packages[dev.DEV] = {"versionCode": 1, "enabled": 0, "running": False}
            return "Success"
        if args[0] == "logcat":
            return "development log"
        raise AssertionError(args)

    def shell(self, *args, **kwargs):
        self.calls.append(args)
        if args == ("id",):
            return "uid=0(root)"
        if args[0] == "pidof":
            pkg = self.packages[args[1]]
            if args[1] == dev.DEV and self.exit_after_launch:
                return ""
            return "123" if pkg and pkg["running"] else ""
        if args[:2] == ("am", "force-stop"):
            self.packages[args[2]]["running"] = False
            return ""
        if args[:2] in (("am", "start"), ("am", "start-foreground-service")):
            name = args[args.index("-n") + 1].split("/")[0]
            if name == dev.DEV and self.fail_launch:
                return "Error: launch rejected"
            self.packages[name]["running"] = True
            return "Status: ok"
        if args[:3] == ("cmd", "package", "set-home-activity"):
            self.home = args[-1]
            return "Success"
        if args[0] == "appops":
            return ""
        if args[:3] == ("dumpsys", "activity", "services"):
            if args[-1] == dev.DEV and self.fail_service:
                return "(nothing)"
            service = "RecoveryService" if args[-1] == dev.RECOVERY else "AsgClientService"
            return "  * ServiceRecord{123 u0 package/." + service + "}\n    isForeground=true\n"
        raise AssertionError(args)


class DeviceWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "state.json"
        self.device = FakeDevice()
        self.initial = copy.deepcopy(self.device.packages)
        self.args = Namespace(apk="fork.apk", trial_seconds=10, keep_running=False)

    def snapshot(self, device, path, identity):
        state = {"identity": identity, "status": "prepared", "home": device.home,
                 "backupDir": self.temp.name,
                 "packages": {key: device.package(key) for key in (dev.STOCK, dev.RECOVERY, dev.LEGACY)}}
        dev.write_state(path, state)
        return state

    def install(self, clock=(0, 100)):
        with patch.object(dev, "validate_dev_apk"), patch.object(dev, "digest", return_value="abc"), \
                patch.object(dev, "snapshot", side_effect=self.snapshot), \
                patch.object(dev.time, "monotonic", side_effect=clock), patch.object(dev.time, "sleep"):
            dev.install(self.device, self.path, self.args, self.device.identity())

    def assert_stock_restored(self):
        for name in (dev.STOCK, dev.RECOVERY):
            self.assertEqual(self.device.packages[name], self.initial[name])
        self.assertEqual(self.device.home, dev.STOCK + "/.MainActivity")
        self.assertEqual(self.device.packages[dev.DEV]["enabled"], 3)
        self.assertEqual(json.loads(self.path.read_text())["status"], "restored")
        for call in self.device.calls:
            self.assertNotIn("uninstall", call)
            self.assertNotIn("reboot", call)
            self.assertNotIn("remount", call)

    def test_trial_restores_original_enable_states_and_home(self):
        self.install()
        self.assert_stock_restored()
        recovery_stop = self.device.calls.index(("enabled", dev.RECOVERY, 3))
        stock_stop = self.device.calls.index(("enabled", dev.STOCK, 3))
        self.assertLess(recovery_stop, stock_stop)

    def test_launch_failure_restores_stock(self):
        self.device.fail_launch = True
        with self.assertRaisesRegex(RuntimeError, "launch rejected"):
            self.install()
        self.assert_stock_restored()

    def test_missing_foreground_service_restores_stock(self):
        self.device.fail_service = True
        with self.assertRaisesRegex(RuntimeError, "Foreground service did not start"):
            self.install()
        self.assert_stock_restored()
        self.assertTrue((self.path.parent / "last-trial-services.txt").exists())

    def test_sleeping_display_does_not_require_activity_first_draw(self):
        self.install()
        starts = [call for call in self.device.calls if call[:2] == ("am", "start")]
        self.assertTrue(starts)
        self.assertTrue(all("-W" not in call for call in starts))
        self.assertTrue(any(call[:2] == ("am", "start-foreground-service")
                            for call in self.device.calls))

    def test_other_foreground_service_cannot_mask_missing_asg_foreground(self):
        dump = ("  * ServiceRecord{123 u0 package/.AsgClientService}\n    isForeground=false\n"
                "  * ServiceRecord{456 u0 package/.OtaService}\n    isForeground=true\n")
        self.assertFalse(dev.service_is_foreground(dump, "AsgClientService"))

    def test_process_exit_during_trial_restores_stock(self):
        self.device.exit_after_launch = True
        with self.assertRaisesRegex(RuntimeError, "process exited"):
            self.install(clock=(0, 1))
        self.assert_stock_restored()

    def test_keep_running_requires_explicit_option(self):
        self.args.keep_running = True
        self.install()
        self.assertEqual(self.device.home, dev.DEV + "/" + dev.MAIN)
        self.assertEqual(json.loads(self.path.read_text())["status"], "active")

    def test_restore_refuses_a_different_device_before_changes(self):
        self.snapshot(self.device, self.path, {"device": "different"})
        with self.assertRaisesRegex(RuntimeError, "identity"):
            dev.restore(self.device, self.path)
        self.assertEqual(self.device.calls, [])

    def test_wrong_apk_is_rejected_before_device_changes(self):
        with patch.object(dev, "apk_manifest", return_value=[("manifest", {"package": dev.STOCK})]):
            with self.assertRaisesRegex(ValueError, "independent"):
                dev.install(self.device, self.path, self.args, self.device.identity())
        self.assertEqual(self.device.calls, [])

    def test_active_camera_blocks_switch_before_snapshot(self):
        with patch.object(dev, "validate_dev_apk"), \
                patch.object(self.device, "check_camera_idle", side_effect=RuntimeError("Camera is active")), \
                patch.object(dev, "snapshot") as snapshot:
            with self.assertRaisesRegex(RuntimeError, "Camera is active"):
                dev.install(self.device, self.path, self.args, self.device.identity())
            snapshot.assert_not_called()
        self.assertFalse(any(call[0] in ("install", "enabled") for call in self.device.calls))

    def test_stock_permission_collision_is_rejected(self):
        nodes = [("manifest", {"package": dev.DEV, "versionName": "test-dev"}),
                 ("meta-data", {"name": dev.STOCK + ".DEVELOPMENT_BUILD", "value": True}),
                 ("permission", {"name": dev.STOCK + ".permission.RECOVERY_TELEMETRY"})]
        with patch.object(dev, "apk_manifest", return_value=nodes):
            with self.assertRaisesRegex(ValueError, "permission owned by stock"):
                dev.validate_dev_apk("fork.apk")


if __name__ == "__main__":
    unittest.main()
