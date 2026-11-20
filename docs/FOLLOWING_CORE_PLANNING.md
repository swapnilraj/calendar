# Pure Following recurrence planning

These helpers calculate bounded nominal ordinals, exact prefix/tail repeat limits and metadata-only
EXDATE partitions. They contain no Android APIs or provider writes, and keep the existing pinned
lib-recur 0.17.1 adapter boundary. This packet adds no dependency.

`RecurrenceOrdinalMapping` proves the selected nominal occurrence under bounded expansion.
`RecurrenceFollowingRules` retains literal prefix COUNT/UNTIL context and proves a compatible tail.
`RecurrenceFollowingMetadata` retains surviving native exclusion tokens, order, duplicate spelling,
group prefixes and NULL/empty distinctions for unchanged temporal/rule intent. It supplies semantic
proof only: the Android reader must independently decode complete source, and a writer must prove
clone admission, exception transfer, child preservation, cache refresh and native instance bounds.
A manually constructed helper snapshot is not native serialization or execution authority.

The explicit `SeriesScopeCapabilities.recurrence` field defaults to UNKNOWN. Reading recurrence text
does not grant rule-edit rights. Existing callers retain their defaults; no app action or provider
profile is enabled by this packet.

Tests cover nominal mapping, COUNT/UNTIL changes, portable clock checks, selected identity, finite
budgets, and literal exclusion partition semantics. Native CalendarProvider and Google/Workspace
round-trips remain separate acceptance gates.
