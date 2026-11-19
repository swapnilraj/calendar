# Google Calendar Android UX parity audit

Audit date: **2026-11-18**. Target requested by the user: the **current Android phone app**, including its screen structure and interactions, rather than feature names alone. This is an initial reference audit, not a claim of exact parity or a replacement for the feature inventory in [GOOGLE_CALENDAR_AUDIT.md](GOOGLE_CALENDAR_AUDIT.md).

At the initial source checkpoint described below, Daylight already implemented the principal calendar views and substantial editing safeguards. Its presentation differed considerably from the official Android references: navigation is spread across several rows, views live outside the drawer, the Day month title does not open date navigation, and details/editing use long bottom sheets. The initial recommendation was to align the shared navigation shell before reskinning individual event forms; subsequent updates are recorded separately below.

## Live-reference update — 18 November 2026

The original audit below records the earlier marketing-image/help baseline. Its statements that no
APK or emulator was available describe that initial checkpoint, not the current environment.

Google Calendar **2026.44.0-1016863050-release**, versionCode **2018381752**, now installs normally
in an isolated Android 15/API35 Google APIs userdebug emulator. The APK SHA256 is
`46fcded5edececc7a35fa8bd06e3127aa0ed2ef52728a11491f1ed2e45ea41e7`;
its production signing certificate was checked against the official Google Play system-image copy.
APK signature and integrity verification remained enabled. Google assets and APKs stay outside the
repository. No Google account or real meetings have been added.

The first inspected live capture (`/tmp/daylight-b31-google-calendar-first.png`, session-local)
shows Calendar's notification onboarding, including Allow notifications and Not right now, behind
an Android “Process system isn't responding” dialog. This establishes successful app execution and
rendering, **not** a completed onboarding or main-calendar interaction. The software-only emulator
has no hardware virtualization and is slow; its timing cannot establish ordinary-device performance
or animation parity. The reference display is 720×1280 pixels at 320dpi (360×640dp). Locale, font
scale, theme, navigation insets and account configuration still need recording before matched layout
comparisons. Main views, editor, recurrence, gestures and accessibility remain unverified live.

Daylight's earlier screenshots use Robolectric/Roborazzi source-based rendering, not an installed
Android app. Those tests cannot execute Google's APK. The running emulator can execute both apps;
comparison evidence must distinguish those real-device captures from source-rendered baselines.

### Bounded runtime follow-up — 18 November 2026

The independent emulator audit finished **blocked before the main calendar**. Five inspected
captures show the Google Workspace splash, “Checking info”, then a Google-server communication
error; system/launcher ANRs were also observed, and Back returned to the launcher. Installed
versionCode was **2018381752** on API35, `en-US`, 360×640dp at 320dpi, font scale1, light theme.
No accounts or credentials were added. This sequence does not establish the cause of the error;
no authentication, backend or network diagnosis is inferred.

The observation ledger is `/tmp/daylight-google-ux-resume-observation.json`; the five capture
files match `/tmp/daylight-google-ux-resume-*.png`. These are session-local evidence, not committed
Google assets. **Main navigation, event editor, guests and recurrence remain UNVERIFIED live.**
The separate native local-provider acceptance run is provider evidence, not Google UX certification.

### Public entry-point follow-up — 19 November 2026

Fresh read-only manifest/resource inspection of the same pinned APK found public event VIEW/INSERT/
EDIT routes on exported `com.android.calendar.event.LaunchInfoActivity`. Exported
`com.android.calendar.AllInOneActivity` is an alias for the same activity, handling the launcher and
VIEW date/time routes. Exported `CalendarPublicPreferenceActivity` also targets it, accepting
MANAGE_NETWORK_USAGE or MAIN with NOTIFICATION_PREFERENCES. The launcher new-event shortcut
likewise targets LaunchInfoActivity. The actual calendar/settings activities are not exported.

Packaged labels include “On this device” (`0x7f140283`), “Non-Google accounts” (`0x7f14027a`) and
“Calendar works better with a Google Account” (`0x7f1403d1`). They do not establish an accessible
account-free bootstrap. The notification “Not right now” control is not an authentication skip;
authentication “Not now” belongs to existing-account sync repair. Static evidence also does not
prove sign-in is mandatory.

The public settings alias is a legitimate candidate for a later bounded runtime observation, with
no guarantee it avoids the same setup error. No non-exported route, account/credential action,
APK modification or new runtime interaction was attempted. The session-local ledger is
`/tmp/daylight-google-public-entrypoint-audit-2026-11-19.json`; reference assets remain outside Git.
Main calendar/editor interactions and exact parity remain unverified.

## APK resource inspection — 18 November 2026

