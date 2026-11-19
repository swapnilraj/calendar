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
PROCESSES = {
    "identity": {"pid": 11110, "startElapsedRealtime": 50, "name": host.APP},
    "CREATE_STAGE": {"pid": 11111, "startElapsedRealtime": 100, "name": host.APP},
    "RECOVER_READ_ONLY": {"pid": 11112, "startElapsedRealtime": 200, "name": host.APP},
    "CLEANUP": {"pid": 11113, "startElapsedRealtime": 300, "name": host.APP},
}


def prior_for(phase):
    keys = {"CREATE_STAGE": (), "RECOVER_READ_ONLY": ("CREATE_STAGE",), "CLEANUP": ("CREATE_STAGE", "RECOVER_READ_ONLY")}[phase]
    return {key: copy.deepcopy(PROCESSES[key]) for key in keys}


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
              "batchAssertionCount": 6, "targetProcess": copy.deepcopy(PROCESSES[phase]), "priorProcesses": prior_for(phase)}
    report["cleanup"] = ({"status": "VERIFIED_CALENDAR_AND_TRACKED_IDS_ABSENT", "trackedEventCount": 6,
                          "localFiles": "VERIFIED_EXACT_RUN_FILES_ABSENT", "directQueryCount": 250, "batchAssertionCount": 7,
                          "trackedIdCheckScope": "GLOBAL_TRACKED_IDS"} if phase == "CLEANUP" else
                         {"status": "RETAINED_FOR_EXPLICIT_NEXT_PHASE", "ownershipVerified": True})
    if phase == "RECOVER_READ_ONLY":
        report["recovery"] = {"readGranted": True, "writeDenied": True, "authoringTrapInstalled": True,
                              "nativeWriteCalls": 0, "forbiddenWriteAttempts": 0, "minimalBindingQueries": 24,
                              "membershipQueries": 24, "guardedChildQueries": 16}
    return report


