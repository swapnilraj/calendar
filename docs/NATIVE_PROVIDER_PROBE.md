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

## App writer receipt6 mode — source/host verification only; native run NOT_RUN

`app-create-recovery` exercises the real `CalendarWriter`, a fresh explicit `CalendarReader`, and
an exclusive run-specific `CalendarLocalStore`. The instrumentation still substitutes a bare
`Application`; it never starts production app/reminder/widget initialization or collects calendar
flows. There are no global calendar scans, accounts, production profile admissions or new production
APIs. The new mode requires API35 or later on the **same single owned AVD** above. A previous identity
artifact is reusable only when its complete provider/build/boot pins and reviewed APK pair match.
Rebuild both APKs from the reviewed checkout with `-Pdaylight.providerProbe=true`, then capture a
new identity report if the APK pair changed. Building does not authorize execution.

After lead review of source, host tests, both built APKs and the current owned boot/provider pins,
the explicit command is (example only, not recorded execution):

```sh
python3 scripts/run-native-provider-probe.py app-create-recovery \
  --serial emulator-5554 \
  --expected-avd daylight_google_reference_userdebug_api35 \
  --expected-boot-id '<same-current-canonical-boot-uuid>' \
  --app-apk /reviewed/pair/app-debug.apk \
  --test-apk /reviewed/pair/app-debug-androidTest.apk \
  --identity-report /reviewed/same-pair-identity/result.json \
  --output-dir /tmp/daylight-app-writer-new-unused-run
```

This is one explicitly requested three-phase protocol, not automatic retries:

Before calendar permission grants or fixture creation, app mode requests an optional native target
process witness in its fresh read-only identity preflight. That report contains only the exact app
process name, `Process.myPid()` and `Process.getStartElapsedRealtime()` alongside the existing binary
identity. The ordinary identity/18-case modes do not request this witness and keep their original
behavior. This preflight performs no calendar query or mutation.

1. **CREATE_STAGE:** create an exclusive UUID-specific synthetic LOCAL calendar with explicit ALERT
   support/max2 and six new app operations. Canonical controls cover timed one-off with10/30-minute
   alerts, UTC-date two-day one-off without alerts, COUNT3 timed recurring with10/30-minute alerts,
   and COUNT3 UTC-date recurring without alerts. Two more timed operations (one-off and recurring)
   deliberately fail test-wrapper queries after the real native batch returns its original,
   unmodified ACKs. These two must retain exact known-master, complete child-ACK COMMITTED receipt6
   material; the other four must be VERIFIED. Every operation is submitted exactly once.
2. Only a complete `STAGED` report plus separately confirmed native target-PID absence permits the host to
   revoke **only** the target app's WRITE_CALENDAR using `pm revoke --user 0`. A separate fresh
   instrumentation process must observe READ granted and WRITE denied, reopen the same exclusive
   store, and recover each operation twice. The existing authoring probe throws if invoked. The
   SDK-only forwarding observer rejects all provider mutation attempts during recovery and audits
   exact-calendar/ID/UID selection, minimal binding before payloads, raw reminder membership and
   guarded acknowledged reminder-item reads. Successful proof requires24 minimal binding queries,
   24 membership queries,16 guarded child queries and zero mutation attempts. Generated UID, frozen
   plan digest, native first-instance bounds, parent and child identities must remain exact.
3. Only complete `RECOVERED`, separately confirmed target-PID absence, and exact same-run ownership proof permit WRITE to be granted again,
   **solely** for a final fresh-process CLEANUP phase. The existing guarded calendar-ID/account/type/
   name/owner assertion and exact owned-calendar delete remain in use. All6 tracked event IDs,
   fixture namespace, exclusive local database/sidecars and private run manifest must be absent.
   Cleanup has its own admission fence, including the outer finally path: an admitted, completed CLEANUP entry, RECOVERED state, and all6 VERIFIED cases each recovered twice are required before any mutation. Failed/partial/unknown app runs report CLEANUP_WITHHELD and retain their fixture/artifacts. Only complete cleanup is PASS. STAGED/RECOVERED are never final acceptance.

`process.wait()` and its `.exit.json` artifact describe the **host adb client only**. They do not
establish target-process termination. For the optional identity witness and each app phase, the
host validates the native positive PID and exact `dev.swapnilraj.calendar` name before issuing only
`ps -p <reported-pid> -o PID,NAME`, bracketed by the existing owned serial/AVD/boot/user guards. At most
10 reads, with0.1-second delays between still-present observations, are allowed. Only a successful
read containing exactly the PID/NAME header and no row confirms `TARGET_PID_ABSENT`; a matching row
remains running, and a changed name, malformed output, permission failure or nonzero command exit is
unproven. There is no alternate command, arbitrary process enumeration, kill or force-stop fallback.
Each phase has a separate exclusive `.target-exit.json` artifact. Target absence is required before
WRITE revoke and cleanup-only WRITE regrant, independently of complete instrumentation/adb exit.