**Status: static resource candidate evidence only. Google main-calendar phone UX remains
UNVERIFIED live.** This section supersedes the initial audit's APK-unavailable statements, but
not its distinctions between packaged controls, documented behavior and observed interactions.
The initial source/baseline comparisons below are historical; they do not describe every subsequent
Daylight implementation change.

The inspected archive was `/tmp/daylight-b29-calendar-candidate.apk`, package
`com.google.android.calendar`, versionName **2026.44.0-1016863050-release**, versionCode
**2018381752**, SHA256
`46fcded5edececc7a35fa8bd06e3127aa0ed2ef52728a11491f1ed2e45ea41e7`.
`aapt2 dump badging` reported minSdk26, targetSdk37 and compileSdk37. Read-only inspection used
Android SDK37 `aapt2 dump resources`, `aapt2 dump xmltree --file …` and `apkanalyzer manifest print`.
Resource IDs below identify this archive only; obfuscated XML paths are not stable public APIs.
No DEX decompilation, account access or runtime interaction was part of this inspection. Full
resource dumps remain session-local under `/tmp`; no Google assets, fonts, icons or resource files
were copied into the repository. Only short control labels are quoted here.

**R — observed packaged resource:** a layout/menu/string exists in this APK. It may belong to an
alternate, adaptive, legacy, account-gated or remotely configured path. XML visibility defaults do
not establish runtime visibility. Resources alone cannot establish current phone arrangement,
gestures, animation, focus, accessibility, delivery semantics or pixel parity.

| Surface | R: exact resource evidence | Interpretation and remaining limit |
| --- | --- | --- |
| Editor header and sheet states | `layout/editor_header` **`0x7f0e007c`**, `res/Gf.xml`: minimum64dp header, Close at start, Save at end and centered `drag_handle`; no title TextView in this header candidate. `layout/editor_sheet` **`0x7f0e007d`**, `res/4s.xml`: header, collapsed, half-collapsed and expanded stubs. `layout/editor_sheet_predictive` **`0x7f0e007e`**, `res/4B.xml`: rounded clipping wrapper. | Packaged evidence for multiple editor states, not proof which state opens for FAB, empty-slot or Edit. Daylight's current phone editors are full-height with persistent New/Edit titles; that implementation is **not verified Google parity**. Do not change source UI solely from this XML. |
| View selection and drawer | `menu/view_switcher_menu` **`0x7f100012`**, `res/oC.xml`: Schedule, Day, Week, Month. Compact/expanded toggle layouts **`0x7f0e0221`** / **`0x7f0e0223`**, `res/GH.xml` / `res/BD.xml`. `string/list_week_view_3days_label` **`0x7f14063e`**: “3 days”. Drawer accessibility string **`0x7f140259`** identifies Calendar List and Settings. | Four-view resources coexist with 3-day strings and the earlier five-view promotional drawer. Neither removal of 3-day nor active toolbar placement is established. Inspect the actual current drawer and phone view chooser. |
| Toolbar and date navigator | `menu/all_in_one_title_bar_with_one_google_bar` **`0x7f100000`**, `res/uA.xml`: view switcher, Search, Today, Tasks and account items; view switcher initially hidden. `layout/date_picker_button` **`0x7f0e005d`**, `res/2Z.xml`: clickable/focusable minimum48dp control with text, arrow and alternate content; arrow initially hidden. `layout/mini_month` **`0x7f0e00e2`**, `res/W6.xml`: `drag_up_view` and date-picker container. Show/hide month-picker strings **`0x7f140274`** / **`0x7f140267`**. | Supports a date-navigator candidate, not exact arrow visibility, drag behavior, expansion height or date-retention rules. Tasks remains outside Daylight scope. Year-optional picker resources elsewhere in the APK must not be assumed to be the event date picker. |
| Add people | `layout/contact_picker_sheet` **`0x7f0e004f`**, `res/87.xml`: detached text toolbar and RecyclerView. Toolbar **`0x7f0e007b`**, `res/tS.xml`: search field, clear control and action. Add people **`0x7f1403b8`**, Suggestions **`0x7f1403c4`**, Search results **`0x7f1403c2`**, Done **`0x7f1403be`**, discard prompt **`0x7f1403bc`**. Manifest declares READ_CONTACTS. | Contact-search UI is a concrete candidate difference from Daylight's ordered raw email/name guest rows. A permission declaration does not prove prompting, autocomplete data source or denied-permission behavior. No contacts permission should be added solely from APK evidence. |
| Event recurrence and scope | `layout/recurrence_picker_view` **`0x7f0e0190`**, `res/HB.xml`, class `com.google.android.calendar.recurrencepicker.RecurrencePickerView`: interval/frequency, weekday/monthly selectors and never/date/count controls; container **`0x7f0e018b`**, `res/D2.xml`. “Does not repeat” **`0x7f1404e0`**, “Custom…” **`0x7f1404d3`**, save-scope title **`0x7f1404e1`**. Scope strings This event **`0x7f14093c`**, following **`0x7f14093a`**, all **`0x7f140938`**; scope container **`0x7f0e01af`**, `res/yn1.xml`. | Presence does not establish offered scope, order or prompt timing for a given event. Another recurrence layout **`0x7f0e018a`**, `res/6z.xml`, contains Tasks-specific components and is not a safe event reference. Daylight's repeat panel and explicit typed review exist; exact arrangement remains unverified. Preserve recurrence safeguards. |
| Inline RSVP | `layout/event_rsvp_actions_pill` **`0x7f0e0083`**, `res/qW.xml`: Yes, No, Maybe buttons and alternate Yes-with-location group. Labels **`0x7f1408d7`** / **`0x7f1408d5`** / **`0x7f1408d4`**. `menu/rsvp_location_menu` **`0x7f10000a`**, `res/dP.xml`: Yes, in-room and virtual variants. | Candidate difference from Daylight's separate Respond/scope workflow. Actual inline visibility, eligibility and recurring response flow require runtime proof. Attendance-mode choices need supported backend rights before exposure. |
| Guest notifications | Invitation/update/cancellation prompt strings **`0x7f1405c6`**, **`0x7f1405d2`**, **`0x7f1405c7`**; Send **`0x7f1405ce`**, Don't send **`0x7f1405cd`**; message layout **`0x7f0e00a7`**, `res/SY.xml`. | No proof of prompt timing, recipient selection, server defaults or delivery. **Send/Don't send capability is unverified for Daylight.** CalendarContract and a local write acknowledgement cannot fake these semantics or prove invitation delivery; retain honest sync-adapter/local-save wording. |
| Details overflow | `menu/event_view_overflow` **`0x7f100004`**, `res/dC.xml`: Delete, Email guests, Forward; Duplicate, Copy to and Report spam initially hidden/disabled. | Runtime capabilities can change these defaults. Do not infer that Google lacks duplication, remove supported Daylight actions or add unsupported email/forward controls from this menu alone. |

