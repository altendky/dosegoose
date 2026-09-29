# Android application

The Android application will begin as one conventional Gradle `app` module with
a single Compose activity. Source is organized by feature inside the standard
Android source set rather than creating build modules before they are useful.

```text
apps/android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/
└── app/
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── kotlin/.../dosegoose/
        │   │   ├── app/
        │   │   ├── feature/
        │   │   │   ├── today/
        │   │   │   ├── schedule/
        │   │   │   ├── medications/
        │   │   │   └── settings/
        │   │   ├── data/
        │   │   ├── corebridge/
        │   │   ├── platform/
        │   │   └── designsystem/
        │   └── res/
        ├── test/
        └── androidTest/
```

The primary navigation destinations are Today, Schedule, and Medications.
Settings is initially reachable from the app bar rather than occupying a
permanent navigation destination.

Android system back and the app-bar Back control share one navigation policy.
Back dismisses the innermost transient surface first: the earlier-intake
dialog, then a medication editor, then Settings. From Schedule or Medications,
back returns to the Today start destination. Only back from Today with no
transient surface is delegated to Android to leave the app.

- **Today** groups occurrences as needed now, upcoming, taken, or needing
  attention and includes a compact daily timeline.
- **Schedule** presents a date-oriented agenda. Day and week presentations come
  before a month grid because they communicate multiple same-day doses more
  clearly.
- **Medications** owns the medication list, details, add/edit flow, and the
  medication's schedule and reminder settings.
- **Settings** owns application-wide appearance, defaults, notification and
  activity behavior, privacy, export and backup, and platform diagnostics.

Each feature uses unidirectional data flow. A route connects navigation and
dependencies, a ViewModel exposes one UI state and accepts actions, and a
stateless screen renders that state. Feature code does not implement reminder
or recurrence policy.

The supporting packages have narrow responsibilities:

- `data` exposes observable repositories and projections such as a Today
  snapshot, medication summary, and schedule day. Persistence is hidden behind
  these repository boundaries. Room stores medications, once-daily schedules,
  dated occurrences, and a pending-effect outbox. Preferences DataStore stores
  theme selection.
- `corebridge` isolates generated UniFFI code and translates between Kotlin and
  portable types without duplicating domain rules.
- `platform` owns Android clocks, alarms, notifications, activity evidence,
  foreground and unlock facts, lifecycle callbacks, and effect execution.
- `designsystem` owns semantic colors, typography, reusable status components,
  and System, Light, and Dark appearance modes.

For every transition, replacement core state must be persisted before requested
platform effects are executed. Android entry points such as activities,
receivers, and workers coordinate work but are not sources of truth.

The project follows Android's recommended separation between UI and data layers
while omitting a separate Kotlin domain layer initially. The portable Rust core
already owns the shared domain rules; a Kotlin use-case layer should be added
only if native orchestration becomes meaningfully reusable or complex.

## Local Today slice

Room schema version 4 contains separate `medications` and
`once_daily_schedules` tables joined by a medication foreign key. Both records
have UUID string identities, and each medication currently has exactly one
once-daily schedule. A small metadata table records that the first-run examples
have been seeded and holds a device UUID; it prevents a deliberately emptied or
edited database from being silently reset on a later launch. Room's exported schema is checked into
`app/schemas` so future schema changes can have explicit, tested migrations.

Android expands each enabled once-daily schedule into an immutable occurrence
for a local date, resolves the configured due time with Android's time-zone
rules, and passes the resulting Unix timestamp plus early- and late-taking
durations into the portable core. The
occurrence UUID is deterministically derived from the schedule UUID and local
date. Its row stores the schedule, zone, UTC offset, policy values, an opaque
versioned core snapshot, and a denormalized core projection for queries. The
snapshot is the transition source of truth; projection columns are only a
core-produced cache.

The shell reconciles yesterday when its late window may still be open, today,
and tomorrow so an early window that crosses midnight can open on time. It does
so on process start, foreground entry, midnight, and the next known policy
boundary. Existing occurrence configuration is not rewritten when a schedule
is edited, so an edit applies to future materialization. Occurrence rows do not
cascade when configuration is removed because recorded history must survive.