class PhaseProtocolTest(unittest.TestCase):
    def test_valid_phases_keep_stage_distinct_from_pass(self):
        for phase in ("CREATE_STAGE", "RECOVER_READ_ONLY", "CLEANUP"):
            self.assertEqual(PINS, host.require_app_phase(phase_report(phase), phase, RUN, PINS, prior_for(phase)))

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
            with self.assertRaises(host.Refusal): host.require_app_phase(report, "RECOVER_READ_ONLY", RUN, PINS, prior_for("RECOVER_READ_ONLY"))

    def test_cleanup_requires_calendar_events_and_local_files_absent(self):
        for key, value in (("status", "CLEANUP_WITHHELD"), ("trackedEventCount", 5), ("localFiles", "UNKNOWN"),
                           ("trackedIdCheckScope", "CALENDAR_SCOPED")):
            report = phase_report("CLEANUP"); report["cleanup"][key] = value
            with self.assertRaises(host.Refusal): host.require_app_phase(report, "CLEANUP", RUN, PINS, prior_for("CLEANUP"))

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

    def test_native_process_identity_and_both_prior_tuples_are_required(self):
        for key, value in (("pid", True), ("pid", 0), ("pid", 2147483648), ("name", "another.app"),
                           ("startElapsedRealtime", 0), ("startElapsedRealtime", 100)):
            report = phase_report("RECOVER_READ_ONLY"); report["targetProcess"][key] = value
            with self.assertRaises(host.Refusal): host.require_app_phase(report, "RECOVER_READ_ONLY", RUN, PINS, prior_for("RECOVER_READ_ONLY"))
        for changed_phase in ("CREATE_STAGE", "RECOVER_READ_ONLY"):
            report = phase_report("CLEANUP"); report["priorProcesses"][changed_phase]["pid"] += 10
            with self.assertRaises(host.Refusal): host.require_app_phase(report, "CLEANUP", RUN, PINS, prior_for("CLEANUP"))
        with self.assertRaises(host.Refusal): host.require_app_phase(phase_report("CLEANUP"), "CLEANUP", RUN, PINS)


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

    def execute(self, args, bad_phase=None, corruption=False, interrupted=False, process_states=None):
        events = []
        process_states = process_states or {}
        class Adb:
            def __init__(self, *ignored): pass
            def mutation(self, tokens, shell=False): events.append(tuple(tokens))
            def read(self, tokens, shell=False):
                self_outer = {str(value["pid"]): key for key, value in PROCESSES.items()}
                if len(tokens) != 5 or tokens[:2] != ["ps", "-p"] or tokens[3:] != ["-o", "PID,NAME"] or tokens[2] not in self_outer:
                    raise AssertionError("only native-reported exact owned PID reads are allowed")
                key = self_outer[tokens[2]]; events.append(("target-ps", key))
                outcome = process_states.get(key, "absent")
                if outcome == "unreadable": raise host.Refusal("synthetic nonzero/unreadable ps")
                if outcome == "ambiguous": return "PID NAME\n" + tokens[2] + " other.process"
                if outcome == "running": return "PID NAME\n" + tokens[2] + " " + host.APP
                return "PID NAME"
            def read_reported_target_pid(self, process):
                host.validate_target_process(process)
                key = next(key for key, value in PROCESSES.items() if value["pid"] == process["pid"])
                events.append(("target-ps", key)); outcome = process_states.get(key, "absent")
                facts = {"command": ["adb", "-s", GUARD["serial"], "shell", "ps -p " + str(process["pid"]) + " -o PID,NAME"],
                         "returncode": 0, "stdout": "PID NAME\n", "stderr": "", "failure": None, "outputTruncated": False, "timedOut": False}
                if outcome == "unreadable": facts.update(returncode=None, failure="READ_TIMEOUT", timedOut=True)
                elif outcome == "ambiguous": facts["stdout"] += str(process["pid"]) + " other.process\n"
                elif outcome == "running": facts["stdout"] += str(process["pid"]) + " " + host.APP + "\n"
                elif outcome == "absent-exit1": facts["returncode"] = 1
                return facts
        def instrument(adb, run, mode, pins, output, label, endzone="distinct", phase=None, boot=None, process_witness=False):
            events.append(("instrument-enter", phase or mode))
            if bad_phase is not None and phase == bad_phase:
                if interrupted: raise KeyboardInterrupt()
                raise host.Refusal("synthetic incomplete instrumentation")
            if mode == "identity":
                report = {"schema": 1, "status": "PASS", "scope": "native-provider-identity-only", "runId": RUN,
                          "productionProfileActivation": "NONE", "providerIdentity": PINS}
                if process_witness: report["targetProcess"] = copy.deepcopy(PROCESSES["identity"])
            else: report = phase_report(phase)
            if phase == "CREATE_STAGE" and corruption: report["ownershipProof"] = "CORRUPT"
            events.append(("instrument-exit-protocol-complete", phase or mode))
            return report
        with patch.object(host, "Adb", Adb), patch.object(host, "guard_device", side_effect=lambda *_: dict(GUARD)), patch.object(host, "instrument", side_effect=instrument), patch.object(host.time, "sleep"):
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
        self.assertLess(events.index(("target-ps", "CREATE_STAGE")), events.index(revoke))
        self.assertLess(events.index(revoke), events.index(("instrument-enter", "RECOVER_READ_ONLY")))
        self.assertLess(events.index(("instrument-exit-protocol-complete", "RECOVER_READ_ONLY")), grants[-1])
        self.assertLess(events.index(("target-ps", "RECOVER_READ_ONLY")), grants[-1])
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

    def test_host_adb_exit_with_still_running_stage_target_cannot_revoke_write(self):
        with tempfile.TemporaryDirectory() as d:
            args = self.setup_run(d); events, result = self.execute(args, process_states={"CREATE_STAGE": "running"})
            proof = json.loads((args.output_dir / "app-create-stage.target-exit.json").read_text())
        self.assertIn(("instrument-exit-protocol-complete", "CREATE_STAGE"), events)
        self.assertEqual(10, events.count(("target-ps", "CREATE_STAGE")))
        self.assertEqual("UNPROVEN", proof["status"]); self.assertEqual("FAIL", result["status"])
        self.assertFalse(any(e[:2] == ("pm", "revoke") for e in events))
        self.assertNotIn(("instrument-enter", "RECOVER_READ_ONLY"), events)

    def test_unproven_recovery_target_cannot_regrant_write_or_start_cleanup(self):
        for outcome in ("running", "unreadable", "ambiguous"):
            with tempfile.TemporaryDirectory() as d:
                args = self.setup_run(d); events, result = self.execute(args, process_states={"RECOVER_READ_ONLY": outcome})
            self.assertEqual("FAIL", result["status"])
            self.assertEqual(1, events.count(("pm", "grant", "--user", "0", host.APP, "android.permission.WRITE_CALENDAR")))
            self.assertNotIn(("instrument-enter", "CLEANUP"), events)
            if outcome != "running": self.assertEqual(1, events.count(("target-ps", "RECOVER_READ_ONLY")))

    def test_unreadable_or_ambiguous_ps_preflight_creates_no_fixture_and_grants_no_permission(self):
        for outcome in ("unreadable", "ambiguous", "running"):
            with tempfile.TemporaryDirectory() as d:
                args = self.setup_run(d); events, result = self.execute(args, process_states={"identity": outcome})
            self.assertEqual("FAIL", result["status"])
            self.assertNotIn(("instrument-enter", "CREATE_STAGE"), events)
            self.assertFalse(any(e[:2] in (("pm", "grant"), ("pm", "revoke")) for e in events))

    def test_documented_exit1_header_only_still_requires_all_protocol_phases_and_proofs(self):
        with tempfile.TemporaryDirectory() as d:
            events, result = self.execute(self.setup_run(d), process_states={key: "absent-exit1" for key in PROCESSES})
        self.assertEqual("PASS", result["status"])
        self.assertEqual(1, events.count(("instrument-enter", "CREATE_STAGE")))
        self.assertEqual(1, events.count(("instrument-enter", "RECOVER_READ_ONLY")))
        self.assertEqual(1, events.count(("instrument-enter", "CLEANUP")))

    def test_identity_mode_keeps_no_calendar_permission_or_process_read_behavior(self):
        with tempfile.TemporaryDirectory() as d:
            args = self.setup_run(d); args.mode = "identity"; events, result = self.execute(args)
        self.assertEqual("PASS", result["status"])
        self.assertFalse(any(e[0] in ("target-ps", "pm") for e in events))

    def test_unknown_reported_name_is_refused_before_any_pid_read(self):
        class NoReads:
            def read(self, *args, **kwargs): raise AssertionError("must not inspect unowned process")
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(host.Refusal):
                host.confirm_target_exit(NoReads(), object(), {"pid": 123, "startElapsedRealtime": 20, "name": "another.app"}, Path(d), "bad")


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