### Current Daylight observations and next runtime checks

At source checkpoint **`9d168f8`**, `PhoneEventEditor.kt`, `PhoneMeetingCreateEditor.kt` and
`PhoneOwnedMeetingEditor.kt` implement full-height editors and persistent toolbar titles. The
meeting forms expose raw guest rows, issued roles and removals; no contact-search picker or
READ_CONTACTS permission was found. Responses use a separate workflow. These are observed
Daylight implementation differences from **packaged candidates**, not confirmed differences from
an exercised Google phone flow. Header/title treatment, initial sheet height/drag states, contact
search and inline RSVP merit investigation before further cosmetic alignment. Static XML alone
does not justify a source UI change or weakening durable-draft, permission, identity or recurrence
guards.

The next audit must use synthetic data and record APK/build identity, Android version, viewport,
density/insets, locale, 12/24-hour setting, theme, font/display scale and account entitlements:

1. Open the actual drawer and every offered view; locate the active view chooser, expand/collapse
   the month title, page/select dates and use Today. Check selection/scroll retention and Back.
2. Compare FAB creation, empty-slot tap/long-press and existing-event Edit. Capture initial height,
   drag/expansion states, title/header, keyboard, Back and cancel paths before deciding whether the
   full-height Daylight implementation matches any active Google path.
3. With authorized synthetic contacts only, inspect Add people search/suggestions, Done, discard,
   role choices and unavailable/denied contacts behavior. Do not log private contacts or events.
4. Inspect the **event** recurrence flow, including custom interval, monthly rules, count/date end
   conditions and Save-time scopes; distinguish it from Tasks. Preserve exact event identity,
   count/exclusions and duration safeguards while comparing presentation.
5. When dedicated organizer/invitee accounts are authorized and available, inspect inline RSVP and
   invitation/update/removal/cancellation prompts. Record account gating and actual delivery as
   separate evidence; neither exists merely because a string or local ACK exists.
6. Repeat relevant flows with large text, RTL, accessibility focus/announcements and matched DST
   dates. Runtime captures are required for hit-testing, focus order, gestures and grid transitions.

No main Google calendar, event editor or account-backed meeting flow was verified by this static
inspection. The independent emulator audit remains the authority for subsequent live observations.

## Evidence and reproducibility

Evidence tags used in the initial comparison rows (R above is the later packaged-resource evidence):

