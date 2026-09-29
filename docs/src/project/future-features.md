# Future features

These are possible extensions rather than commitments for the first mobile
application. The initial scheduling scope remains once daily.

## Scheduling

- Selected weekdays or non-daily calendar patterns
- Multiple scheduled times per day
- Fixed interval schedules, such as every number of hours or days
- As-needed medication with appropriate limits and safety guidance
- Courses with start and end dates
- Tapered schedules and temporary schedule changes
- Planned skips, exceptions, travel handling, and explicit time-zone behavior
- Explicit schedule-effective boundaries for medication creation, re-enabling,
  and schedule edits. Reconciliation must not infer a missed or
  needs-attention dose whose reminder period began before Dose Goose became
  responsible for that schedule. The same-day fencepost policy should either
  select the next occurrence or require an explicit user choice to include the
  otherwise-retroactive occurrence.

New recurrence types should extend a portable scheduling model rather than
accumulating independent recurrence rules in Kotlin and Swift.

## Medication and history management

- Rich medication details including strength, units, instructions, notes, and
  appearance
- Safe schedule-edit previews that explain which generated occurrences change
- Independent notification lead time and early-taking eligibility if experience
  shows that the initial combined window is too limiting
- Intake correction with durable history rather than silent destructive edits
- Longer-term history, adherence summaries, and exportable reports
- Medication archiving that retains past intake records

## Presentation and navigation

- An optional silent or minimized due-notification mode, after evaluating
  Android channel persistence, discoverability, OEM presentation differences,
  and whether it should be a global or per-medication preference
- Investigate whether a continuous or full-screen alarm mode is needed beyond
  repeated notification alerts, including user control, accessibility, Android
  background-execution policy, and what happens while the notification shade
  is open
- Adaptive list-detail medication management on larger screens
- Week and month schedule presentations after the daily agenda is useful
- Search, filtering, and attention-focused views for larger medication lists
- Widgets and other platform surfaces that preserve the same authorization
  rules as the foreground application
- A frequency-independent Today model that can present cross-midnight
  carryover, missed history, and overlapping occurrences without assuming one
  dose per medication per day

## Reliability and coordination

- Activity-evidence tuning, accessibility-aware motion sources, per-medication
  overrides, and support for Android devices without Google Play services
- Deeper diagnostics for channel configuration, battery restrictions, failed
  alert execution, and disabled activity evidence
- Outbox retry telemetry, bounded retention, and operator-facing diagnostics
- Encrypted user-selected backup, restore, and conflict reporting
- Cross-device synchronization and reminder ownership
