# Open questions

- Minimum supported iOS version
- iOS bundle identifier
- Whether core, simulator, and mobile applications share one release version
- Whether the core will be published independently
- Exact encrypted-backup and recovery-key formats
- Whether medication data needs application-level at-rest encryption in
  addition to platform sandboxing and device storage protection
- Cross-device alarm ownership and conflict resolution
- Whether Android should add confidence thresholds, sustained-motion grace,
  wheelchair or other accessibility-aware evidence, or a due-boundary sample
  for someone already walking when monitoring begins
- What native evidence policy iOS should use, and whether future native
  classifiers need to pass evidence metadata into the portable core
- Whether the initial Android overnight defaults and confidence thresholds need
  per-user tuning, accessibility alternatives, or a diagnostic explanation of
  why a sleep candidate did not complete
- Whether later sleep handling should recognize naps, travel, shift work, or a
  scheduled-time anchor without allowing sleep detection to silence a reminder
  already in progress
- Medication-specific late-dose policies beyond the initial single duration,
  including post-window clinical guidance
- Whether travel should keep future once-daily doses at their original zone or
  rematerialize uncreated dates in the newly observed device zone
- Whether a deliberate clock correction requires additional reconciliation or
  a visible diagnostic beyond the core's stale-event protection
- Whether users need an explicit action to reschedule an already materialized
  pending occurrence after editing its future schedule
- Whether users should be offered an explicit same-day opt-in when creation,
  re-enabling, or rescheduling would otherwise begin with the next occurrence
- Which medication details are required beyond a display name, such as amount,
  unit, instructions, notes, or appearance
- Which reminder settings are global defaults and which can be overridden per
  medication
- Whether and how users may correct or delete intake history while preserving
  an audit trail
- How a missed dose differs from a late-window-elapsed dose in user-facing and
  clinical guidance
- How Today, carryover, and historical attention should be defined without
  assuming exactly one occurrence per local day, including schedules that are
  more frequent or less frequent than daily
- Whether notification lead time and early-taking eligibility should remain one
  setting or become independently configurable
- Whether the scheduled anchor time should eventually have reminder semantics
  beyond defining the early and late offsets, including how an earlier
  departure should affect escalation without mistaking ordinary morning
  activity for leaving
- Snooze limits and whether snooze or dismissal needs additional authorization
- Whether users may explicitly opt in to forwarding private reminder details
  to watches, cars, or other Android companion surfaces
- Durable effect identity, delivery acknowledgements, and replay after process
  death
- Cross-device event ordering and merge rules for stale observations, accepted
  activity, snoozes, presentation state, and intake records