- **P0–P7 — observed reference image:** visually inspected official Play promotional screenshots. These show composited phone presentations, not an instrumented running app. They establish visible arrangement, not gesture behavior or dp measurements.
- **H1–H6 — documented:** fetched the Android-specific official Help pages below on the audit date; instructions establish interactions, not exact pixels.
- **S — observed Daylight source:** inspected the source at local integrated commit `841406d4705276f7036c37055bd92a88a9ac021e`. This is the development integration, not a claim that every feature is already merged into the published branch. PR50 reminder activation is a separate publication packet.
- **B — observed Daylight baseline:** visually inspected the named existing synthetic-data Roborazzi images. A component fixture may omit production controls; source must corroborate conclusions about routing or availability.
- **I — inferred/proposed:** design consequence or proposed alignment. It is not an observation of Google behavior.

| Baseline dimension | Google reference available | Daylight evidence / comparison rule |
| --- | --- | --- |
| App/version | Official `com.google.android.calendar` Play page; the English/US page reports updated **Nov 12, 2026**. No installed versionName/versionCode was obtained. Listing update date does not identify the screenshot build. | Source commit above. Record APK versionCode/versionName and SHA for future device comparisons. |
| Device / OS | Phone frames in marketing images; device model, Android version, density, insets, navigation mode and window size unknown. No connected Android device or Google APK available to this audit. | Integrated baseline tests use Robolectric API35, `en-rUS-w393dp-h851dp-mdpi`. These are simulated renders, not device captures. |
| Locale / time | Screenshots show English text, January sample dates and a mix of schedule/grid times. Play retrieval `hl=en`, `gl=GB` for root image capture; auditor text retrieval `gl=US`. Locale region and 12/24-hour preference of the depicted app are unknown. | Integrated test date `2026-11-17`, Europe/London, 24-hour input. Match synthetic event dates, device locale and time format before measuring layout differences. |
| Font / display scale | Not reported. Do not infer font scale or typeface metrics from the resized phone frames. | Default font scale1; separate component fixtures cover 2x text and RTL. Normal-scale and accessibility comparisons must be separate. |
| Theme / accounts | Light and dark promo examples; account/feature entitlements and sync state unknown. | Light/dark fixtures, synthetic calendars. Capability-dependent actions must be tested with known permissions, account identity and access. |
| Physical visual metrics | No reliable dp, corner radius, typography, animation timing, touch-slop or frame timing measurements available. | Proposed spacing is an implementation choice until verified at a matched device baseline. No pixel-parity percentage is defensible. |

### Official references

