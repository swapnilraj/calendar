# Controlled Google compatibility matrix

**Initial status: every case NOT_RUN.** This file is a procedure, not execution evidence. Run relevant
cases with P owning and W attending, then W owning and P attending. Remote checks use Google Calendar
web in the corresponding dedicated identity; never infer delivery from a local write. Respect the
setup/authorization/privacy rules in [README](README.md).

Use new independent fixtures per destructive or recurrence case. Name them `DD-<case>-<run alias>`
with no personal information. Select a future test date in the event zone, record explicit dates and
instants in evidence, and use the same range in every observing view. Record actual remote observation
time; if no remote result appears within five minutes with normal connectivity, mark the case BLOCKED
for sync investigation (or FAIL if a reproducible application defect is identified), never PASS.

| ID | Workflow | Initial status | Required evidence |
|---|---|---|---|
| G01 | Account metadata and filtering | NOT_RUN | Independent account/calendar identity and persisted app-only selection |
| G02 | Ordinary create/update/delete | NOT_RUN | One local and one remote event, exact round-trip fields and deletion |
| G03 | Offline create/edit/reconnect | NOT_RUN | Draft/local result retained, exactly one remote event after reconnect |
| G04 | External conflict and draft recovery | NOT_RUN | Preserved external change and recoverable local draft |
| G05 | Single recurring exception | NOT_RUN | Only selected original occurrence changed/deleted |
| G06 | Future-series split with moved exceptions | NOT_RUN | Past preserved, future correctly split and overrides preserved |
| G07 | Whole-series edit and recurrence boundaries | NOT_RUN | Preserved exception/count/zone/end semantics |
| G08 | Invitations and attendee responses | NOT_RUN | Organizer and attendee Google observations, both account directions |
| G09 | Organizer reschedule/guest change/cancel | NOT_RUN | Attendee receives intended changes/cancellation without duplicates |
| G10 | Timed/all-day reminder persistence | NOT_RUN | Reminder settings round-trip plus actual eligible device alert |
| G11 | Reminder lifecycle and dense delivery | NOT_RUN | Observed fire/snooze/reschedule/reboot/Doze outcomes |
| G12 | Access loss, unavailable provider, account removal | NOT_RUN | No unsafe write, stale private display or crash |
| G13 | Timezone/accessibility/entry-point correctness | NOT_RUN | Correct occurrence via views/search/widget/intents and readable layout |
| G14 | APK upgrade and fixture cleanup | NOT_RUN | Preserved settings/drafts/snooze and verified controlled cleanup |

## G01 — identity and app-local filters

1. Add two disposable calendars with the same display name, one in P and one in W; seed one synthetic
   event in each through the test account's Google Calendar UI.
2. Open Daylight, verify account grouping and independent calendar selection. Hide only P's calendar,
   switch views/search and restart the app. W's event must remain and P's must stay hidden here.
3. Verify Google Calendar's visibility/sync settings were not altered. Re-enable P here and confirm
   identity survives the duplicate names. Test rename and rediscovery using only disposable calendars.
4. Record provider-hidden/sync-disabled coverage separately; no events exposed does not prove empty
   cloud data. Never change sync settings merely to make a checkbox look successful.

## G02 — ordinary write round-trip

1. Create a 60-minute event on a browsed future date, with synthetic title/location/notes, zone,
   availability/privacy where supported, and one reminder. Press Save once; also test rapid double tap
   on a separate fixture. Verify one occurrence locally and on owner Google web.
2. Edit time/duration/notes, then reload details and every relevant planning view. Verify the same
   remote event changes, unsupported metadata remains intact, and no copy appears.
3. Delete with confirmation; verify absence locally and remotely. Repeat for an all-day event spanning
   three intended dates; the displayed inclusive dates must match Google in positive/negative zones.

## G03 — offline/reconnect

1. Disable connectivity manually on the test phone; confirm it is offline. Create one future event and
   edit a second pre-synced fixture. Distinguish draft state, provider local save and remote pending.
2. Leave/reopen Daylight; confirm local data/draft retention. Double-save must not duplicate.
3. Re-enable connectivity, keep account sync enabled and observe Google web independently. Verify one
   intended create and the correct edit, no lost fields or duplicate uploads. Record exact counts.
4. If testing process death/uncertain save, use only controlled fixtures: interrupt after submit and
   reopen. The app must reconcile uncertainty rather than blindly inserting again.

## G04 — external conflict and draft recovery

1. Open a synced fixture in the editor and make an unsaved local note edit. Change its time remotely
   from Google web, wait for device sync, then attempt local Save.
2. Verify conflict/review rather than silent loss of the remote change. Verify the draft can be
   retained/reviewed and deliberate reload is clearly different from applying it.
