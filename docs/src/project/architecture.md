# Architecture

The repository has a functional core and platform shells.

```text
input event + prior state -> new state + requested effects
```

The Rust core owns reminder policy, state transitions, validation, portable
data formats, and eventual merge behavior. It receives time, identifiers,
activity observations, foreground/unlock state, and other dependencies as
explicit values.

Android, iOS, and desktop shells execute effects and report their results back
to the core. They own alarms, notifications, sensors, authentication, storage,
document providers, and native presentation.

The desktop application is a simulator and executable specification. It will
inject time, motion, lifecycle, notification, and multi-device events without
requiring a mobile emulator.

## First portable slice

The first implemented aggregate is one scheduled dose occurrence on one device.
A `ScheduledDose` carries distinct medication and occurrence identifiers, while
the device-local `DoseState` carries a device identifier. A future collection
can therefore contain many medications, occurrences, and device projections
without changing their identities.

`DoseState::transition` borrows prior state and accepts one explicit event. A
transition always returns replacement state, an ordered list of effects, and an
accepted or rejected action outcome. Late-window expiration advances before
action validation, so even a rejected late action can close the window and
cancel presentation. Other presentation reconciliation is event-specific.
Effects request quiet or intrusive presentation,
cancellation, or a future policy evaluation. They do not execute platform work.
Presentation state and effects are explicitly scoped to one device; future
synchronization should merge medication and intake facts, not treat presentation
visibility as global state.

Time is represented as shell-supplied Unix seconds. The core uses timestamps
only for ordering and duration arithmetic; calendar schedules, time zones, and
clock acquisition remain shell or future scheduling-layer responsibilities.

Accepted activity evidence is an input to the slice rather than a raw sensor
sample. This keeps sensor access outside the core while leaving room for a
portable activity-classification policy later. Likewise, sensitive events carry
explicit foreground and device-unlock facts rather than consulting lifecycle or
authentication APIs.

Initialization requests evaluations at the inclusive start of the early-taking
window and at the scheduled due time (one effect when those instants are equal).
The first actionable evaluation requests another evaluation at the first
instant after the inclusive late-dose deadline. These effects let a shell reach
policy boundaries without polling or reading core internals. Quiet presentation,
recording, and activity-gated intrusive presentation can all start when the
early-taking window opens.

## Android schedule generation

The first Android scheduling layer keeps three concepts separate:

```text
medication -> recurring schedule -> dated dose occurrences
```

The first recurring schedule produces one occurrence per local calendar day at
a configured local due time, with early- and late-taking durations. Android
resolves that local due time and expands it into the absolute `ScheduledDose`
occurrence consumed by the existing state machine.
It stores the core's versioned snapshot opaquely and atomically appends requested
effects to a pending outbox. Calendar, time-zone, clock, storage, and foreground
authorization inputs remain explicit at the bridge; schedule generation does
not introduce a clock or operating-system dependency into the portable core.

This boundary lets Today and schedule views aggregate many occurrences without
moving reminder policy into a native application. It also preserves recorded
history if a medication or its future schedule is later changed.
