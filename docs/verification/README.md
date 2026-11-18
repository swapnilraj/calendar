# Device and Google compatibility acceptance

This is the controlled M0/device verification packet. **No device/account workflow has been run by
adding these documents.** All matrix rows start `NOT_RUN`. A successful shell readiness check is
transport evidence only, not calendar-provider, cloud-sync, invitation or notification evidence.

Use [the matrix](GOOGLE_COMPATIBILITY.md), copy [the evidence template](EVIDENCE_TEMPLATE.md) for each
run, and complete [the seven-day beta](BETA_CHECKLIST.md) after integrated implementation exists.
Required behaviour is in [SPEC](../SPEC.md); current project status remains in
[DAILY_DRIVER_PROGRESS](../DAILY_DRIVER_PROGRESS.md).

## Prerequisites and safe setup

1. Use a dedicated authorized test phone and two dedicated test identities: **P** (personal Google)
   and **W** (Workspace). Use display aliases only in committed evidence. Employer restrictions must
   permit the test; this packet does not bypass managed-profile policy.
2. Both accounts must use supported same-profile Android calendar data. Keep Google calendar account
   synchronization enabled. Create separate disposable calendars named with synthetic test labels.
   Only those calendars and those two recipients may receive fixture writes/invitations.
3. The tester explicitly confirms permission to create/update/delete fixtures and send test invitations
   between P and W. Merely connecting adb does not authorize changes to real calendar data. If dedicated
   accounts are unavailable, leave relevant rows `NOT_RUN` or `BLOCKED`; do not use actual meetings.
4. Install a known APK and record its commit/version and artifact hash. Confirm time/date/timezone are
   correct and calendar sync is enabled. Record Android API, generic OS/vendor family, and sync-adapter
   app version manually. Do not collect serials, emails, tokens, account dumps or provider database dumps.
5. Use Google Calendar web in the test identities as independent remote observers. A local app or
   CalendarContract row is not server evidence. Sign-in is performed by the tester, never recorded.
6. Enable Daylight read permission; request write permission when the workflow needs it. For reminders,
   enable notification channel/permission and precise-alarm access where required. Disable competing
   calendar notifications for the reminder cases, leaving calendar sync enabled.
7. Phone/device cases are required on a Google-enabled physical phone. An emulator may supplement
   automated checks but does not satisfy physical-phone alarm, TalkBack or power-management acceptance.
   Cover API 29 plus permission transitions on API 31/33+ and the current supported Android release;
   use multiple controlled devices/emulators as needed and identify which rows each run covers.

## Readiness command

Run `scripts/check-device-readiness.sh`. It only invokes `adb devices`, suppresses raw output and
reports redacted transport readiness. It does not install an APK, alter permissions, query event data,
force a device into Doze, create fixtures or determine whether the transport is physical/emulated.
ADB itself may start its ordinary local daemon when invoked.

| Exit | Meaning |
|---|---|
| 0 | Exactly one authorized adb transport; manual prerequisites remain unverified |
| 1 | adb executable unavailable |
| 2 | No connected device |
| 3 | Offline/unauthorized/unavailable transport present |
| 4 | Multiple authorized transports; selection needed |
| 5 | adb command failed or timed out |

The tool makes no usage-limit or account-entitlement diagnosis. Device readiness and cloud-computer
availability are separate from a user's subscription or remaining usage.

## Evidence rules

- `NOT_RUN`: no execution. `BLOCKED`: attempted but a prerequisite prevents verification, with reason.
  `FAIL`: observed required behaviour is incorrect. `PASS`: every assertion and required remote/device
  observation passed. Record times and bounded sync observations; never convert an unobserved result
  into a pass because a timeout elapsed.
- A missing/unimplemented feature stays pending, not a mocked pass. Separate pure/UI test output from
  local-provider, remote-server, and actual notification observations.
- Use synthetic fixture labels and alias P/W only. Screenshots must contain only dedicated fixtures;
  crop/redact unrelated account identifiers and notifications before attaching. Never commit private
  event titles, addresses, attendee emails, auth artifacts, raw adb devices output or logcat dumps.
- Restore temporarily changed connectivity, timezone, font size, notification and power settings after
  testing. Delete only the disposable fixtures after recording results; verify cleanup remotely.
- Data loss, duplicate writes, incorrect recurrence scopes, failed meeting propagation or missed eligible
  reminders block release. Report external checks as pending when no phone/accounts are available.
