# Seven-day work/personal beta — NOT STARTED

Start only after integrated M0–M9 behaviour can be exercised. Seven actual consecutive days including
a weekend are required; seven scripted iterations in one day are not a beta. Use dedicated authorized
accounts/fixtures for recorded evidence; private daily events must never enter committed logs/images.

- Candidate commit / version / APK hash: NOT_RUN
- Stable signing/upgrade compatibility verified: NOT_RUN
- Start/end dates / tester alias: NOT_RUN
- Device/API family / reminder permissions and health: NOT_RUN

| Day | Date | Work/personal use and eligible-alert observations | Defects / evidence | Status |
|---|---|---|---|---|
| 1 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| 2 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| 3 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| 4 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| 5 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| 6 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| 7 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |

Required within the actual beta window:

- [ ] A workday and weekend/personal planning session across selected calendars and views.
- [ ] Native creation/edit/delete and date/search navigation without routine external-app editing.
- [ ] Recurring appointment, individual exception and reviewed future/all scope change.
- [ ] Authorized two-account invitation, RSVP, reschedule and cancellation with remote observations.
- [ ] Offline period followed by reconnection; no duplicates or lost changes.
- [ ] A remote edit arriving while Daylight is open; safe conflict/draft behaviour.
- [ ] Eligible reminder with UI absent, snooze, reboot recovery and timezone-change checks.
- [ ] Same-key APK upgrade with retained settings/draft/snooze; fresh-install check separately.
- [ ] Accessibility/font/RTL checks on the candidate; no unreachable crowded events.
- [ ] Every issue linked to a synthetic reproduction, fixing commit and verified re-test.

Release blockers: data loss, duplicate writes, wrong recurrence scope, failed meeting propagation or
missed eligible reminders. A beta finding requiring a new candidate needs re-test plus an explicit
record of whether the seven-day window restarts; never combine incompatible candidate evidence as one
silently passed run. Overall disposition stays pending until the lead reviews the complete evidence.

Durable release gates (not satisfied by this checklist):

- [ ] Full integrated tests/debug build/lint and inspected synthetic screenshot baselines.
- [ ] All required Google/device matrix cases PASS, or explicit approved scope change; no hidden gaps.
- [ ] Seven-day beta completed without unresolved release blockers.
- [ ] Stable signing material outside Git and verified fresh-install/upgrade path.
- [ ] GitHub release contains installable APK, SHA-256, version and release notes with known limitations.
- [ ] Actual release download/install verified independently of preview or temporary CI artifacts.
