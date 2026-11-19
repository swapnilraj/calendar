"""Stdlib-only guard/protocol tests. Never invokes adb, Gradle, or an emulator."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("probe_host", Path(__file__).with_name("run-native-provider-probe.py"))
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)
RUN = "77777777-7777-4777-8777-777777777777"
BOOT = "88888888-8888-4888-8888-888888888888"
PINS = {"package": "com.android.providers.calendar", "versionCode": 35, "baseSha256": "a" * 64,
        "splits": [], "signers": ["b" * 64], "signingHistory": ["b" * 64],
        "buildFingerprint": "synthetic-owned-build", "sdk": 35, "securityPatch": "2026-01-01"}
GUARD = {"serial": "emulator-5554", "avd": host.OWNED_AVD, "bootId": BOOT,
         "buildFingerprint": PINS["buildFingerprint"], "qemu": "1", "androidUser": 0}


def phase_report(phase, run=RUN):
    cases = []
    for label, (mode, all_day, alerts, fault) in host.APP_CASES.items():
        staged = phase == "CREATE_STAGE"
        cases.append({"label": label, "mode": mode, "allDay": all_day, "alertCount": alerts, "fault": fault,
                      "phase": "COMMITTED" if staged and fault != "NONE" else "VERIFIED",
                      "recoveries": 0 if staged else 2, "uidStable": not staged or fault == "NONE", "planStable": True})
    report = {"schema": 1, "runId": run, "scope": "native-local-app-writer-only", "appPhase": phase,
              "status": {"CREATE_STAGE": "STAGED", "RECOVER_READ_ONLY": "RECOVERED", "CLEANUP": "PASS"}[phase],
              "productionProfileActivation": "NONE", "googleWorkspaceRoundTrip": "NOT_RUN", "providerIdentity": copy.deepcopy(PINS),
              "ownershipProof": "EXACT_RUN_MANIFEST_AND_CALENDAR", "receiptVersion": 6, "cases": cases,
              "fixtureRetained": phase != "CLEANUP", "queryTimeoutSeconds": 30, "directQueryCap": 320,
              "batchAssertionCap": 128, "deadlineKind": "cooperative", "directQueryCount": 80,
              "batchAssertionCount": 6}
    report["cleanup"] = ({"status": "VERIFIED_CALENDAR_AND_TRACKED_IDS_ABSENT", "trackedEventCount": 6,
                          "localFiles": "VERIFIED_EXACT_RUN_FILES_ABSENT", "directQueryCount": 250, "batchAssertionCount": 7} if phase == "CLEANUP" else
                         {"status": "RETAINED_FOR_EXPLICIT_NEXT_PHASE", "ownershipVerified": True})
    if phase == "RECOVER_READ_ONLY":
        report["recovery"] = {"readGranted": True, "writeDenied": True, "authoringTrapInstalled": True,
                              "nativeWriteCalls": 0, "forbiddenWriteAttempts": 0, "minimalBindingQueries": 24,
                              "membershipQueries": 24, "guardedChildQueries": 16}
    return report


class PhaseProtocolTest(unittest.TestCase):
    def test_valid_phases_keep_stage_distinct_from_pass(self):
        for phase in ("CREATE_STAGE", "RECOVER_READ_ONLY", "CLEANUP"):
            self.assertEqual(PINS, host.require_app_phase(phase_report(phase), phase, RUN, PINS))

    def test_wrong_or_partial_proof_is_never_continuable(self):
        mutations = [lambda r: r.update(status="PASS"), lambda r: r.update(runId=BOOT),
                     lambda r: r.update(ownershipProof="UNKNOWN"), lambda r: r.update(productionProfileActivation="ENABLED"),
                     lambda r: r["cases"].pop(), lambda r: r["cases"].__setitem__(1, r["cases"][0]),
                     lambda r: r["cases"][0].update(alertCount=True), lambda r: r["providerIdentity"].update(baseSha256="c" * 64),
                     lambda r: r["cases"][-1].update(uidStable=True), lambda r: r["cleanup"].update(ownershipVerified=False)]
        for mutate in mutations:
            report = phase_report("CREATE_STAGE"); mutate(report)
            with self.assertRaises(host.Refusal): host.require_app_phase(report, "CREATE_STAGE", RUN, PINS)

    def test_read_only_proof_requires_actual_permission_and_zero_mutation_attempts(self):
        for key, value in (("readGranted", False), ("writeDenied", False), ("authoringTrapInstalled", False),
                           ("nativeWriteCalls", 1), ("forbiddenWriteAttempts", 1), ("guardedChildQueries", 15),
                           ("minimalBindingQueries", True)):
            report = phase_report("RECOVER_READ_ONLY"); report["recovery"][key] = value
            with self.assertRaises(host.Refusal): host.require_app_phase(report, "RECOVER_READ_ONLY", RUN, PINS)

    def test_cleanup_requires_calendar_events_and_local_files_absent(self):
        for key, value in (("status", "BLOCKED_NO_BLIND_DELETE"), ("trackedEventCount", 5), ("localFiles", "UNKNOWN")):
            report = phase_report("CLEANUP"); report["cleanup"][key] = value
            with self.assertRaises(host.Refusal): host.require_app_phase(report, "CLEANUP", RUN, PINS)

    def test_identity_mode_does_not_accept_writer_stage(self):
        with self.assertRaises(host.Refusal): host.require_pass(phase_report("CREATE_STAGE"), "identity", RUN)

    def test_cumulative_limits_and_strict_schema_cannot_be_reset_or_coerced(self):
        for key, value in (("directQueryCount", 321), ("batchAssertionCount", -1), ("schema", True),
                           ("directQueryCap", 9999), ("queryTimeoutSeconds", 0)):
            report = phase_report("CREATE_STAGE"); report[key] = value
            with self.assertRaises(host.Refusal): host.require_app_phase(report, "CREATE_STAGE", RUN, PINS)

    def test_existing_eighteen_case_contract_remains_accepted_and_requires_all_cases(self):
        report = {"schema": 1, "status": "PASS", "runId": RUN, "scope": "native-local-provider-only",
                  "productionProfileActivation": "NONE", "providerIdentity": PINS, "endZoneMode": "distinct",
                  "cleanup": {"status": "VERIFIED_CALENDAR_AND_TRACKED_IDS_ABSENT"}, "cases": []}
        summary = {"count": 8, "duplicates": 0, "missingExpected": 0, "unexpectedBounds": 0}
        for seed in ("new-york-spring", "lord-howe-half-hour", "utc-date-two-day"):
            for limit in ("COUNT", "UNTIL"):
                for variant in ("RULE_ONLY_CONTROL", "EXACT_CONTEXT", "ROLLBACK"):
                    zone = None if seed == "utc-date-two-day" else "Pacific/Honolulu"
                    report["cases"].append({"seed": seed, "limit": limit, "variant": variant, "requestedEndZone": zone,
                                            "storedEndZone": zone, "before": summary, "after": summary, "outcome": "PASS",
                                            "prefixCount": 3, "tailCount": 5, "rollback": "VERIFIED_FINAL_ASSERTION"})
        self.assertEqual(PINS, host.require_pass(report, "fixtures", RUN, "distinct"))
        report["cases"].pop()
        with self.assertRaises(host.Refusal): host.require_pass(report, "fixtures", RUN, "distinct")

    def test_duplicate_json_keys_and_arbitrary_serial_are_refused(self):
        with self.assertRaises(host.Refusal): host.decode_json('{"schema":1,"schema":1}')
        for value in ("device-123", "emulator-0", "emulator-5554 extra", "emulator-5554\n"):
            with self.assertRaises(Exception): host.emulator_serial(value)


class HostOrderingTest(unittest.TestCase):
    def setup_run(self, directory):
        directory = Path(directory); apks = {}
        for role in ("app", "test"):
            file = directory / (role + ".apk")
            with zipfile.ZipFile(file, "w") as z:
                z.writestr("AndroidManifest.xml", role); z.writestr("classes.dex", role)
            _, apks[role] = host.apk_metadata(file)
        prior_run = "99999999-9999-4999-8999-999999999999"
        identity = {"schema": 1, "status": "PASS", "scope": "native-provider-identity-only", "runId": prior_run,
                    "productionProfileActivation": "NONE", "providerIdentity": PINS}
        prior = directory / "identity.json"
        prior.write_text(json.dumps({"schema": 1, "mode": "identity", "status": "PASS", "runId": prior_run,
                                     "apks": apks, "guard": GUARD, "probeReport": identity}))
        args = host.parser().parse_args(["app-create-recovery", "--serial", GUARD["serial"], "--expected-avd", host.OWNED_AVD,
                                        "--expected-boot-id", BOOT, "--app-apk", str(directory / "app.apk"),
                                        "--test-apk", str(directory / "test.apk"), "--identity-report", str(prior),
                                        "--output-dir", str(directory / "out"), "--run-id", RUN])
        return args

    def execute(self, args, bad_phase=None, corruption=False, interrupted=False):
        events = []
        class Adb:
            def __init__(self, *ignored): pass
            def mutation(self, tokens, shell=False): events.append(tuple(tokens))
        def instrument(adb, run, mode, pins, output, label, endzone="distinct", phase=None, boot=None):
            events.append(("instrument-enter", phase or mode))
            if bad_phase is not None and phase == bad_phase:
                if interrupted: raise KeyboardInterrupt()
                raise host.Refusal("synthetic incomplete instrumentation")
            if mode == "identity":
                report = {"schema": 1, "status": "PASS", "scope": "native-provider-identity-only", "runId": RUN,
                          "productionProfileActivation": "NONE", "providerIdentity": PINS}
            else: report = phase_report(phase)
            if phase == "CREATE_STAGE" and corruption: report["ownershipProof"] = "CORRUPT"
            events.append(("instrument-exit-protocol-complete", phase or mode))
            return report
        with patch.object(host, "Adb", Adb), patch.object(host, "guard_device", side_effect=lambda *_: dict(GUARD)), patch.object(host, "instrument", side_effect=instrument):
            try: host.run(args)
            except (host.Refusal, KeyboardInterrupt): pass
        return events, json.loads((args.output_dir / "result.json").read_text())

    def test_write_revoke_after_successful_stage_exit_and_regrant_only_after_recovery_exit(self):
        with tempfile.TemporaryDirectory() as d:
            events, result = self.execute(self.setup_run(d))
        revoke = ("pm", "revoke", "--user", "0", host.APP, "android.permission.WRITE_CALENDAR")
        grants = [i for i, event in enumerate(events) if event == ("pm", "grant", "--user", "0", host.APP, "android.permission.WRITE_CALENDAR")]
        self.assertEqual(2, len(grants)); self.assertEqual("PASS", result["status"])
        self.assertLess(events.index(("instrument-exit-protocol-complete", "CREATE_STAGE")), events.index(revoke))
        self.assertLess(events.index(revoke), events.index(("instrument-enter", "RECOVER_READ_ONLY")))
        self.assertLess(events.index(("instrument-exit-protocol-complete", "RECOVER_READ_ONLY")), grants[-1])
        self.assertLess(grants[-1], events.index(("instrument-enter", "CLEANUP")))

    def test_failed_or_corrupt_stage_cannot_revoke_or_start_recovery(self):
        for corruption in (False, True):
            with tempfile.TemporaryDirectory() as d:
                events, result = self.execute(self.setup_run(d), bad_phase=None if corruption else "CREATE_STAGE", corruption=corruption)
            self.assertEqual("FAIL", result["status"])
            self.assertFalse(any(e[:2] == ("pm", "revoke") for e in events))
            self.assertNotIn(("instrument-enter", "RECOVER_READ_ONLY"), events)

    def test_uncertain_or_interrupted_recovery_never_regrants_write_or_runs_cleanup(self):
        for interrupted in (False, True):
            with tempfile.TemporaryDirectory() as d:
                events, result = self.execute(self.setup_run(d), bad_phase="RECOVER_READ_ONLY", interrupted=interrupted)
            self.assertEqual("FAIL", result["status"])
            self.assertEqual(1, events.count(("pm", "grant", "--user", "0", host.APP, "android.permission.WRITE_CALENDAR")))
            self.assertNotIn(("instrument-enter", "CLEANUP"), events)

    def test_wrong_boot_artifact_refuses_before_any_adb_operation(self):
        with tempfile.TemporaryDirectory() as d:
            args = self.setup_run(d); args.expected_boot_id = RUN
            with patch.object(host, "Adb", side_effect=AssertionError("must not construct adb")):
                with self.assertRaises(host.Refusal): host.run(args)


class DeviceGuardTest(unittest.TestCase):
    def test_exact_owned_device_guard_refuses_mismatched_user_boot_avd_and_qemu(self):
        class Adb:
            def __init__(self, overrides): self.overrides = overrides; self.calls = []
            def read(self, tokens, shell=False):
                key = tuple(tokens); self.calls.append(key)
                defaults = {("get-state",): "device", ("emu", "avd", "name"): host.OWNED_AVD + "\nOK",
                            ("getprop", "ro.kernel.qemu"): "1", ("cat", "/proc/sys/kernel/random/boot_id"): BOOT,
                            ("am", "get-current-user"): "0", ("getprop", "ro.build.fingerprint"): PINS["buildFingerprint"]}
                return self.overrides.get(key, defaults[key])
        args = type("Args", (), {"serial": GUARD["serial"], "expected_avd": host.OWNED_AVD, "expected_boot_id": BOOT})()
        self.assertEqual(GUARD, host.guard_device(Adb({}), args))
        for key, wrong in ((("emu", "avd", "name"), "another-avd\nOK"), (("am", "get-current-user"), "10"),
                           (("getprop", "ro.kernel.qemu"), "0"), (("cat", "/proc/sys/kernel/random/boot_id"), RUN)):
            adb = Adb({key: wrong})
            with self.assertRaises(host.Refusal): host.guard_device(adb, args)
            self.assertNotIn(("devices",), adb.calls)


if __name__ == "__main__":
    unittest.main()