Reconciliation scope and Today-display scope are intentionally separate. For
the temporary once-daily rule, Today normally shows the current local date. It
may carry forward yesterday's occurrence while that occurrence is still
recordable and today's early-taking window has not opened. It may likewise show
tomorrow's occurrence once a cross-midnight early window opens. When a newer
occurrence becomes actionable, the prior occurrence for that schedule leaves
Today even if their windows overlap. This prevents an expired prior dose from
appearing as an unlabeled duplicate while retaining useful cross-midnight
access. A frequency-independent definition of Today remains future work.

Schedules now persist an effective-from instant. When a medication is created,
re-enabled, or its schedule changes, an occurrence whose scheduled time is
already earlier than that instant is excluded. The conservative first policy
therefore starts that medication with its next occurrence; an explicit same-day
retroactive opt-in remains a future product decision.

Every core-requested effect is appended in order to `pending_effects` in the
same Room transaction as its replacement snapshot. Android reconciles the
latest core presentation state into one notification card per occurrence using
two versioned notification channels: a regular default-importance notification
channel and a high-importance alarm-style channel. The core's `Quiet`
presentation name means non-intrusive here; it does
not mean that Android silences the notification. Accepted activity replaces the
regular card with the alarm-style card. The first intrusive presentation sounds,
then Android requests another notification alert every ten minutes while the
dose remains active and inside its late window. This is a repeated one-shot
notification alert, not a continuously sounding ringtone; Android timing and
sound behavior remain subject to user channel settings and system restrictions.
Notification copy places the scheduled time and absolute "Take by" cutoff on
separate lines in the expanded card, not in a relative countdown that could
freeze on an old card. The pending
core presentation effect anchors the repeat cadence durably across
process death. A routine refresh updates the card without sounding again.
The card is marked ongoing and has no dismissal action. Its intrusive action
is **Snooze 10 min** when ten minutes fit, then **Snooze until [cutoff]** for
the remainder of the late window. Snoozing pauses sound without recording
intake or resetting accepted activity. Tapping the card or **Open app** enters
the foreground application for explicit dose actions. Android 14 and later may
still permit users to clear an ongoing notification; Dose Goose restores the
card silently without changing dose state or cancelling scheduled reminder
work. Notifications
show generic content on the lock screen and remain local to the phone rather
than forwarding medication details to companion devices.

Alert reconciliation reads presentation state, future evaluations, and the
current pending-effect ID cutoff as one serialized snapshot. Android
acknowledges only through that cutoff after platform work succeeds, so an
effect created concurrently remains durable for the next pass. Routine state
projection is bounded to the maximum live late-window horizon, while older
occurrences with unhandled effects remain eligible for cleanup.

AlarmManager schedules policy evaluations and the next local midnight outside
the process lifetime. Exact alarms are used when the user grants Alarms &
reminders access; otherwise Android's inexact idle-capable alarm is the explicit
degraded fallback. Boot, package replacement, wall-clock changes, time-zone
changes, and exact-alarm permission changes rebuild scheduling from durable
state. Notification permission and precise-timing readiness are visible in
Settings.

Android uses the Google Play services Activity Recognition Transition API as a
low-power activity-evidence source. Detection is registered only while at least
one enabled occurrence is within its actionable early/late window and still
inactive. A latest
`WALKING` or `RUNNING` enter transition applies accepted evidence to every such
occurrence. Exits, stillness, cycling, vehicle travel, generic `ON_FOOT`, device
unlock, and notification interaction do not count. The Transition API performs
the sensor classification; Dose Goose does not run a foreground service or
store raw sensor samples or an activity history.

API 29 and newer request `ACTIVITY_RECOGNITION` only from an explicit Settings
action; API 26 through 28 use the legacy Google Play services manifest
permission. Missing permission, unavailable Play services, or registration
failure leaves regular due reminders operational and is shown in Settings. The
shell reconstructs registration after process start, foreground entry, reboot,
package replacement, clock changes, and reminder-policy boundaries. Because
registration begins when the early-taking window opens, someone already walking
at that boundary may not generate a new enter transition. A persisted device
activity latch avoids that false negative after activity has previously been
accepted; otherwise the initial policy waits for an unambiguous transition.

