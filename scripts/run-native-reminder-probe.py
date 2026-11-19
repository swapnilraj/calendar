#!/usr/bin/env python3
"""Identity-only controller for the separately installed, reviewed reminder-probe UID (stdlib)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shlex
import subprocess
import threading
import time
import uuid
import zipfile

APP = "dev.swapnilraj.calendar.reminderprobe"
TEST = APP + ".tests"
APPLICATION = "dev.swapnilraj.calendar.probe.ProbeReminderApplication"
COMPONENT = TEST + "/dev.swapnilraj.calendar.probe.NativeReminderProbe"
SERIAL = "emulator-5554"
AVD = "daylight_google_reference_userdebug_api35"
BOOT = "f14d0429-80d1-4b93-9eeb-da5f4f2046ac"
FINGERPRINT = "google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys"
SIGNER = "90936f837de57f48b69c5a1364920972880362722faf228d2f66c875f6bc6278"
PROVIDER_SIGNER = "6cecc50e34ae31bfb5678986d6d6d3736c571ded2f2459527793e1f054eb0c9b"
PROVIDER = {"package": "com.android.providers.calendar", "versionCode": 35,
            "baseSha256": "a1facdbec3de78c6817efe1aea40b804a66c8ccbcd23a72a426d8c21cfebd273", "splits": [],
            "signers": [PROVIDER_SIGNER], "signingHistory": [PROVIDER_SIGNER],
            "buildFingerprint": FINGERPRINT, "sdk": 35, "securityPatch": "2024-09-05"}
SHA = re.compile(r"[0-9a-f]{64}\Z")
MAX_CAPTURE = 65536
MAX_LINE = 40000
MAX_REPORT = 32768
ROOT = "dev.swapnilraj.calendar."
COMPONENTS = [(ROOT + "android.reminders." + name, "receiver", True, False)
              for name in ("ReminderAlarmReceiver", "ReminderActionReceiver", "ReminderRecoveryReceiver")]
COMPONENTS += [(ROOT + "android.reminders.ReminderJobService", "service", True, True)]
COMPONENTS += [(ROOT + name, "activity", False, True) for name in
               ("MainActivity", "android.navigation.CalendarEntryActivity", "android.navigation.CalendarEditReviewActivity",
                "android.navigation.CalendarSeedReviewActivity", "ReminderOpenActivity", "WidgetOpenActivity", "android.widgets.AgendaWidgetConfigureActivity")]
# Disabled components retain their existing exported attribute; it is not used as authority.
COMPONENTS += [(ROOT + "android.widgets." + name, "receiver", False, False)
               for name in ("AgendaWidgetProvider", "AgendaWidgetRecoveryReceiver")]
COMPONENTS += [(ROOT + "android.widgets.AgendaWidgetJobService", "service", False, True)]
COMPONENTS += [(name, "activity", False, True) for name in
               ("androidx.compose.ui.tooling.PreviewActivity", "androidx.activity.ComponentActivity")]
COMPONENTS += [("androidx.startup.InitializationProvider", "provider", False, False),
               ("androidx.profileinstaller.ProfileInstallReceiver", "receiver", False, True)]


class Refusal(RuntimeError):
    pass


def exact_uuid(value):
    try:
        if isinstance(value, str) and str(uuid.UUID(value)) == value:
            return value
    except ValueError:
        pass
    raise Refusal("canonical UUID required")


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Refusal("duplicate JSON key")
        result[key] = value
    return result


def decode(raw):
    try:
        return json.loads(raw, object_pairs_hook=strict_object)
    except (ValueError, UnicodeError) as error:
        raise Refusal("invalid JSON") from error


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def apk(path, expected):
    if not isinstance(expected, str) or not SHA.fullmatch(expected):
        raise Refusal("reviewed APK digest required")
    path = path.resolve(strict=True)
    if not path.is_file() or not 0 < path.stat().st_size <= 134217728 or not zipfile.is_zipfile(path):
        raise Refusal("bounded reviewed APK required")
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) > 8192 or len(set(names)) != len(names) or any(len(n) > 512 for n in names):
            raise Refusal("APK entry limit or duplicate names")
        if not {"AndroidManifest.xml", "classes.dex"} <= set(names):
            raise Refusal("APK manifest/code required")
    if sha256(path) != expected:
        raise Refusal("reviewed APK changed")
    return path, {"name": path.name, "bytes": path.stat().st_size, "sha256": expected}


def exclusive(path, value, raw=False):
    with path.open("xb") as stream:
        os.chmod(path, 0o600)
        stream.write(value if raw else (json.dumps(value, indent=2, sort_keys=True) + "\n").encode())
        stream.flush()
        os.fsync(stream.fileno())


class Adb:
    """Only fixed read commands plus one fixed instrumentation launch; no install/grant/stop API."""
    READS = {(False, ("get-state",)), (False, ("emu", "avd", "name")),
             (True, ("getprop", "ro.kernel.qemu")), (True, ("getprop", "ro.build.fingerprint")),
             (True, ("cat", "/proc/sys/kernel/random/boot_id")), (True, ("am", "get-current-user"))}

    def __init__(self, executable, serial):
        if serial != SERIAL:
            raise Refusal("owned serial required")
        self.prefix = [executable, "-s", serial]

    def read(self, tokens, shell=False):
        if (shell, tuple(tokens)) not in self.READS:
            raise Refusal("command is outside identity read allowlist")
        result = self._read(tokens, shell)
        if result.returncode != 0 or result.stderr:
            raise Refusal("device read failed")
        return result.stdout.decode("utf-8", errors="strict").strip()

    def _read(self, tokens, shell=True):
        command = self.prefix + (["shell", shlex.join(tokens)] if shell else tokens)
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=False)
        if len(result.stdout) > 16384 or len(result.stderr) > 16384:
            raise Refusal("read output limit")
        return result

    def require_no_existing_target(self):
        result = self._read(["pidof", APP])
        if result.returncode != 1 or result.stdout.strip() or result.stderr:
            raise Refusal("probe process already exists or absence is unproven; no stop/restart attempted")

    def exact_pid_absent(self, process):
        process = validate_process(process)
        result = self._read(["ps", "-p", str(process["pid"]), "-o", "PID,NAME"])
        lines = [line.split() for line in result.stdout.decode("utf-8", errors="strict").splitlines()]
        if result.stderr or result.returncode not in (0, 1):
            raise Refusal("exact owned PID read failed")
        if lines == [["PID", "NAME"]]:
            return True
        if result.returncode == 0 and lines == [["PID", "NAME"], [str(process["pid"]), APP]]:
            return False
        raise Refusal("owned PID lifecycle ambiguous; no other process inspected")


def guard(adb, args):
    if args.serial != SERIAL or args.expected_avd != AVD or args.expected_boot_id != BOOT:
        raise Refusal("fixed owned AVD/boot required")
    if adb.read(["get-state"]) != "device":
        raise Refusal("owned emulator offline")
    names = [line.strip() for line in adb.read(["emu", "avd", "name"]).splitlines() if line.strip() not in ("", "OK")]
    if names != [AVD] or adb.read(["getprop", "ro.kernel.qemu"], True) != "1":
        raise Refusal("owned emulator identity differs")
    if adb.read(["cat", "/proc/sys/kernel/random/boot_id"], True) != BOOT:
        raise Refusal("owned boot changed")
    if adb.read(["am", "get-current-user"], True) != "0" or adb.read(["getprop", "ro.build.fingerprint"], True) != FINGERPRINT:
        raise Refusal("owned user/build changed")
    return {"serial": SERIAL, "avd": AVD, "bootId": BOOT, "androidUser": 0, "fingerprint": FINGERPRINT}


def validate_process(value):
    if (not isinstance(value, dict) or set(value) != {"pid", "startElapsedRealtime", "name"} or
            type(value["pid"]) is not int or not 0 < value["pid"] < 2147483648 or
            type(value["startElapsedRealtime"]) is not int or value["startElapsedRealtime"] <= 0 or value["name"] != APP):
        raise Refusal("exact native probe process witness required")
    return value


def require_startup(value):
    counters = {"configurationAttempts", "operationAttempts", "retryAttempts", "registrationAttempts", "cancellationAttempts",
                "inFlight", "retryRefusals", "suppressionRefusals", "unexpectedDeliveryAttempts"}
    keys = counters | {"state", "runtimeGetterCalls", "health", "callers"}
    if not isinstance(value, dict) or set(value) != keys or value["state"] != "UNBOUND":
        raise Refusal("startup schema/state differs")
    if any(type(value[k]) is not int or not 0 <= value[k] <= 8 for k in counters):
        raise Refusal("startup counter bound/type differs")
    if type(value["runtimeGetterCalls"]) is not int or not 2 <= value["runtimeGetterCalls"] <= 10:
        raise Refusal("runtime getter count differs")
    callers = value["callers"]
    kinds = {"NATIVE", "RECOVERY", "ALARM", "ACTION", "JOB", "UNKNOWN"}
    if (not isinstance(callers, dict) or set(callers) != kinds or
            any(type(n) is not int or not 0 <= n <= 8 for n in callers.values()) or callers["NATIVE"] != 2 or
            any(callers[k] != 0 for k in ("ALARM", "ACTION", "JOB", "UNKNOWN"))):
        raise Refusal("runtime entry is unattributed or unexpected")
    recovery = callers["RECOVERY"]
    if (value["runtimeGetterCalls"] != 2 + recovery or value["configurationAttempts"] != recovery or
            value["retryAttempts"] != value["retryRefusals"] or value["retryAttempts"] > recovery or
            value["suppressionRefusals"] > recovery or value["operationAttempts"] != value["suppressionRefusals"] or
            any(value[k] != 0 for k in ("inFlight", "registrationAttempts", "cancellationAttempts", "unexpectedDeliveryAttempts"))):
        raise Refusal("callback settlement or unavailable-port proof incomplete")
    if recovery == 0:
        if value["health"] != "UNKNOWN" or any(value[k] != 0 for k in counters):
            raise Refusal("no-callback state is not genuinely unknown")
    elif value["health"] not in ("UNAVAILABLE", "DEGRADED") or (value["health"] == "DEGRADED" and value["retryRefusals"] == 0):
        raise Refusal("callback health was not genuine unavailable/refused recovery")
    return value


def require_report(value, run, pair):
    keys = {"schema", "scope", "productionProfileActivation", "googleWorkspaceRoundTrip", "status", "runId", "hostBootPin",
            "targetProcess", "targetUid", "applicationEnabledSetting", "appIdentity", "testIdentity", "providerIdentity", "components", "startup", "capabilities", "constraints"}
    if (not isinstance(value, dict) or set(value) != keys or type(value["schema"]) is not int or value["schema"] != 2 or
            value["status"] != "PASS" or value["scope"] != "UNBOUND_NO_DATA_OR_DELIVERY" or value["runId"] != exact_uuid(run) or
            value["productionProfileActivation"] != "NONE" or value["googleWorkspaceRoundTrip"] != "NOT_RUN" or value["hostBootPin"] != BOOT):
        raise Refusal("identity scope/status/schema mismatch")
    validate_process(value["targetProcess"])
    if type(value["targetUid"]) is not int or not 10000 <= value["targetUid"] <= 99999:
        raise Refusal("user0 isolated application UID required")
    if type(value["applicationEnabledSetting"]) is not int or value["applicationEnabledSetting"] != 0:
        raise Refusal("application enabled setting must retain manifest DEFAULT")
    if value["providerIdentity"] != PROVIDER:
        raise Refusal("provider implementation differs")
    for key, pkg, label in (("appIdentity", APP, "app"), ("testIdentity", TEST, "test")):
        meta = value[key]
        if (not isinstance(meta, dict) or set(meta) != {"package", "versionCode", "baseSha256", "splits", "signers"} or
                meta["package"] != pkg or meta["baseSha256"] != pair[label]["sha256"] or meta["splits"] != [] or meta["signers"] != [SIGNER] or
                type(meta["versionCode"]) is not int or (meta["versionCode"] != 1 if label == "app" else meta["versionCode"] not in (0, 1))):
            raise Refusal("installed APK pair differs from reviewed pair")
    rows = value["components"]
    if not isinstance(rows, list) or len(rows) != len(COMPONENTS):
        raise Refusal("component matrix incomplete")
    for row, (name, kind, enabled, exported) in zip(rows, COMPONENTS):
        if (not isinstance(row, dict) or set(row) != {"package", "class", "kind", "enabled", "exported", "process", "enabledSetting"} or
                row["package"] != APP or row["class"] != name or row["kind"] != kind or row["process"] != APP or
                type(row["enabled"]) is not bool or row["enabled"] is not enabled or type(row["exported"]) is not bool):
            raise Refusal("component package/class/enablement differs")
        if type(row["enabledSetting"]) is not int or row["enabledSetting"] != 0:
            raise Refusal("component enabled setting must retain manifest DEFAULT")
        if enabled and row["exported"] is not exported:
            raise Refusal("reminder receiver/service export differs")
    require_startup(value["startup"])
    caps = value["capabilities"]
    boolean_keys = {"readCalendar", "writeCalendar", "postNotifications", "notificationsEnabled", "exactAlarmAccess", "channelPresent"}
    if (not isinstance(caps, dict) or set(caps) != boolean_keys | {"channelEnabled", "ownJobCount", "ownNotificationCount"} or
            any(type(caps[k]) is not bool for k in boolean_keys) or
            (caps["channelEnabled"] is not None and type(caps["channelEnabled"]) is not bool) or
            (caps["channelEnabled"] is None) is caps["channelPresent"]):
        raise Refusal("capability schema differs")
    if caps["channelPresent"] or caps["channelEnabled"] is not None:
        raise Refusal("unbound identity requires no existing channel")
    for key in ("ownJobCount", "ownNotificationCount"):
        if type(caps[key]) is not int or caps[key] != 0:
            raise Refusal("probe UID has existing resources")
    constraints = {"state": "UNBOUND", "providerDataAccessAvailable": False, "privateStoreAccessAvailable": False,
                   "fixtureModesAvailable": False, "cleanupAvailable": False, "permissionCommandsAvailable": False,
                   "nativeJobSchedulingAvailable": False, "deliveryAvailable": False, "settlementSeconds": 5,
                   "deadlineKind": "cooperative", "deadlineSeconds": 120, "componentCap": 18, "ownResourceCap": 16}
    if not isinstance(value["constraints"], dict) or set(value["constraints"]) != set(constraints):
        raise Refusal("identity authority limits missing")
    for key, expected in constraints.items():
        if type(value["constraints"][key]) is not type(expected) or value["constraints"][key] != expected:
            raise Refusal("identity authority limit changed")
    return value


class Capture:
    def __init__(self):
        self.output = bytearray()
        self.pending = bytearray()
        self.report = None
        self.code = None

    def feed(self, chunk):
        if len(self.output) + len(chunk) > MAX_CAPTURE:
            raise Refusal("instrumentation output limit")
        self.output.extend(chunk)
        self.pending.extend(chunk)
        while b"\n" in self.pending:
            line, _, self.pending = self.pending.partition(b"\n")
            self.line(line.rstrip(b"\r"))
        if len(self.pending) > MAX_LINE:
            raise Refusal("instrumentation line limit")

    def line(self, line):
        if len(line) > MAX_LINE:
            raise Refusal("instrumentation line limit")
        prefix = b"INSTRUMENTATION_RESULT: reminder_probe_report="
        if line.startswith(prefix):
            if self.report is not None or len(line) - len(prefix) > MAX_REPORT:
                raise Refusal("duplicate or oversized report")
            self.report = decode(line[len(prefix):])
        elif line.startswith(b"INSTRUMENTATION_CODE: "):
            if self.code is not None:
                raise Refusal("duplicate completion code")
            try:
                self.code = int(line.split(b": ", 1)[1])
            except ValueError as error:
                raise Refusal("invalid completion code") from error
        elif line.startswith((b"INSTRUMENTATION_FAILED", b"INSTRUMENTATION_ABORTED")):
            raise Refusal("instrumentation failed")

    def finish(self):
        if self.pending:
            self.line(bytes(self.pending))
        if self.report is None or self.code != -1:
            raise Refusal("successful instrumentation report required")
        return self.report


def instrument(adb, args, output):
    extras = {"probeOptIn": "REMINDER_IDENTITY_V2", "probeRunId": args.run_id, "expectedBootId": BOOT,
              "expectedAppSha256": args.expected_app_sha256, "expectedTestSha256": args.expected_test_sha256,
              "expectedSignerSha256": SIGNER}
    tokens = ["am", "instrument", "--user", "0", "-w", "-r"]
    for key, value in extras.items():
        tokens += ["-e", key, value]
    tokens.append(COMPONENT)
    process = subprocess.Popen(adb.prefix + ["shell", shlex.join(tokens)], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, bufsize=0, start_new_session=True)
    exclusive(output / "client-process.json", {"hostAdbPid": process.pid, "component": COMPONENT})
    chunks = queue.Queue(maxsize=16)
    def read():
        try:
            while True:
                chunk = process.stdout.read(4096)
                if not chunk:
                    break
                chunks.put(chunk)
        finally:
            chunks.put(None)
    threading.Thread(target=read, daemon=True).start()
    capture, failure = Capture(), None
    next_notice = time.monotonic() + 60
    while True:
        try:
            chunk = chunks.get(timeout=15)
        except queue.Empty:
            if time.monotonic() >= next_notice:
                print("Observing the same identity process; no timeout kill/retry.", flush=True)
                next_notice = time.monotonic() + 60
            continue
        if chunk is None:
            break
        if failure is None:
            try:
                capture.feed(chunk)
            except Refusal as error:
                failure = error
        # Drain after a refusal without retaining unbounded output or killing the target.
    rc = process.wait()
    exclusive(output / "instrumentation.stdout.txt", bytes(capture.output), raw=True)
    exclusive(output / "client-exit.json", {"returncode": rc, "targetExitNotImplied": True})
    report_stored = capture.report is not None
    if report_stored:
        exclusive(output / "instrumentation.report.json", capture.report)
    if failure is not None:
        raise failure
    report = capture.finish()
    if not report_stored:
        exclusive(output / "instrumentation.report.json", report)
    if rc != 0:
        raise Refusal("ADB instrumentation client failed")
    return report


def parser():
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("mode", choices=["identity-capabilities"])
    root.add_argument("--serial", required=True, choices=[SERIAL])
    root.add_argument("--expected-avd", required=True, choices=[AVD])
    root.add_argument("--expected-boot-id", required=True, choices=[BOOT])
    root.add_argument("--app-apk", required=True, type=Path)
    root.add_argument("--expected-app-sha256", required=True)
    root.add_argument("--test-apk", required=True, type=Path)
    root.add_argument("--expected-test-sha256", required=True)
    root.add_argument("--expected-signer-sha256", required=True, choices=[SIGNER])
    root.add_argument("--run-id", required=True)
    root.add_argument("--output-dir", required=True, type=Path)
    root.add_argument("--adb", default="adb")
    return root


def run(args):
    exact_uuid(args.run_id)
    if args.mode != "identity-capabilities" or args.expected_signer_sha256 != SIGNER:
        raise Refusal("only reviewed identity capture is available")
    app_path, app = apk(args.app_apk, args.expected_app_sha256)
    test_path, test = apk(args.test_apk, args.expected_test_sha256)
    if app_path == test_path or app["sha256"] == test["sha256"]:
        raise Refusal("distinct APK pair required")
    args.output_dir.mkdir(mode=0o700, parents=False, exist_ok=False)
    pair = {"app": app, "test": test}
    state = {"schema": 1, "mode": args.mode, "runId": args.run_id, "status": "FAIL", "apks": pair,
             "guard": None, "probeReport": None, "targetExit": "UNPROVEN", "nativeInstallOrPermissionCommandsAvailable": False}
    exclusive(args.output_dir / "metadata.json", state)
    adb = Adb(args.adb, args.serial)
    try:
        state["guard"] = guard(adb, args)
        adb.require_no_existing_target()
        guard(adb, args)
        if sha256(app_path) != app["sha256"] or sha256(test_path) != test["sha256"]:
            raise Refusal("APK pair changed before launch")
        report = instrument(adb, args, args.output_dir)
        state["probeReport"] = report
        guard(adb, args)
        require_report(report, args.run_id, pair)
        for attempt in range(10):
            guard(adb, args)
            absent = adb.exact_pid_absent(report["targetProcess"])
            guard(adb, args)
            if absent:
                state["targetExit"] = {"status": "EXACT_REPORTED_PID_ABSENT", "targetProcess": report["targetProcess"]}
                break
            if attempt < 9:
                time.sleep(0.1)
        else:
            raise Refusal("reported probe process remains; no kill/retry")
        if sha256(app_path) != app["sha256"] or sha256(test_path) != test["sha256"]:
            raise Refusal("APK pair changed during capture")
        state["status"] = "PASS"
    except Exception as error:
        state["failureClass"] = type(error).__name__
        raise
    finally:
        exclusive(args.output_dir / "result.json", state)
    return state


if __name__ == "__main__":
    try:
        result = run(parser().parse_args())
        print(json.dumps({"status": result["status"], "runId": result["runId"], "scope": "identity-only"}))
    except (Refusal, OSError, UnicodeError, subprocess.SubprocessError) as error:
        raise SystemExit("Identity capture refused: " + type(error).__name__)
