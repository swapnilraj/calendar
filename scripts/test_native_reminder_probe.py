"""Pure host refusal tests. No adb, emulator, Gradle or APK installation is invoked."""
import copy
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("reminder_probe_host", Path(__file__).with_name("run-native-reminder-probe.py"))
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)
RUN = "77777777-7777-4777-8777-777777777777"
PAIR = {"app": {"sha256": "a" * 64}, "test": {"sha256": "b" * 64}}


def report():
    identities = {key: {"package": pkg, "versionCode": 1 if key == "app" else 0,
                       "baseSha256": PAIR[key]["sha256"], "signers": [host.SIGNER], "splits": []}
                  for key, pkg in (("app", host.APP), ("test", host.TEST))}
    return {"schema": 2, "scope": "UNBOUND_NO_DATA_OR_DELIVERY", "status": "PASS", "runId": RUN,
            "productionProfileActivation": "NONE", "googleWorkspaceRoundTrip": "NOT_RUN", "hostBootPin": host.BOOT, "targetUid": 10001, "applicationEnabledSetting": 0,
            "targetProcess": {"pid": 12345, "startElapsedRealtime": 100, "name": host.APP},
            "appIdentity": identities["app"], "testIdentity": identities["test"], "providerIdentity": copy.deepcopy(host.PROVIDER),
            "components": [{"package": host.APP, "class": name, "kind": kind, "enabled": enabled,
                            "exported": exported, "process": host.APP, "enabledSetting": 0} for name, kind, enabled, exported in host.COMPONENTS],
            "startup": {"state": "UNBOUND", "runtimeGetterCalls": 2, "configurationAttempts": 0, "operationAttempts": 0,
                        "retryAttempts": 0, "registrationAttempts": 0, "cancellationAttempts": 0, "health": "UNKNOWN", "inFlight": 0,
                        "callers": {"NATIVE": 2, "RECOVERY": 0, "ALARM": 0, "ACTION": 0, "JOB": 0, "UNKNOWN": 0},
                        "retryRefusals": 0, "suppressionRefusals": 0, "unexpectedDeliveryAttempts": 0},
            "capabilities": {"readCalendar": False, "writeCalendar": False, "postNotifications": False,
                             "notificationsEnabled": True, "exactAlarmAccess": False, "channelPresent": False,
                             "channelEnabled": None, "ownJobCount": 0, "ownNotificationCount": 0},
            "constraints": {"state": "UNBOUND", "providerDataAccessAvailable": False, "privateStoreAccessAvailable": False,
                            "fixtureModesAvailable": False, "cleanupAvailable": False, "permissionCommandsAvailable": False,
                            "nativeJobSchedulingAvailable": False, "deliveryAvailable": False, "settlementSeconds": 5,
                            "deadlineKind": "cooperative", "deadlineSeconds": 120, "componentCap": 18, "ownResourceCap": 16}}