- **P:** [Google Calendar Play listing](https://play.google.com/store/apps/details?id=com.google.android.calendar&hl=en&gl=GB). Root downloaded the eight images into `/tmp/google-calendar-ux-reference/play-screenshot-0.png` through `7.png`; source URLs and capture date are in `/tmp/google-calendar-ux-reference/manifest.json`. These are session-local reference files, not committed Google assets.
  - P0: Schedule list and toolbar; P1: grid with selected slot and quick-create card; P2: event details, edit/overflow and RSVP; P3: Gmail-imported flight detail; P4: full-month overview; P5: dark grid; P6: navigation drawer; P7: home-screen widgets.
- **H1:** [View your day, week, or month — Android](https://support.google.com/calendar/answer/6110849?hl=en&co=GENIE.Platform%3DAndroid).
- **H2:** [Create an event — Android](https://support.google.com/calendar/answer/72143?hl=en&co=GENIE.Platform%3DAndroid), including edit and drag-move instructions.
- **H3:** [Create a recurring event — Android](https://support.google.com/calendar/answer/37115?hl=en&co=GENIE.Platform%3DAndroid).
- **H4:** [Change Calendar settings — Android](https://support.google.com/calendar/answer/6084644?hl=en&co=GENIE.Platform%3DAndroid).
- **H5:** [Invite people — Android](https://support.google.com/calendar/answer/37161?hl=en&co=GENIE.Platform%3DAndroid).
- **H6:** [Respond to event invitations — Android](https://support.google.com/calendar/answer/37135?hl=en&co=GENIE.Platform%3DAndroid).

At the initial marketing/help checkpoint, all six Help pages and the Play page returned HTTP200. The pages can change independently of releases. Help was read as text without a browser. At that initial checkpoint no Google APK had been downloaded, unpacked, executed or inspected, and no live Google gesture had been exercised. The later live-reference and resource updates above supersede APK availability only; static resources still do not prove runtime navigation, accessibility or gestures.

### Daylight evidence inspected

Sources: `ui/LiveCalendarDay.kt`, `DayScreen.kt`, `DayHeader.kt`, `DayPage.kt`, `calendars/CalendarDrawerSheet.kt`, `calendars/CalendarViewChooser.kt`, `month/MonthView.kt`, `multiday/MultiDayView.kt`, `agenda/AgendaView.kt`, `events/EventDetailsSheet.kt`, `events/EventEditorSheet.kt`, `events/RecurrenceEditorControls.kt`, `events/meetings/MeetingResponseSheet.kt`, `settings/SettingsSheet.kt`, and the integrated UI test fixtures.

Baselines visually inspected: `integration_live_day.png`, `integration_live_week.png`, `integration_live_month.png`, `integration_calendar_drawer.png`, `integration_live_details.png`, `integration_live_agenda.png`, `events_editor_light.png`, and `settings_light.png` under `app/src/test/screenshots/`. The Day/details fixture does not supply Settings or mutation ports, so it deliberately omits the production drawer/view/FAB/edit actions. Their existence and placement were checked in source instead.

## Screen and interaction comparison

Priority: **P0** shared navigation / immediate use; **P1** core daily flows; **P2** later interaction or feature expansion. Priority is proposed (I), not a Google classification.

| Surface / interaction | Google evidence and its limit | Daylight evidence | Concrete gap and proposed result | Priority |
| --- | --- | --- | --- | --- |
| Shared top toolbar | P0/P1/P4/P5 visibly put menu, month dropdown, search, today-like calendar icon and account avatar together. Exact icon functions beyond H1 are not exercised. | S: DayScreen month text, injected view dropdown, previous/today/next; non-Day has a separate menu/view row above each view's own header. B: week/month/agenda show stacked controls. | One common toolbar across all views; month/date control, search and Today in stable positions. Retain Daylight identity; do not add a decorative avatar with no real account action. | P0 |
| Month-name date chooser | H1 explicitly: tap month name, swipe left/right through months, tap date; Today at top right. | S: DayScreen month title is plain Text. DayHeader date opens that day's event sheet. Month/MultiDay/Agenda have modal Material date pickers. | Add expandable month/date navigator from the common month title in every view, supporting month paging and selected date. Preserve an accessible arbitrary-date/year alternative. | P0 |
| Day/date state when switching | H1 documents changing views, but exact selection/scroll retention not specified. | S: shared selected date, view-specific ranges, shared wall-minute scroll anchor. | Preserve these existing semantics under the new shell. Test date changes, Today, view switching and return from details. Precise Google retention rules remain unverified. | P0 |
| Drawer views | P6 visibly orders Schedule, Day, 3 Day, Week, Month before Refresh and calendar accounts. H1 says Menu chooses view. | S/B: drawer focuses on calendars; view choice is a topbar dropdown. | Put implemented views in the drawer above calendar lists, with selected-state indication. Use “Schedule” as the list-view UI label if aligning terminology; retain AGENDA persisted identifier. | P0 |
| Drawer calendar groups | P6 shows avatars/account addresses and compact colored checkboxes. It does not establish account-management behavior. | S/B: group name/type, verbose coverage context, color checkbox plus separate dot, select all/none, creation-default radios. | Compact normal rows; move creation defaults to settings. Keep account separation and stable calendar identity. Preserve read-only/unknown/missing-calendar explanations via accessible secondary context. | P1 |
| Drawer footer / refresh | P6 shows Refresh above accounts; H4 puts Settings under Menu. Exact hidden footer contents not captured. | S: Settings/Search/Reminders/Drafts injected into drawer footer; defaults consume main list space. | Use ordered navigation sections and real Refresh callback. Keep Daylight-specific reminders/drafts discoverable without duplicating the view chooser. Do not invent account/sync actions. | P1 |
| Day grid | P1/P5 show compact toolbar/date row, all-day area and timed event blocks. H2 documents empty-slot creation and drag to move. | S/B: timed grid, now indicator, overlap layout, all-day header, horizontal day pager, explicit durable-editor gesture route. | Retain geometry/provider guards; align shared chrome and slot-selection feedback. Exact row density, resize affordance and paging physics need matched live evidence. | P1 |
| Three-day grid | P6 establishes a 3 Day view exists; no inspected screenshot shows its full grid. | S: MultiDayView uses 3 real-date columns, common gutter/vertical scroll and horizontal pan when needed. | Apply common toolbar/date strip; remove redundant range/navigation/zoom rows at normal scale. Do not claim column widths or Google paging behavior are observed. | P1 |
| Week grid | P6 establishes Week; exact phone week density absent from these images. | S/B: 96dp font-aware minimum columns cause horizontal panning; five control rows precede the header/grid in baseline. | Reduce chrome first. Evaluate fitting seven columns at normal text only after reference capture; preserve readable pan alternative at large text. No forced tiny event text. | P1 |
| Grid zoom | No inspected Help/image establishes Google's pinch thresholds, controls or zoom persistence. | S: Day pinch zoom; multi-day visible minus/Zoom%/plus row; 32..160dp per hour. | Keep accessible zoom actions but avoid permanent normal-scale toolbar height solely for zoom. Exact gesture parity is unverified; do not change geometry merely from memory. | P2 |
| All-day / crowded dates | P0/P4 show colored event bars; overflow behavior not demonstrated. | S: aligned all-day headers with overflow to full day-event sheet; month shows two chips then overflow. | Retain complete accessible access and date identity while aligning density. Need live crowded-day reference before matching collapse/overflow visuals. | P1 |
| Month overview | P4 visibly fits the month's rows in one phone overview with small bars. No swipe action observed. | S/B: always six rows, min112dp*fontScale per cell, scrolling; two chips, heavy cell borders, partial sixth row on393x851 baseline. | Fit ordinary month overview to available height at normal scale; lighter separators and density-aware event summaries. At large text allow deliberate scrolling/list alternative. Choice of 5/6 rows needs reference verification; do not infer it from one January image. | P1 |
| Month date tap | H1 says tap date to find that day's events, but does not fully specify the current Month screen transition. | S: date tap/overflow opens a day-event bottom sheet; individual chip opens event. | Keep functional access during shell work. Verify whether current phone app navigates to Day, expands a panel or preserves Month before changing this interaction. | P2 |
| Schedule/Agenda list | P0 shows compact day labels, colored event cards and illustrations. H1 calls it Schedule and describes upcoming events/tasks by day. | S/B: “Agenda”, explicit31-day range, earlier/later buttons, full-width date headings, dot/list rows and empty-day messages. | Offer compact Schedule-style grouping, event-color cards, fewer empty-day rows, incremental range extension with honest coverage/end states. Illustrations are optional original Daylight assets, never copied. | P1 |
| Create action | H2 documents bottom-right Create then Event; P0/P4/P5 show plus action. Other create types are not fully observed here. | S: text-only ExtendedFloatingActionButton “Create event”; directly opens durable editor. | Stable bottom-right plus affordance; direct Event creation until other real types exist. Do not expose Tasks/Focus/working-location dead options. | P0 |
| Empty-slot quick creation | H2 explicitly permits tapping an empty grid slot. P1 shows an outlined selected slot, quick-create card and Save button. It does not prove tap/long-press timing. | S: creation gesture is long-press; dropped proposal prepares a durable editor, with no automatic provider save. | Add tap-to-select time-slot interaction and compact title/time/calendar quick-create state. Keep explicit Save and fresh validation. Gesture trigger change needs conflict tests against scroll, event taps, RTL and cancellation. | P1 |
| Move / resize | H2 documents drag-and-drop move; resize behavior and commit semantics are not established in inspected sources. | S: long-press preflight, move/resize15-minute proposal, multi-column clipped hit-testing, editor review, exact DST choices. | Preserve the existing safety route. Align preview appearance only after reference capture; do not remove review or silently save ambiguous civil times to mimic a marketing frame. | P1 |
| Details presentation | P2 shows a near-full phone surface, close/edit/overflow at top and icon-led rows, with RSVP at bottom. P3 uses an illustrated flight header. | S/B: partially expanded bottom sheet, large textual sections, visible display-zone/context information, edit/delete/duplicate actions near bottom. | Dedicated full-height details presentation with top close/edit/overflow, grouped icon rows and reachable primary join action; secondary metadata collapsible. Flight/Gmail rich cards need separate data support. | P1 |
| Details guests / meeting actions | P2 shows guest count, organizer avatar and communication icons. H5 documents guest list, email/chat and some account-restricted scheduling tools. | S: guest list/status/access-policy context and safe join/map/description links; no guest communication toolbar or availability planner. | Improve guest grouping without pretending provider attendees establish organizer permissions. Email/chat/find-time require supported ports and account policy, beyond a visual-only pass. | P2 |
| Create/edit form | H2 documents top-right Save, All-day above date/time, calendar carousel and guest/location fields. P1 only illustrates quick-create. | S/B: long modal bottom sheet with outlined raw ISO date/time/zone fields, calendar chips, Save/Cancel at bottom. | Full-screen editor with persistent top close/Save; tappable human-readable date/time rows and picker sheets. Keep raw invalid input/draft recovery internally; explicit DST/namespace conflicts remain blocking. | P1 |
| Notifications / color / availability fields | H2 documents event color picker; H4 documents notification settings. Exact picker arrangements not observed. | S/B: alerts are comma-separated minute offsets; no event-color picker in current editor; availability/visibility have typed controls. | Human-readable notification rows and add/remove choices, separate all-day presets, color choice only when writer capability supports it. Retain unknown and unchanged values rather than coercing them into presets. | P1 |
| Recurrence creation | H3: under time, “Does not repeat”, choose frequency, Save. Custom repeat picker visuals are not shown. | S: vertically expanded frequency/day/end chips plus raw interval/ordinal/date fields. | Single repeat summary row opening a focused recurrence screen; localized frequency and ending controls. Preserve supported-rule validation and existing authored recurrence semantics. | P1 |
| Recurrence edit scope | H3: after edits and Save choose This event / This and following / All; following appears only from a later occurrence. | S: selects a supported series scope before entering the durable series editor. | Decide deliberately whether to adopt a Save-time scope chooser. This affects draft authority/generation and cannot be a cosmetic reorder. Preserve capability gating, frozen request recovery and explicit scope review. | P2 |
| RSVP | P2 shows inline Yes/No/Maybe plus dropdown; H6 documents same, proposed time and note. | S: details “Respond” action opens separate response/scope form and explicit Save; durable response guards. | Inline response choices can launch existing confirmed workflow with clear scope; keep delivery limitations truthful. Proposed-time and note are separate unsupported features, not fake dropdown actions. | P1 |
| Settings hierarchy | H4: Menu > Settings > General, plus account/calendar settings; documents individual setting names. Exact current screen layout unseen. | S/B: one long Save/Cancel settings sheet with defaults, visibility, theme/zone and weather controls; reminder setup is separate. | Dedicated Settings screen with General and calendar/reminder sections; subordinate choices rather than a large raw form. Whether each change applies immediately needs live confirmation and persistence design. | P1 |
| Settings coverage | H4 documents alternate calendars, week numbers, short-entry sizing, illustrations, invitations, conferencing and quick responses. | S: firstweekday, device/fixedzone, theme, declined/weekends, duration/reminders, view/weather options; no UI for those additional documented settings. | Track as real feature gaps, not visual parity. Prioritize week numbers and short-entry sizing; backend/account features need separate approved scope. | P2 |
| Search access | P0/P1/P4/P5 visibly have toolbar magnifier. Actual search screen not inspected. | S: search action in drawer when a real port is wired; native sheet with explicit date range/limits. | Move real search action into common toolbar. Preserve bounded coverage, permission/limit states and exact occurrence opening; no claim about Google result layout yet. | P0 |
| Accessibility / RTL / DST | Inspected Google images are English, normal-looking scale and ordinary dates; they prove no RTL, keyboard or DST behavior. | S: bidi time labels, 2x-font/RTL component fixtures, hidden-weekend access, explicit overlap/gap review and elapsed durations. | Maintain these safeguards during alignment. Exact Google accessibility tree, spoken ordering and transition behavior require actual device observation. | P0 guardrail |

## Proposed first implementation packet: shared phone navigation

Bounded goal: make switching views and dates work in the documented Android places, with one compact shell, before changing provider or editor behavior.

1. Add a reusable phone toolbar: menu; localized month/title with expand state; actual Search when available; Today. Use original Daylight icons/typography/color treatment. Keep an overflow for secondary Daylight actions if needed; no inert profile/account affordance.
2. Add an expandable calendar date navigator with month paging, today/selection and a screen-reader-accessible date/year picker alternative. Selecting a date updates the existing shared selection and view range. Opening it cancels pending grid edits through the existing cancellation path.
3. Move Schedule/Day/3 Day/Week/Month into the drawer above accounts; show current selection. Preserve the persisted enum and explicit calendar filters. Move default-creation calendar controls into Settings; keep permission/identity explanations available.
4. Remove duplicated normal-scale view headers, range arrows and permanent zoom row from the content when hosted by the new shell. Leaf components must retain standalone defaults for tests and reuse; accessible navigation/zoom actions remain reachable. Do not break wide layouts or large-text alternatives.
5. Use a consistent plus Create affordance and real Search shortcut. Continue opening existing durable workflows; no new writes, automatic saves, backend dependencies or fake menu items.

Suggested ownership boundary: shared shell/date-nav/drawer controls by one UI owner; lead owns Live routing/date-range/cancellation wiring. Keep `DayGrid` layout, provider contracts, draft stores and mutation engines untouched. Subsequent separate packets: month/schedule density; details surface; editor/date-time/notification/recurrence presentation; account-dependent guest features.

Acceptance evidence for this packet:

- Matched synthetic dates/events across all five views; one date/title toolbar and stable Today/Search/Create access at393x851, font1, English US and both themes.
- Drawer view choice preserves selected date and explicit empty calendar filters; selecting a date navigates the actual range in Day/3Day/Week/Month/Schedule. Today respects display zone.
- Date navigator open/dismiss, view switch and calendar filter change cancel held/preflight/storage-pending gesture routes; no stale preview can open an editor afterward.
- Font2 and Arabic/Hebrew RTL keep all controls and date access reachable, logical focus order and descriptive selected state. Keyboard/screen-reader alternatives do not require swipes or dragging.
- Existing namespace, recurring-instance, DST, query-limit and durable-save tests pass; screenshots are inspected, but pixel equivalence to Google remains unverified until a matched live reference is obtained.

## Remaining reference work before claiming exact UX

Obtain an authorized current Google Calendar installation/reference capture, record versionName/versionCode, device/Android/build, window/density/insets, locale, time format, font/display scale, theme, account type and enabled features. Use only synthetic calendar data. Capture toolbar expanded/collapsed, all five views, dense/all-day cases, date navigation, drawer, FAB/slot creation, details, editor, repeat custom/scope, RSVP and settings. Exercise tap, swipe, back, rotation, keyboard, TalkBack, drag, resize and interrupted workflows. Record actual outcomes instead of deriving them from APK strings or screenshots.

A legitimately available APK may help inspect resources and screen assets, but no APK was available for this audit. Do not use unverified third-party downloads as proof of the current phone UI. Keep Google images/assets outside the Daylight application; AGENTS branding rules still apply. Functional arrangement and navigation can align while retaining original Daylight branding and its weather layer. Exact visuals, gestures and DST behavior remain **unverified** by the current reference set.


## Live Settings reference — 19 November 2026

A subsequent live emulator observation verified Google's Settings and General screens, independently
of the earlier onboarding failure. Reference: 2026.44.0-1016863050-release, versionCode 2018381752;
API 35, owned user 0, 720×1280 at 320 dpi (360×640 dp), en-US/LTR, font scale 1, light theme.
Installed APK SHA-256 `46fcded5edececc7a35fa8bd06e3127aa0ed2ef52728a11491f1ed2e45ea41e7`.
The exported public preference activity opened Settings after one observed System UI ANR was dismissed
with Wait. A later read-only capture established the screen; immediate launch timing was not measured.

Settings contains General and Manage accounts. General is a full-height grouped list with a persistent
header, subtitles and trailing switches. Observed controls, in order: start of week; device timezone;
disabled timezone row; alternate calendar; week numbers; declined events; short-entry sizing; event
illustrations; default event duration; adding invitations; theme; calendar notifications; task
notifications; overdue tasks; quick responses; calendar-data sharing with other apps. The duration row
opened an informational page describing a one-hour default for non-Google accounts; no duration
choice or persistence behavior was demonstrated. No setting, account or system permission was changed.
Back from Settings returned to the launcher. No main calendar, editor, gestures, RTL, large-font or
account-dependent meeting flow was verified by these observations.

Eight reference images and the detailed observation report remain outside Git in
`/tmp/daylight-google-general-reference-20261119-m8bfzett/`. The post-load Settings capture is under
`/tmp/daylight-google-postload-observation-20261119-wy2l65mm/`. These observations supersede the
historical statement that no installed APK was available, solely for the surfaces actually inspected.
Daylight should use the observed hierarchy for supported settings and keep its own working defaults,
reminders and weather controls. Unsupported Google controls must not become inert UI affordances.


### Daylight implementation following the Settings observation

The phone flow now implements Settings → General for existing supported preferences, with real
creation-default/reminder callbacks and separate Daylight display/reminder/weather groups. Dedicated
in-place editors preserve explicit save/cancel and field-only persistence; legacy component sheets
remain available. Ten synthetic screenshots were recorded/inspected, including dark theme, fixed zone,
large-font RTL, invalid input and a failed save. Logical heading and wrapped-paragraph alignment was
corrected after inspection. This verifies Daylight's local flow; Google selector appearance, save
behavior, exact dimensions and main/editor/account UX still require live reference evidence.


### Adaptive Month overview — local implementation

The shared host already removed duplicate Month/Schedule chrome and Schedule already omitted most
empty dates; those historical table gaps were not current source findings. The remaining Month
width/height issue is now corrected locally: measured normal-phone layout fits the same six weeks
and seven columns, with plain space-budgeted summaries/counts and a complete day-list action. Larger
text/constrained/wide layouts preserve the detailed scrolling mode. Four synthetic captures were
inspected at360×640 and393×851, including dark theme and crowded dates. The39 functional checks pass;
full baseline verification is queued. This matches documented overview intent without claiming live
Google event-tap gestures, variable row-count behavior or exact pixel/accessibility parity.


### Latest MAIN observation — 19 November 2026

At idle, the same pinned Google APK/owned API35 emulator received one ordinary MAIN/LAUNCHER launch.
The first capture showed only splash; a later read-only capture showed “There was a problem
communicating with Google servers. Try again later.” The visible window was Google Play services'
account-flow ErrorActivity; Calendar AllInOne remained obscured without a visible surface. No account,
permission, setting or calendar was changed. This observes a communication wall, not its cause or an
account-mandatory rule. Main/editor parity remains unverified. Reports/images remain outside Git:
`/tmp/daylight-google-main-once-20261119-klc9iek1/` and
`/tmp/daylight-google-main-postload-20261119-0j9gjpd0/`.