Accepted activity is persisted as a device-level latch and projected into each
actionable occurrence's opaque portable-core snapshot. Repeated callbacks skip
already-active occurrences, so they cannot re-present a dismissed intrusive
reminder. Activity exit never resets the latch. The Today surface exposes the
authorized foreground action that returns the device and its active occurrences
to regular reminders.

While that device latch is active, Android also requests classification-only
events from the Google Play services Sleep API. The initial bedtime policy is
intentionally aimed at overnight sleep rather than naps: only events inside a
configurable window (default 8:00 PM through noon) count, high-confidence sleep
classifications must continue for two hours, qualifying classifications may be
at most 30 minutes apart, and strong awake evidence restarts the interval.
Short ambiguous readings do not immediately discard a candidate. A walking or
running enter transition clears any pending sleep candidate and advances its
processed-event watermark so a delayed older batch cannot clear fresh activity.

Confirmed sleep clears only the device-level activity latch. It does not alter
an occurrence whose portable snapshot already contains accepted activity, so
it cannot silence an intrusive reminder already in progress. Later occurrences
therefore begin inactive until new walking/running evidence is accepted. The
manual unlocked-foreground reset remains available and still resets active
occurrences as well as the latch. Sleep monitoring uses the same Physical
activity permission, is active only while the latch is active, and is restored
through the same reconciliation paths as activity monitoring. Settings exposes
the overnight window and both registration states.

Sleep registration and removal are serialized so an older asynchronous Google
Play services task cannot leave monitoring opposite the latest durable state.
Changing the configured window discards any candidate accumulated under the old
window while preserving the processed-event watermark. A manual wall-clock
change clears that watermark and rebases the active-latch timestamp to the new
clock; a time-zone change discards the candidate without changing epoch-time
ordering.

Today is derived from portable projections. A foreground, unlocked action can
record an available dose at the captured action time or at an explicitly
selected earlier time. Ordinary recording enforces the early-taking boundary;
an explicit out-of-window override permits a selected past time before that
boundary while the occurrence itself remains recordable. Closing a notification
or alarm is not represented as intake. Corrupt or unsupported snapshots fail
visibly and are never replaced with fresh state.

"Record earlier" opens Android's platform time-picker dialog, initialized from
the phone's current time when opened. Since this picker chooses only a clock
time, Android resolves it to the latest matching instant within that dose's
available window, including a prior local date when the window crosses
midnight. If the selected past time falls before the window, a dialog shows the
full selected date and window bounds and offers **Change time**, **Record
anyway**, and **Cancel**. **Record anyway** invokes the distinct portable-core
override action, not ordinary recording. A future or nonexistent
daylight-saving clock time cannot be overridden. If the same clock time is valid
on multiple dates, the latest wins; explicit date selection remains an open UX
question for longer windows. The override is not available after the dose's
late deadline; retrospective logging for expired occurrences remains open.

The medication editor uses the platform time picker for the due time, following
the user's 12/24-hour preference. Both platform time pickers follow Dose Goose's
resolved system/light/dark appearance choice. Early and late durations use
explicit 30-minute controls. The initial product intentionally couples the
early regular notification to early-taking eligibility; separating those
settings remains an open product question.

Preferences DataStore stores the System, Light, or Dark appearance choice. UI
navigation, open editors, and loading/errors are not persisted. Random
identifier generation remains in the Android shell,
outside reducers and the sans-I/O core.

The database and preferences are application-private, excluded from Android
backup, and require no network permission. This is platform sandboxing, not
custom database encryption; encrypted export and any stronger at-rest design
remain separate future work.

- [Android app architecture](https://developer.android.com/topic/architecture)
- [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- [Adaptive list-detail layouts](https://developer.android.com/develop/adaptive-apps/guides/list-detail)