class IdentityReportTest(unittest.TestCase):
    def test_no_callback_unbound_identity_can_pass_without_permission_or_channel(self):
        value = report()
        self.assertIs(value, host.require_report(value, RUN, PAIR))

    def test_wrong_schema_mode_run_boot_or_claim_is_refused(self):
        for key, replacement in (("schema", True), ("scope", "fixtures"), ("status", "STAGED"), ("runId", host.BOOT),
                                 ("hostBootPin", RUN), ("productionProfileActivation", "ENABLED"), ("googleWorkspaceRoundTrip", "PASS")):
            value = report(); value[key] = replacement
            with self.subTest(key=key), self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_unknown_or_missing_report_fields_do_not_gain_authority(self):
        for change in (lambda v: v.update(unknown=True), lambda v: v.pop("constraints")):
            value = report(); change(value)
            with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_real_app_package_other_uid_process_and_boolean_pid_are_refused(self):
        for key, replacement in (("name", "dev.swapnilraj.calendar"), ("pid", True), ("pid", 0), ("startElapsedRealtime", False)):
            value = report(); value["targetProcess"][key] = replacement
            with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_numeric_user0_target_uid_is_required_for_later_ownership_binding(self):
        for uid in (True, False, 9999, 100000, -1, "10001", None):
            value = report(); value["targetUid"] = uid
            with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
        value = report(); value.pop("targetUid")
        with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
        for uid in (10000, 99999):
            value = report(); value["targetUid"] = uid
            host.require_report(value, RUN, PAIR)

    def test_installed_pair_and_signers_are_exact(self):
        for key in ("appIdentity", "testIdentity"):
            for field, replacement in (("package", "dev.swapnilraj.calendar"), ("baseSha256", "c" * 64),
                                       ("signers", ["c" * 64]), ("versionCode", True), ("splits", [{}])):
                value = report(); value[key][field] = replacement
                with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_provider_metadata_must_match_complete_pinned_descriptor(self):
        for change in (lambda v: v.update(sdk=34), lambda v: v.update(baseSha256="c" * 64),
                       lambda v: v.update(signingHistory=[]), lambda v: v.update(unknown=True)):
            value = report(); change(value["providerIdentity"])
            with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_every_unbound_callback_or_job_attempt_refuses_quiet_identity(self):
        for field in ("configurationAttempts", "operationAttempts", "retryAttempts", "registrationAttempts", "cancellationAttempts"):
            for replacement in (1, False):
                value = report(); value["startup"][field] = replacement
                with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
        for health in ("READY", "DISABLED", "UNAVAILABLE", "DEGRADED"):
            value = report(); value["startup"]["health"] = health
            with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_component_enablement_package_and_private_receiver_are_bound(self):
        for row_index, field, replacement in ((0, "package", "dev.swapnilraj.calendar"), (0, "exported", True),
                                               (3, "enabled", False), (4, "enabled", True), (0, "process", "other"),
                                               (0, "enabled", 1)):
            value = report(); value["components"][row_index][field] = replacement
            with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
        value = report(); value["components"].pop()
        with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_persisted_application_and_component_overrides_are_not_manifest_default(self):
        invalid = (False, True, 1, 2, 3, 4, -1, None, "0", 0.0)
        for setting in invalid:
            value = report(); value["applicationEnabledSetting"] = setting
            with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
            for index in range(len(host.COMPONENTS)):
                value = report(); value["components"][index]["enabledSetting"] = setting
                with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
        value = report(); value.pop("applicationEnabledSetting")
        with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
        value = report(); value["components"][0].pop("enabledSetting")
        with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_both_inherited_tooling_activities_must_remain_disabled(self):
        expected = ["androidx.compose.ui.tooling.PreviewActivity", "androidx.activity.ComponentActivity"]
        indices = [i for i, row in enumerate(report()["components"]) if row["class"] in expected]
        self.assertEqual(expected, [report()["components"][i]["class"] for i in indices])
        for index in indices:
            value = report(); value["components"][index]["enabled"] = True
            with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
        value = report(); value["constraints"]["componentCap"] = 14
        with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_inherited_startup_provider_and_profile_receiver_cannot_run(self):
        expected = [("androidx.startup.InitializationProvider", "provider"),
                    ("androidx.profileinstaller.ProfileInstallReceiver", "receiver")]
        rows = report()["components"]
        self.assertEqual(expected, [(row["class"], row["kind"]) for row in rows[-2:]])
        for index in (-2, -1):
            for field, setting in (("enabled", True), ("enabledSetting", 1), ("enabledSetting", False)):
                value = report(); value["components"][index][field] = setting
                with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
        value = report(); value["components"][-2]["kind"] = "receiver"
        with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
        value = report(); value["constraints"]["componentCap"] = 16
        with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_capability_types_channel_absence_and_resource_counts_are_strict(self):
        for key, replacement in (("ownJobCount", 1), ("ownNotificationCount", True), ("readCalendar", 0),
                                 ("channelEnabled", True), ("channelPresent", True)):
            value = report(); value["capabilities"][key] = replacement
            with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
        value = report(); value["capabilities"].update(channelPresent=True, channelEnabled=False)
        with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_schema1_is_never_reinterpreted_as_schema2(self):
        value = report(); value["schema"] = 1
        with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_bounded_attributed_recovery_preserves_truthful_degraded_health(self):
        value = report(); state = value["startup"]
        state.update(runtimeGetterCalls=3, configurationAttempts=1, operationAttempts=1, suppressionRefusals=1,
                     retryAttempts=1, retryRefusals=1, health="DEGRADED")
        state["callers"]["RECOVERY"] = 1
        host.require_report(value, RUN, PAIR)
        state.update(health="UNAVAILABLE", retryAttempts=0, retryRefusals=0)
        host.require_report(value, RUN, PAIR)

    def test_callback_origin_settlement_health_and_refusal_proof_are_mandatory(self):
        def base():
            value = report(); s = value["startup"]
            s.update(runtimeGetterCalls=3, configurationAttempts=1, retryAttempts=1, retryRefusals=1, health="DEGRADED")
            s["callers"]["RECOVERY"] = 1
            return value
        for kind in ("ALARM", "ACTION", "JOB", "UNKNOWN"):
            value = base(); value["startup"]["callers"][kind] = 1
            with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
        for field, replacement in (("inFlight", 1), ("configurationAttempts", 0), ("retryRefusals", 0),
                                   ("unexpectedDeliveryAttempts", 1), ("health", "READY"), ("health", "DISABLED"),
                                   ("health", "UNKNOWN"), ("retryRefusals", False)):
            value = base(); value["startup"][field] = replacement
            with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)
        value = base(); value["startup"]["callers"]["RECOVERY"] = 9
        with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)

    def test_identity_never_admits_store_provider_fixture_or_cleanup_modes(self):
        for key in ("providerDataAccessAvailable", "privateStoreAccessAvailable", "fixtureModesAvailable", "cleanupAvailable", "permissionCommandsAvailable", "nativeJobSchedulingAvailable", "deliveryAvailable"):
            value = report(); value["constraints"][key] = True
            with self.assertRaises(host.Refusal): host.require_report(value, RUN, PAIR)


