# Phone event editor presentation

The `PhoneEventEditor` component is a full-window presentation over the existing durable ordinary
editor controller. Android Help's create-event and repeat-event instructions establish top-right
Save, All-day above date/time, calendar selection and a repeat choice under time. These informed
the arrangement here. The available official promotional images do not establish exact full-form
pixels, picker geometry, motion, or dimensions. This is reference-aligned presentation, not verified
pixel equivalence to the current Google Calendar installation. Daylight uses its own theme and
original line drawings; no reference images, logos or proprietary fonts are application assets.

## Integration

`PhoneEventEditor(controller, writableCalendars, onDismiss, onSaved, onDeleted,
onReviewExisting?, use24Hour, onReplacedLaunch?, onDiscarded?)` matches the existing sheet's
presentation callbacks. The host still prepares durable records, verifies generation and source
identity, supplies permission preflight/retirement ports, and persists replacement launches. The
component does not create draft records, submit on open/close, infer calendar rights, or change
frozen commands. Existing `EventEditorSheet` and its fixture defaults remain unchanged.

Close and Save stay in a fixed toolbar. Normal draft discard is secondary, below the form; blocked
or pending saves show their recovery actions prominently. Destructive discard/replacement and
original deletion retries require confirmation. A failed local draft flush leaves the full window
open. Submitted requests stay immutable; the UI says “Check save result” and “Discard draft” while
the controller enforces exact retirement and cleanup behind those user-facing actions.

Primary rows show localized civil dates/times, a calendar/account, All-day, time zone, location,
notifications and description. Unknown or partial raw input is never replaced with today's date.
Native pickers have explicit text entry; incomplete text remains in the durable draft. Overlap
choices preserve exact offsets, and cancelling a picker does not change them. Notification labels
cover before/after offsets and unresolved calendar defaults without rewriting source values.
Repeat opens focused existing typed controls, followed by the existing authored-rule review.
Availability/privacy remain capability-gated. There is no guest action until its real draft workflow
is integrated in a separate packet.

## Evidence

Focused tests cover input recovery, source namespace policy, all-day exclusive UTC storage,
notification editing, repeat validation, permission/frozen controls, fixed toolbar actions and a
failed Close flush. Three synthetic renders are inspected: light, dark, and Android font-scale 2.0
with forced RTL layout at API35/393x851. The large-text test sets Android's actual font scale because
a Dialog can reset a caller's injected composition density. It is an English fixture in RTL layout,
not proof of a translated Arabic/Hebrew UI or of a live Google application's accessibility behavior.
Production host journeys and the combined build remain separate integration gates.
