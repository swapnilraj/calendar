# Native LOCAL CalendarProvider probe

This opt-in instrumentation checks the installed native provider with disposable, synthetic LOCAL
calendar rows. It does not test Google/Workspace sync, Google Calendar UI, organizer/attendee
propagation, or certify/activate a production provider-clone profile. Identity-mode PASS means only
that a read-only provider descriptor was captured. Fixture-mode PASS requires the full 18-case
COUNT/UNTIL, DST, prefix-context and rollback matrix plus guarded cleanup.

The lead must build and review the target debug APK and conditional probe test APK from the same
checkout. Conditional runner configuration uses `-Pdaylight.providerProbe=true`; the component is
`dev.swapnilraj.calendar.providerprobe/dev.swapnilraj.calendar.probe.NativeCalendarProviderProbe`.
The runner substitutes a bare Application so app reminder/widget startup does not participate.
Keep APK files unchanged while the script runs. Only use the reviewed app/probe APK pair.

The stdlib-only host script accepts only the owned AVD `daylight_google_reference_userdebug_api35`
and an explicit `emulator-N` serial. Obtain the current boot UUID from the already authorized owned
emulator; never substitute an arbitrary device. Every install/grant and instrumentation phase is
preceded by serial, exact AVD, QEMU, boot-UUID and active Android user-0 checks. Installs,
permission grants and instrumentation explicitly target `--user 0`; there is no arbitrary user option.
Instrumentation is checked again afterward.
No device enumeration or physical-device fallback occurs.

First capture identity. Supply existing built APK files and an unused output directory whose parent
exists (the commands below are examples, not recorded execution):

```sh
python3 scripts/run-native-provider-probe.py identity \
  --serial emulator-5554 \
  --expected-avd daylight_google_reference_userdebug_api35 \
  --expected-boot-id '<current-canonical-boot-uuid>' \
  --app-apk /path/to/app-debug.apk \
  --test-apk /path/to/app-debug-androidTest.apk \
  --output-dir /tmp/daylight-probe-identity
```

Identity mode performs no calendar permission grant or calendar query. The script normally installs
both supplied APKs using only `adb install --user 0 -r`; matching signatures preserve existing app data.
An install/signature/version failure stops the run. There is no uninstall, downgrade, root, reboot,
cache wipe, signature workaround or alternate installation path.

Review the identity `result.json` provider binary/build, split digests, current signers and complete
signing history. Then explicitly request fixtures using that successful identity artifact, the same
APK pair and boot, a fresh run UUID, and another unused directory:

```sh
python3 scripts/run-native-provider-probe.py fixtures \
  --serial emulator-5554 \
  --expected-avd daylight_google_reference_userdebug_api35 \
  --expected-boot-id '<same-canonical-boot-uuid>' \
  --app-apk /path/to/app-debug.apk \
  --test-apk /path/to/app-debug-androidTest.apk \
  --identity-report /tmp/daylight-probe-identity/result.json \
  --output-dir /tmp/daylight-probe-fixtures
```

Fixture mode first executes a fresh read-only identity preflight and compares the complete descriptor
to the prior pins. Only afterward does it grant READ_CALENDAR and WRITE_CALENDAR to
`dev.swapnilraj.calendar`, then submit LOCAL_FIXTURE_V1 with the frozen pins. It never grants other
permissions, creates Google accounts, changes existing calendars' visibility/sync settings, or reads
logcat/account listings. The runner uses a UUID-specific LOCAL calendar and finally guards exact
calendar ID/account/type/name/owner before deleting only that fixture. The negative control need
not reproduce a bug; exact-context and rollback cases must pass. Distinct end zones are the default.
If native setup rejects that input, inspect the failed report; an explicitly requested new UUID run
can use `--end-zone-mode null`. Native mode and every seed's requested/stored end zone must match
the explicit host request. The script never falls back or retries automatically.

Artifacts are created exclusively with no overwrites: APK SHA-256/size and guard metadata, bounded
instrumentation stdout, parsed native reports, and a final `result.json`. A non-PASS, incomplete
protocol, identity change, or cleanup failure exits nonzero while retaining available reports.
Inspect the native report's cleanup status. Never automatically rerun an uncertain run or issue
external cleanup. Always use a fresh fixture UUID. The runner refuses an existing UUID namespace;
it has no registry of prior cleaned runs and cannot prove that an explicitly supplied UUID was never
used before.

The 15-minute native deadline is cooperative; direct queries have 30-second cancellation signals.
SDK `applyBatch` has no cancellation API and can outlive that deadline. The host continues monitoring
and draining bounded output without a timeout kill, provider restart, retry or forced cleanup.
Use the phase progress and process artifact to inspect the same run. Interrupting host monitoring
leaves the on-device outcome potentially unknown; it does not authorize another fixture run.