class HostBoundaryTest(unittest.TestCase):
    def test_read_surface_cannot_run_install_grants_shell_settings_or_stop(self):
        adb = host.Adb("adb", host.SERIAL)
        with patch.object(host.subprocess, "run", side_effect=AssertionError("must not execute")):
            for command in (["install", "x.apk"], ["pm", "grant", host.APP, "permission"], ["am", "force-stop", host.APP],
                            ["settings", "put", "global", "http_proxy", "x"], ["content", "query"]):
                with self.assertRaises(host.Refusal): adb.read(command, True)
        self.assertFalse(hasattr(adb, "mutation"))

    def test_physical_or_different_emulator_serial_is_rejected(self):
        for value in ("device-123", "emulator-5556", "", host.SERIAL + " shell"):
            with self.assertRaises(host.Refusal): host.Adb("adb", value)

    def test_preexisting_target_never_gets_stopped(self):
        adb = host.Adb("adb", host.SERIAL)
        result = SimpleNamespace(returncode=0, stdout=b"12345\n", stderr=b"")
        with patch.object(adb, "_read", return_value=result):
            with self.assertRaises(host.Refusal): adb.require_no_existing_target()

    def test_only_documented_empty_pidof_proves_prelaunch_absence(self):
        adb = host.Adb("adb", host.SERIAL)
        with patch.object(adb, "_read", return_value=SimpleNamespace(returncode=1, stdout=b"", stderr=b"")):
            adb.require_no_existing_target()
        for code, out, err in ((0, b"", b""), (1, b"", b"denied"), (1, b"123\n", b"")):
            with patch.object(adb, "_read", return_value=SimpleNamespace(returncode=code, stdout=out, stderr=err)):
                with self.assertRaises(host.Refusal): adb.require_no_existing_target()

    def test_exact_owned_pid_absence_supports_toybox_exit_one_only_for_header(self):
        adb = host.Adb("adb", host.SERIAL); process = report()["targetProcess"]
        for code in (0, 1):
            with patch.object(adb, "_read", return_value=SimpleNamespace(returncode=code, stdout=b"PID NAME\n", stderr=b"")):
                self.assertTrue(adb.exact_pid_absent(process))
        with patch.object(adb, "_read", return_value=SimpleNamespace(returncode=0, stdout=("PID NAME\n12345 " + host.APP + "\n").encode(), stderr=b"")):
            self.assertFalse(adb.exact_pid_absent(process))
        for out in (b"", b"PID NAME\n12345 other\n", b"PID NAME\n99999 other\n"):
            with patch.object(adb, "_read", return_value=SimpleNamespace(returncode=1, stdout=out, stderr=b"")):
                with self.assertRaises(host.Refusal): adb.exact_pid_absent(process)

    def test_apk_digest_and_code_are_checked_without_install(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"fixture"); archive.writestr("classes.dex", b"fixture")
            digest = host.sha256(path)
            self.assertEqual(digest, host.apk(path, digest)[1]["sha256"])
            with self.assertRaises(host.Refusal): host.apk(path, "0" * 64)

    def test_capture_requires_single_bounded_report_and_successful_code(self):
        capture = host.Capture()
        encoded = json.dumps(report()).encode()
        capture.feed(b"INSTRUMENTATION_RESULT: reminder_probe_report=" + encoded[:50])
        capture.feed(encoded[50:] + b"\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual(report(), capture.finish())
        with self.assertRaises(host.Refusal): capture.feed(b"INSTRUMENTATION_CODE: -1\n")
        capture = host.Capture()
        with self.assertRaises(host.Refusal): capture.feed(b"x" * (host.MAX_LINE + 1))

    def test_duplicate_json_keys_and_noncanonical_run_are_refused(self):
        with self.assertRaises(host.Refusal): host.decode('{"status":"FAIL","status":"PASS"}')
        with self.assertRaises(host.Refusal): host.exact_uuid("bad-run")

    def test_artifacts_are_exclusive_and_private(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "result.json"
            host.exclusive(path, {"status": "fixture"})
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError): host.exclusive(path, {})


if __name__ == "__main__":
    unittest.main()
