# Requirements

## Reminder behavior

- A dose becomes due according to its configured medical schedule.
- The first supported schedule type produces one dose per local calendar day at
  a user-selected local time.
- Each schedule has an early-taking window and a late-taking window around its
  due time. A regular, non-alarm notification begins when the early window
  opens, and recording becomes available at the same instant.
- Intrusive reminder eligibility begins when the early-taking window opens.
  Accepted activity can therefore start an alarm before the scheduled time.
- Intrusive reminders wait for meaningful phone-carried activity.
- Brief pickup or unlock activity must not be treated as walking around.
- Once activity is accepted, snoozing does not return the dose to an inactive
  reminder state.
- Returning an already-active dose occurrence to inactivity requires an
  explicit foreground action. Confirmed overnight sleep may clear the
  device-wide activity latch for future occurrences, but never silences a
  reminder already in progress.
- Clearing a notification never records a dose as taken or stops its reminder
  policy. Explicit foreground app actions govern intake and inactivity.
- Notifications show an absolute late-taking cutoff rather than a relative
  countdown that can become stale.
- Recording a dose as taken requires the unlocked foreground application.
- The recorded intake time defaults to now and may be changed to an earlier
  time. A time before the early-taking window requires a separate, explicit
  out-of-window confirmation; ordinary recording remains bounded by the window.
- Medication-specific late-dose policies change the available guidance and
  actions without silently shifting the next scheduled dose.

## Medication management

- Users can create, edit, archive, and inspect medications independently of
  their generated dose occurrences and intake history.
- Each medication can have its own once-daily schedule and reminder settings.
- The Today view distinguishes doses that are needed, upcoming, taken, or need
  attention. Only an explicit intake record places a dose in the taken group.
- A schedule view presents occurrences by date without conflating a recurring
  schedule definition with any one occurrence.
- Global settings cover application-wide behavior and defaults. A medication's
  explicit schedule settings take precedence over those defaults.

## Operational behavior

- Reminder operation does not require a network or project-operated service.
- State survives process death and device restart within platform constraints.
- Users can export and delete their data.
- Android and iOS use thin native user interfaces.