The owned API35 build's absent-process `ps` exit behavior has **not** yet been exercised by this
source-only packet. Some implementations may return nonzero when no PID matches; the host does not
reinterpret that failure as absence. The nonmutating identity/PID preflight checks the strict
supported behavior on the current pinned boot **before any fixture or calendar permission grant**;
unsupported behavior stops there. A still-running or unproven target never authorizes continuation.

Native phase records also bind PID, SDK start-elapsed time and exact app name into the strict
same-run manifest. RECOVER and CLEANUP retain both available prior phase tuples and require a new,
strictly later start tuple before phase work. Host reports must match the independently pinned prior
report tuples as well. A missing legacy witness, repeated phase, altered tuple or invalid numeric
identity is rejected rather than adopted. Fixed ordered six-case labels, mode/kind/fault flags,
10/30-or-zero alert mapping and exact positive Int/Long child IDs are validated on cold manifest
reads and again before cleanup admission; numeric coercion and corrupt matrices cannot authorize
deletion. These remain app-private lifecycle witnesses, not a general OS process certification.

The observer forwards the original app batch to the real CalendarProvider as one transaction;
`ContentResolver.wrap(ContentProvider)` is a public SDK API, not a replacement calendar engine.
It validates the complete authored values/backreferences against the durable receipt before
forwarding and passes returned ACK facts unchanged. Injected post-ACK read failure is explicitly
labelled synthetic fault injection, not a real device permission failure. Actual READ-only recovery
is independently checked against OS permission state in the next process. Native verification
covers receipt6's bounded **first** occurrence; this mode does not certify every later recurrence,
remote sync, physical reminder delivery or Google/Workspace behavior.

An app-private noBackup AtomicFile contains exact run/boot/provider pins, exclusive database name,
preflight absence/creation-attempt/calendar ownership, canonical ACK IDs, private UID/digest witnesses,
phase and cumulative budgets. Missing/corrupt/mismatched ownership never becomes a fresh fixture or
an inferred cleanup target. A file/store/namespace collision refuses CREATE. The same15-minute
cooperative deadline,320-query cap (20 reserved for cleanup),128-assertion cap,32-row per-query cap
and30-second query cancellation are cumulative across processes. An in-flight SDK batch is never
killed, restarted or replayed. Direct app queries cannot escape the exact fixture scope.

A failed/interrupted stage, incomplete protocol, changed binary/boot, or corrupted manifest stops
host progression without another create, UID/title/time discovery, permission workaround or blind
cleanup. In particular, failed or interrupted READ-only recovery leaves WRITE denied and the
retained fixture/store for lead inspection; it does **not** authorize cleanup or a rerun. Cleanup
failure also remains failure. There is no standalone automatic resume/cleanup command. Any later
recovery decision requires review of that same run's artifacts and ownership, never a fresh-run
substitution. Identity-only behavior and the18-case fixture matrix/pins stay unchanged; future cleanup uses the stronger bounded tracked-ID absence check described below.

Host verification (no adb or device calls):

```sh
python3 -m unittest discover -s scripts -p test_native_provider_probe.py -v
```

The new files currently establish build/host-protocol readiness only. No app-writer native run,
Google/Workspace round trip, physical-device compatibility, clone admission, or live reminder
acceptance follows from them.

## Native LOCAL evidence — 18 November 2026

The guarded API35 experiment passed all18 cases. Six RRULE-only controls reproduced stale cached
prefix instances (8 prefix plus5 tail,13 total with5 duplicates). Six exact-context splits produced
3 prefix plus5 tail instances,8 total with no duplicates. Six intentional final-assertion rollback
cases retained the original8. This covers New York DST, Lord Howe's half-hour transition and
UTC-date two-day all-day events, each with COUNT and UNTIL.

The [complete bounded host/probe report](audit/evidence/native-local-provider-api35-2026-11-18.json)
records the exact APK/provider/build/signature pins, bounds, guards and case outcomes. Cleanup
verified the owned synthetic calendar absent and all30 tracked event IDs absent **within that
calendar**, using the original calendar-scoped tracked-ID query (delete acknowledgment received;186
direct queries and79 batch assertions overall). That artifact is preserved unchanged; it does not
establish global tracked-ID absence. The corrected source now checks only the bounded tracked _id
list across the public Events collection, projecting _id alone after guarded owned-calendar deletion.
Any surviving ID blocks PASS, without reading foreign payloads or deleting a replacement row. This
stronger cleanup check has not yet been run on the native provider. The SDK probe input was built after
`43c2755`; it directly exercises native LOCAL operations, rather than running Daylight's complete
series writer or a Google sync adapter. Google/Workspace round-trips remain NOT_RUN and production
profile activation remains NONE. No runtime Google Calendar UX or device reminder claim follows.