class ExactReportedPidReadTest(unittest.TestCase):
    process = PROCESSES["CREATE_STAGE"]

    def call(self, output, result=None, error=None, guard_side_effect=None):
        adb = host.Adb("adb", GUARD["serial"])
        with patch.object(host.subprocess, "run", return_value=result, side_effect=error) as run, \
                patch.object(host, "guard_device", side_effect=guard_side_effect or (lambda *_: dict(GUARD))), \
                patch.object(host.time, "sleep"):
            proof = host.confirm_target_exit(adb, object(), self.process, output, "target")
        return proof, run

    def test_header_only_exit0_and_documented_exit1_are_accepted_with_raw_facts(self):
        for code in (0, 1):
            result = host.subprocess.CompletedProcess([], code, b"  PID NAME                       \n", b"")
            with tempfile.TemporaryDirectory() as d:
                proof, run = self.call(Path(d), result=result)
                retained = json.loads((Path(d) / "target.target-exit.json").read_text())
            self.assertEqual("TARGET_PID_ABSENT", proof["status"])
            self.assertEqual(code, retained["pidReads"][0]["returncode"])
            self.assertEqual("  PID NAME                       \n", retained["pidReads"][0]["stdout"])
            self.assertEqual("", retained["pidReads"][0]["stderr"])
            self.assertEqual(["adb", "-s", GUARD["serial"], "shell", "ps -p 11111 -o PID,NAME"], run.call_args.args[0])
            self.assertEqual(1, run.call_count)

    def test_other_codes_stderr_malformed_and_extra_rows_are_refused_and_retained(self):
        fixtures = [(2, b"PID NAME\n", b""), (-1, b"PID NAME\n", b""), (0, b"PID NAME\n", b"warning"),
                    (1, b"PID NAME\n", b"\n"), (0, b"", b""), (1, b"PID COMMAND\n", b""),
                    (1, b"PID NAME\n11111 " + host.APP.encode() + b"\n", b""),
                    (0, b"PID NAME\n11111 other.app\n", b""), (0, b"PID NAME\n\n", b""),
                    (1, b"PID NAME\n11111 other.app\n11112 extra.app\n", b"")]
        for code, stdout, stderr in fixtures:
            with tempfile.TemporaryDirectory() as d:
                with self.assertRaises(host.Refusal):
                    self.call(Path(d), result=host.subprocess.CompletedProcess([], code, stdout, stderr))
                proof = json.loads((Path(d) / "target.target-exit.json").read_text())
            self.assertEqual("UNPROVEN", proof["status"])
            self.assertEqual(code, proof["pidReads"][0]["returncode"])
            self.assertEqual(stdout.decode(), proof["pidReads"][0]["stdout"])
            self.assertEqual(stderr.decode(), proof["pidReads"][0]["stderr"])
            self.assertEqual("EXACT_PID_RESULT", proof["failureStage"])

    def test_utf_limits_and_timeouts_refuse_even_when_partial_header_looks_valid(self):
        fixtures = [(host.subprocess.CompletedProcess([], 1, b"\xff", b""), None, "UTF8_ERROR"),
                    (host.subprocess.CompletedProcess([], 1, b"PID NAME\n" + b" " * 16384, b""), None, "OUTPUT_LIMIT"),
                    (host.subprocess.CompletedProcess([], 0, b"PID NAME\n", b"x" * 16385), None, "OUTPUT_LIMIT"),
                    (None, host.subprocess.TimeoutExpired("adb", 30, output=b"PID NAME\n", stderr=b""), "READ_TIMEOUT"),
                    (None, OSError("synthetic read failure"), "LOCAL_READ_ERROR")]
        for result, error, expected in fixtures:
            with tempfile.TemporaryDirectory() as d:
                with self.assertRaises(host.Refusal): self.call(Path(d), result=result, error=error)
                proof = json.loads((Path(d) / "target.target-exit.json").read_text())
            self.assertEqual("UNPROVEN", proof["status"])
            self.assertEqual(expected, proof["pidReads"][0]["failure"])
            self.assertLessEqual(len(proof["pidReads"][0]["stdout"]), 16384)
            self.assertLessEqual(len(proof["pidReads"][0]["stderr"]), 16384)

    def test_general_adb_read_remains_strict_even_for_header_only_exit1(self):
        adb = host.Adb("adb", GUARD["serial"])
        with patch.object(host.subprocess, "run", return_value=host.subprocess.CompletedProcess([], 1, b"PID NAME\n", b"")):
            with self.assertRaises(host.Refusal): adb.read(["ps", "-p", "11111", "-o", "PID,NAME"], shell=True)
            with self.assertRaises(host.Refusal): adb.read(["get-state"])

    def test_owned_row_is_still_running_only_for_exit0_then_exit1_can_confirm_absence(self):
        sequence = [host.subprocess.CompletedProcess([], 0, b"PID NAME\n11111 " + host.APP.encode() + b"\n", b""),
                    host.subprocess.CompletedProcess([], 1, b"PID NAME\n", b"")]
        with tempfile.TemporaryDirectory() as d:
            proof, run = self.call(Path(d), error=sequence)
        self.assertEqual(["REPORTED_OWNED_PID_PRESENT", "REPORTED_PID_ABSENT"], proof["observations"])
        self.assertEqual([0, 1], [row["returncode"] for row in proof["pidReads"]])
        self.assertEqual(2, run.call_count)

    def test_guard_change_after_pid_read_keeps_read_facts_but_never_confirms_exit(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(host.Refusal):
                self.call(Path(d), result=host.subprocess.CompletedProcess([], 1, b"PID NAME\n", b""),
                          guard_side_effect=[dict(GUARD), host.Refusal("boot changed")])
            proof = json.loads((Path(d) / "target.target-exit.json").read_text())
        self.assertEqual("UNPROVEN", proof["status"])
        self.assertEqual("GUARD_AFTER_PID_READ", proof["failureStage"])
        self.assertEqual(1, proof["pidReads"][0]["returncode"])

    def test_actual_guard_failure_records_exact_guard_tokens_and_pid_facts(self):
        class GuardChangingReads:
            boots = 0
            def read(self, tokens, shell=False):
                if tokens == ["cat", "/proc/sys/kernel/random/boot_id"]:
                    self.boots += 1
                    return BOOT if self.boots == 1 else RUN
                values = {("get-state",): "device", ("emu", "avd", "name"): host.OWNED_AVD + "\nOK",
                          ("getprop", "ro.kernel.qemu"): "1", ("am", "get-current-user"): "0",
                          ("getprop", "ro.build.fingerprint"): PINS["buildFingerprint"]}
                return values[tuple(tokens)]
            def read_reported_target_pid(self, process):
                return {"returncode": 1, "stdout": "PID NAME\n", "stderr": "", "failure": None,
                        "timedOut": False, "outputTruncated": False}
        args = type("Args", (), {"serial": GUARD["serial"], "expected_avd": host.OWNED_AVD, "expected_boot_id": BOOT})()
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(host.Refusal): host.confirm_target_exit(GuardChangingReads(), args, self.process, Path(d), "guard")
            proof = json.loads((Path(d) / "guard.target-exit.json").read_text())
        self.assertEqual("UNPROVEN", proof["status"])
        self.assertEqual("GUARD_AFTER_PID_READ", proof["failureStage"])
        self.assertEqual(["cat", "/proc/sys/kernel/random/boot_id"], proof["guardReads"][-1]["tokens"])
        self.assertEqual(RUN, proof["guardReads"][-1]["stdout"])
        self.assertEqual(1, proof["pidReads"][0]["returncode"])


if __name__ == "__main__":
    unittest.main()