3. Repeat draft editing across rotation, app recreation/process death and write-permission denial;
   reopening must not save as a side effect. Do not label a permission check as a write test.

## G05–G07 — recurrence scopes

Use separate weekly six-occurrence series plus finite daily, monthly date/ordinal and all-day fixtures.

- **G05:** move only occurrence three, change its title and reminder, then verify the original third
  slot is absent and exactly one replacement exists locally/remotely. On a fresh series delete only
  occurrence three. Other five dates must remain unchanged in both views.
- **G06:** on a six-occurrence series move original occurrence six to before the original fourth date,
  keeping a distinct override. Make a separate explicit exception in occurrence two. From original
  fourth occurrence choose “this and following”, change time/pattern, and inspect all six original
  identities. The earlier exception stays; the moved future exception is partitioned by original
  identity, not its new displayed date. Unmappable overrides require review, never silent loss.
  Repeat future deletion on another series. Verify no past truncation or duplicate future instances.
- **G07:** edit the master with “all” while preserving explicit exceptions. Test COUNT and UNTIL,
  exclusions, a monthly 31st pattern, ordinal weekday, leap day and all-day dates. Compare enumerated
  expected dates with remote and local instances, not just recurrence summary text. Preserve unsupported
  syntax during unrelated field edits; unsupported pattern replacement must not silently simplify it.
- Include a DST series retaining its local weekly time. For a synthetic London fall-back span, use
  31 October 2027 `00:45Z–01:15Z`: real duration 30 minutes, two offsets. Check segment rendering and
  announced duration independently of recurrence expansion. Include Lord Howe's half-hour transition
  using the device's current timezone data and record exact chosen instants.

## G08–G09 — meetings (test invitation authorization required)

1. **G08:** owner P creates a future fixture and invites only W. Save and verify both local owner and
   owner web show one meeting; verify attendee W web receives it. RSVP Yes, then Maybe, then No from
   the eligible Daylight attendee context, checking owner web after each transition. Repeat W→P.
2. **G09:** owner reschedules a fresh accepted meeting, changes supported guest settings and removes
   the other test guest on a separate fixture. Verify attendee-side time/cancellation and organizer
   status; do not create fake third-party recipient addresses. Test recurring meeting scopes too.
3. Organizer cancels another fixture. Verify attendee sees cancellation/removal as expected. Contrast
   attendee decline with deleting an owned meeting: the UI and remote consequence must differ.
4. Record email/notification delivery only if actually observed; Calendar web attendance changes and
   email delivery are separate facts. Do not promise update suppression unless independently verified.

## G10–G11 — reminders and background recovery

1. **G10:** create a timed fixture sufficiently ahead with a 10-minute alert, and an all-day fixture
   with previous-day 09:00 alert. Verify stored settings locally and Google web; record actual device
   fire time. Changing only app display zone must not change intended all-day device-local alert time.
2. **G11:** test separate fixtures while app is not visible, its process has died, after a reboot, in
   ordinary battery saver/Doze, and after a timezone/clock change. OS force-stop is distinct from
   ordinary process death; record it separately, including any platform restriction.
3. Schedule close-together and simultaneous eligible alerts; observe platform lateness and duplicate
   behaviour. Snooze 5/10/30 minutes, recreate the process, and verify only the intended snooze remains.
4. Move/cancel a fixture before its alert. Reboot before another. Verify stale reminders are suppressed
   and eligible reminders recovered. Test an advance reminder due within the planning horizon for an
   event starting beyond that horizon; it must not be omitted because the event itself is distant.
5. Revoke notification or precise-alarm access. Verify clear degraded health, no false precise-delivery
   claim, and recovery after restoration. Re-enable all temporary settings after testing.
6. Record DND/channel state and due/fired instants. Platform restrictions need explicit evidence and
   investigation; never turn an unobserved or late alert into a pass by inference.

## G12–G14 — access, usability and upgrade

- **G12:** use a controlled read-only calendar and denied write permission to verify safe action
  availability and retained read-only use. Remove only a dedicated test account with authorization,
  or use a disposable device snapshot. Verify no stale private events/unsafe save/crash. Record
  provider-unavailable recovery as a separate failure-injection test, not a cloud empty-state result.
- **G13:** set font scale 2.0, RTL locale and TalkBack manually. Verify overflow, all-day announcements,
  time ranges and every event remain accessible. Test day/week/month/agenda navigation, search,
  widget and incoming intents against the same known occurrence, including a moved recurring one.
- **G14:** install a later APK signed with the same key over the beta. Verify settings, one draft and
  one snoozed reminder survive; fresh install is separate. Finally remove controlled fixtures from
  their owning test account and verify remote/local cleanup. Preserve evidence without private data.
