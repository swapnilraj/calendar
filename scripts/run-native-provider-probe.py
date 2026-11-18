#!/usr/bin/env python3
"""Guarded host for the opt-in native LOCAL CalendarProvider probe (stdlib only)."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shlex
import subprocess
import sys
import threading
import time
import uuid
import zipfile

APP = "dev.swapnilraj.calendar"
COMPONENT = "dev.swapnilraj.calendar.providerprobe/dev.swapnilraj.calendar.probe.NativeCalendarProviderProbe"
OWNED_AVD = "daylight_google_reference_userdebug_api35"
MAX_STDOUT = 1024 * 1024
MAX_LINE = 128 * 1024
MAX_REPORT = 64 * 1024
SHA = re.compile(r"[0-9a-f]{64}\Z")


class Refusal(RuntimeError):
    pass


def canonical_uuid(value):
    try:
        if isinstance(value, str) and str(uuid.UUID(value)) == value:
            return value
    except (ValueError, AttributeError):
        pass
    raise argparse.ArgumentTypeError("must be a canonical lowercase UUID")


def emulator_serial(value):
    if not re.fullmatch(r"emulator-[1-9][0-9]{0,4}", value):
        raise argparse.ArgumentTypeError("must be an explicit emulator-N serial; physical devices are refused")
    return value


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Refusal("duplicate JSON key")
        result[key] = value
    return result


def decode_json(raw):
    try:
        return json.loads(raw, object_pairs_hook=unique_object)
    except (ValueError, UnicodeError) as error:
        raise Refusal("invalid JSON") from error


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def apk_metadata(path):
    path = path.resolve(strict=True)
    if not path.is_file() or not zipfile.is_zipfile(path):
        raise Refusal("APK must be an existing regular ZIP/APK file")
    with zipfile.ZipFile(path) as archive:
        if "AndroidManifest.xml" not in archive.namelist() or "classes.dex" not in archive.namelist():
            raise Refusal("APK manifest or code is missing")
    return path, {"name": path.name, "sha256": sha256(path), "bytes": path.stat().st_size}


def exclusive_json(path, value):
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, indent=2, sort_keys=True, ensure_ascii=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def exclusive_bytes(path, value):
    with path.open("xb") as stream:
        os.chmod(path, 0o600)
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())


class Adb:
    def __init__(self, executable, serial):
        self.prefix = [executable, "-s", serial]

    def read(self, tokens, shell=False):
        argv = self.prefix + (["shell", shlex.join(tokens)] if shell else tokens)
        # These commands are read-only. A timeout stops only this local read client.
        result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=False)
        if result.returncode != 0 or len(result.stdout) > 16384:
            raise Refusal("ADB read/guard failed")
        return result.stdout.decode("utf-8", errors="strict").strip()

    def mutation(self, tokens, shell=False):
        argv = self.prefix + (["shell", shlex.join(tokens)] if shell else tokens)
        # No automatic timeout/kill/retry of an install or permission operation.
        result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
        if result.returncode != 0 or len(result.stdout) > 65536:
            raise Refusal("normal ADB install/permission operation failed; no fallback attempted")


def guard_device(adb, args):
    if emulator_serial(args.serial) != args.serial or args.expected_avd != OWNED_AVD:
        raise Refusal("unapproved emulator identity")
    if adb.read(["get-state"]) != "device":
        raise Refusal("explicit emulator is not online")
    names = [line.strip() for line in adb.read(["emu", "avd", "name"]).splitlines() if line.strip() and line.strip() != "OK"]
    if names != [args.expected_avd]:
        raise Refusal("AVD name mismatch")
    if adb.read(["getprop", "ro.kernel.qemu"], shell=True) != "1":
        raise Refusal("target is not a QEMU emulator")
    boot = adb.read(["cat", "/proc/sys/kernel/random/boot_id"], shell=True)
    if boot != args.expected_boot_id:
        raise Refusal("emulator boot UUID mismatch")
    if adb.read(["am", "get-current-user"], shell=True) != "0":
        raise Refusal("owned emulator must have Android user 0 active")
    fingerprint = adb.read(["getprop", "ro.build.fingerprint"], shell=True)
    if not fingerprint or "\n" in fingerprint or len(fingerprint) > 4096:
        raise Refusal("invalid build fingerprint")
    return {"serial": args.serial, "avd": args.expected_avd, "bootId": boot, "buildFingerprint": fingerprint, "qemu": "1", "androidUser": 0}


def validate_descriptor(value):
    keys = {"package", "versionCode", "baseSha256", "splits", "signers", "signingHistory", "buildFingerprint", "sdk", "securityPatch"}
    if not isinstance(value, dict) or set(value) != keys:
        raise Refusal("identity descriptor shape is unsupported")
    if not isinstance(value["package"], str) or not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", value["package"]):
        raise Refusal("invalid provider package")
    if type(value["versionCode"]) is not int or value["versionCode"] < 1 or not SHA.fullmatch(value["baseSha256"]):
        raise Refusal("invalid provider version/base digest")
    if type(value["sdk"]) is not int or value["sdk"] < 29:
        raise Refusal("unsupported provider SDK")
    if not isinstance(value["buildFingerprint"], str) or not value["buildFingerprint"] or "\n" in value["buildFingerprint"]:
        raise Refusal("invalid pinned build fingerprint")
    if not isinstance(value["securityPatch"], str):
        raise Refusal("invalid security patch descriptor")
    splits = value["splits"]
    if not isinstance(splits, list) or len(splits) > 32:
        raise Refusal("invalid provider split descriptor")
    names = []
    for item in splits:
        if not isinstance(item, dict) or set(item) != {"name", "sha256"} or not isinstance(item["name"], str):
            raise Refusal("invalid split entry")
        if not item["name"] or "/" in item["name"] or "\\" in item["name"] or not SHA.fullmatch(item["sha256"]):
            raise Refusal("invalid split name/digest")
        names.append(item["name"])
    if names != sorted(set(names)):
        raise Refusal("split entries must have unique sorted names")
    for key, minimum, maximum in (("signers", 1, 16), ("signingHistory", 0, 64)):
        items = value[key]
        if not isinstance(items, list) or not minimum <= len(items) <= maximum or any(not isinstance(item, str) or not SHA.fullmatch(item) for item in items):
            raise Refusal("invalid signing descriptor")
    if value["signers"] != sorted(set(value["signers"])):
        raise Refusal("current signers must be unique and sorted")
    return value


def require_pass(report, mode, run_id):
    if not isinstance(report, dict) or report.get("schema") != 1 or report.get("runId") != run_id or report.get("status") != "PASS":
        raise Refusal("native probe did not report a matching PASS; inspect retained cleanup status")
    expected_scope = "native-provider-identity-only" if mode == "identity" else "native-local-provider-only"
    if report.get("scope") != expected_scope or report.get("productionProfileActivation") != "NONE":
        raise Refusal("native report scope does not match the requested operation")
    descriptor = validate_descriptor(report.get("providerIdentity"))
    if mode == "fixtures":
        if report.get("cleanup", {}).get("status") != "VERIFIED_CALENDAR_AND_TRACKED_IDS_ABSENT":
            raise Refusal("fixture cleanup absence was not verified")
        cases = report.get("cases")
        expected = {(seed, limit, variant) for seed in ("new-york-spring", "lord-howe-half-hour", "utc-date-two-day")
                    for limit in ("COUNT", "UNTIL") for variant in ("RULE_ONLY_CONTROL", "EXACT_CONTEXT", "ROLLBACK")}
        if not isinstance(cases, list) or len(cases) != 18:
            raise Refusal("fixture PASS is missing the 18-case matrix")
        seen = set()
        for case in cases:
            if not isinstance(case, dict):
                raise Refusal("invalid fixture case")
            key = (case.get("seed"), case.get("limit"), case.get("variant"))
            if key not in expected or key in seen:
                raise Refusal("fixture matrix is duplicated or incomplete")
            seen.add(key)
            if "requestedEndZone" not in case or "storedEndZone" not in case or case["requestedEndZone"] != case["storedEndZone"]:
                raise Refusal("initial end-zone preservation did not match")
            variants = [case.get("before")]
            if key[2] != "RULE_ONLY_CONTROL":
                variants.append(case.get("after"))
            for summary in variants:
                if not isinstance(summary, dict) or any(summary.get(k) != v for k, v in (("count", 8), ("duplicates", 0), ("missingExpected", 0), ("unexpectedBounds", 0))):
                    raise Refusal("fixture occurrence bounds/count verification is incomplete")
            if key[2] == "EXACT_CONTEXT" and (case.get("outcome") != "PASS" or case.get("prefixCount") != 3 or case.get("tailCount") != 5):
                raise Refusal("exact-context split verification is incomplete")
            if key[2] == "ROLLBACK" and case.get("rollback") != "VERIFIED_FINAL_ASSERTION":
                raise Refusal("transaction rollback was not verified")
    return descriptor


class Capture:
    def __init__(self):
        self.stdout = bytearray()
        self.pending = bytearray()
        self.truncated = False
        self.line_overflow = False
        self.dropping = False
        self.reports = []
        self.codes = []
        self.progress_count = 0

    def line(self, raw):
        report_prefix = b"INSTRUMENTATION_RESULT: probe_report="
        code_prefix = b"INSTRUMENTATION_CODE: "
        if raw.startswith(report_prefix):
            payload = raw[len(report_prefix):].strip()
            if len(payload) > MAX_REPORT:
                raise Refusal("native report exceeded 64 KiB")
            if self.reports:
                raise Refusal("duplicate native report")
            self.reports.append(decode_json(payload))
        elif raw.startswith(code_prefix):
            try:
                if self.codes:
                    raise Refusal("duplicate instrumentation completion code")
                self.codes.append(int(raw[len(code_prefix):].strip()))
            except ValueError as error:
                raise Refusal("invalid instrumentation completion code") from error
        elif raw.startswith(b"INSTRUMENTATION_STATUS: probe_phase=") and self.progress_count < 128:
            self.progress_count += 1
            print(raw[:512].decode("utf-8", errors="replace"), flush=True)

    def feed(self, chunk):
        self.stdout.extend(chunk)
        if len(self.stdout) > MAX_STDOUT:
            self.truncated = True
            del self.stdout[:-MAX_STDOUT]
        for piece in chunk.splitlines(keepends=True):
            ended = piece.endswith(b"\n")
            if not self.dropping:
                self.pending.extend(piece)
                if len(self.pending) > MAX_LINE:
                    self.line_overflow = True
                    self.pending.clear()
                    self.dropping = True
            if ended:
                line = bytes(self.pending).rstrip(b"\r\n") if not self.dropping else None
                self.pending.clear()
                self.dropping = False
                if line is not None:
                    self.line(line)

    def finish(self):
        if self.pending and not self.dropping:
            self.line(bytes(self.pending).rstrip(b"\r\n"))
        if self.truncated or self.line_overflow or len(self.reports) != 1 or len(self.codes) != 1:
            raise Refusal("instrumentation output was truncated, duplicated or incomplete")
        return self.reports[0], self.codes[0]


def instrument(adb, run_id, mode, pins, output, label, end_zone_mode="distinct"):
    extras = {"probeOptIn": "IDENTITY_ONLY_V1" if mode == "identity" else "LOCAL_FIXTURE_V1", "probeRunId": run_id}
    if mode == "fixtures":
        extras.update({"probeEndZoneMode": end_zone_mode, "expectedBuildFingerprint": pins["buildFingerprint"], "expectedProviderPackage": pins["package"],
                       "expectedProviderVersion": str(pins["versionCode"]), "expectedBaseSha256": pins["baseSha256"],
                       "expectedSplitsJson": json.dumps(pins["splits"], separators=(",", ":")),
                       "expectedSignersJson": json.dumps(pins["signers"], separators=(",", ":")),
                       "expectedSigningHistoryJson": json.dumps(pins["signingHistory"], separators=(",", ":"))})
    tokens = ["am", "instrument", "--user", "0", "-w", "-r"]
    for key, value in extras.items():
        tokens += ["-e", key, value]
    tokens.append(COMPONENT)
    # One remote shell string, with every token quoted by shlex; no host shell is used.
    argv = adb.prefix + ["shell", shlex.join(tokens)]
    process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               start_new_session=(os.name != "nt"), bufsize=0)
    exclusive_json(output / (label + ".process.json"), {"hostAdbPid": process.pid, "component": COMPONENT, "mode": mode})
    chunks = queue.Queue(maxsize=16)

    def read_output():
        try:
            while True:
                chunk = process.stdout.read(4096)
                if not chunk:
                    break
                chunks.put(chunk)
        finally:
            chunks.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    capture = Capture()
    parse_error = None
    next_notice = time.monotonic() + 60
    while True:
        try:
            chunk = chunks.get(timeout=15)
        except queue.Empty:
            if time.monotonic() >= next_notice:
                print("Instrumentation remains running; monitoring only, with no timeout kill or retry.", flush=True)
                next_notice = time.monotonic() + 60
            continue
        if chunk is None:
            break
        try:
            capture.feed(chunk)
        except Refusal as error:
            parse_error = error
            # Keep draining this same process; never kill/retry an uncertain native batch.
    returncode = process.wait()
    exclusive_bytes(output / (label + ".stdout.txt"), bytes(capture.stdout))
    if parse_error:
        raise parse_error
    report, code = capture.finish()
    exclusive_json(output / (label + ".report.json"), report)
    if returncode != 0 or code != -1:
        raise Refusal("instrumentation returned failure; its parsed report and cleanup status were retained")
    return report


def parser():
    root = argparse.ArgumentParser(description=__doc__)
    sub = root.add_subparsers(dest="mode", required=True)
    for mode in ("identity", "fixtures"):
        item = sub.add_parser(mode, help="read-only provider descriptor" if mode == "identity" else "explicit 18-case LOCAL fixture run")
        item.add_argument("--serial", required=True, type=emulator_serial)
        item.add_argument("--expected-avd", required=True, choices=[OWNED_AVD])
        item.add_argument("--expected-boot-id", required=True, type=canonical_uuid)
        item.add_argument("--app-apk", required=True, type=Path)
        item.add_argument("--test-apk", required=True, type=Path)
        item.add_argument("--output-dir", required=True, type=Path, help="new directory; existing paths/artifacts are never overwritten")
        item.add_argument("--run-id", type=canonical_uuid, default=str(uuid.uuid4()))
        item.add_argument("--adb", default="adb")
        if mode == "fixtures":
            item.add_argument("--identity-report", required=True, type=Path, help="prior successful identity-mode result.json from this same boot and APK pair")
            item.add_argument("--end-zone-mode", choices=["distinct", "null"], default="distinct", help="no automatic fallback after a rejected distinct zone")
    return root


def run(args):
    app_path, app_meta = apk_metadata(args.app_apk)
    test_path, test_meta = apk_metadata(args.test_apk)
    if app_path == test_path or app_meta["sha256"] == test_meta["sha256"]:
        raise Refusal("app and test APKs must be distinct reviewed artifacts")
    apks = {"app": app_meta, "test": test_meta}
    prior = None
    pins = None
    if args.mode == "fixtures":
        raw = args.identity_report.read_bytes()
        if len(raw) > MAX_STDOUT:
            raise Refusal("identity artifact is oversized")
        prior = decode_json(raw)
        if not isinstance(prior, dict) or prior.get("schema") != 1 or prior.get("mode") != "identity" or prior.get("status") != "PASS":
            raise Refusal("fixtures require a prior successful identity-mode result.json")
        canonical_uuid(prior.get("runId"))
        pins = require_pass(prior.get("probeReport"), "identity", prior.get("runId"))
        if args.run_id == prior.get("runId") or prior.get("apks") != apks:
            raise Refusal("fixtures require a new run UUID and the same frozen APK pair")
        expected = prior.get("guard", {})
        if expected.get("qemu") != "1" or expected.get("buildFingerprint") != pins["buildFingerprint"] or type(expected.get("androidUser")) is not int or expected["androidUser"] != 0:
            raise Refusal("identity artifact guard/build evidence is incomplete")
        if (expected.get("serial"), expected.get("avd"), expected.get("bootId")) != (args.serial, args.expected_avd, args.expected_boot_id):
            raise Refusal("identity artifact belongs to another emulator or boot")
    args.output_dir.mkdir(mode=0o700, parents=False, exist_ok=False)
    state = {"schema": 1, "mode": args.mode, "runId": args.run_id, "apks": apks,
             "component": COMPONENT, "status": "FAIL", "guard": None, "probeReport": None,
             "expectedGuard": {"serial": args.serial, "avd": args.expected_avd, "bootId": args.expected_boot_id, "androidUser": 0},
             "acceptanceScope": "provider identity only" if args.mode == "identity" else "native LOCAL fixture matrix only",
             "googleWorkspaceRoundTrip": "NOT_RUN", "productionProfileActivation": "NONE"}
    if args.mode == "fixtures":
        state["identityArtifactSha256"] = sha256(args.identity_report)
        state["endZoneMode"] = args.end_zone_mode
    exclusive_json(args.output_dir / "host-metadata.json", {k: v for k, v in state.items() if k != "probeReport"})
    adb = Adb(args.adb, args.serial)

    def guarded_instrument(mode, descriptor, label):
        guard_device(adb, args)
        try:
            return instrument(adb, args.run_id, mode, descriptor, args.output_dir, label,
                              getattr(args, "end_zone_mode", "distinct"))
        finally:
            # Also perform the post-check when instrumentation reports failure or malformed output.
            state["guard"] = guard_device(adb, args)

    try:
        initial_guard = guard_device(adb, args)
        state["guard"] = initial_guard
        if pins and pins["buildFingerprint"] != initial_guard["buildFingerprint"]:
            raise Refusal("identity artifact build pin does not match the guarded boot")
        for path, metadata in ((app_path, app_meta), (test_path, test_meta)):
            guard_device(adb, args)
            if sha256(path) != metadata["sha256"]:
                raise Refusal("APK changed before install")
            adb.mutation(["install", "--user", "0", "-r", str(path)])
            if sha256(path) != metadata["sha256"]:
                raise Refusal("APK changed during install; no retry attempted")
        guard_device(adb, args)
        if args.mode == "fixtures":
            fresh = guarded_instrument("identity", None, "identity-preflight")
            state["identityPreflightReport"] = fresh
            guard_device(adb, args)
            if require_pass(fresh, "identity", args.run_id) != pins:
                raise Refusal("provider binary/build/signing identity changed before permission grants")
            for permission in ("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"):
                guard_device(adb, args)
                adb.mutation(["pm", "grant", "--user", "0", APP, permission], shell=True)
        guard_device(adb, args)
        report = guarded_instrument(args.mode, pins, "instrumentation")
        state["probeReport"] = report
        state["guard"] = guard_device(adb, args)
        actual = require_pass(report, args.mode, args.run_id)
        if actual["buildFingerprint"] != state["guard"]["buildFingerprint"] or (pins and actual != pins):
            raise Refusal("completed report does not match the guarded pinned provider/build")
        state["status"] = "PASS"
    except KeyboardInterrupt:
        state["failure"] = "HOST_INTERRUPTED: instrumentation may remain active; do not retry or perform external cleanup"
        raise
    except Exception as error:
        state["failure"] = str(error) if isinstance(error, Refusal) else type(error).__name__
        raise
    finally:
        native_report = args.output_dir / "instrumentation.report.json"
        if state["probeReport"] is None and native_report.is_file():
            state["probeReport"] = decode_json(native_report.read_bytes())
        exclusive_json(args.output_dir / "result.json", state)


def main():
    args = parser().parse_args()
    try:
        run(args)
    except KeyboardInterrupt:
        print("Host monitoring interrupted. No force-stop/retry/cleanup was issued; inspect the existing run before any next action.", file=sys.stderr)
        return 130
    except Exception as error:
        message = str(error) if isinstance(error, (Refusal, FileExistsError, FileNotFoundError)) else type(error).__name__
        print("Probe refused/failed: " + message, file=sys.stderr)
        return 1
    print("Recorded " + args.mode + " evidence in " + str(args.output_dir / "result.json"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
