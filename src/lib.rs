//! Portable, sans-I/O behavior for Dose Goose.
//!
//! The core receives all observations and dependencies as values and returns
//! updated state plus requested effects. It does not read clocks, sensors,
//! storage, networks, process-global state, or operating-system APIs.

/// A stable identifier for a medication, independent of any one scheduled dose.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct MedicationId(u128);

impl MedicationId {
    /// Creates an identifier from storage- or shell-owned data.
    #[must_use]
    pub const fn new(value: u64) -> Self {
        Self(value as u128)
    }

    /// Creates an identifier from a full 128-bit, UUID-compatible value.
    #[must_use]
    pub const fn from_u128(value: u128) -> Self {
        Self(value)
    }

    /// Returns the portable numeric representation.
    #[must_use]
    pub const fn value(self) -> u128 {
        self.0
    }
}

/// A stable identifier for one occurrence in a medication schedule.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct DoseId(u128);

impl DoseId {
    /// Creates an identifier from storage- or shell-owned data.
    #[must_use]
    pub const fn new(value: u64) -> Self {
        Self(value as u128)
    }

    /// Creates an identifier from a full 128-bit, UUID-compatible value.
    #[must_use]
    pub const fn from_u128(value: u128) -> Self {
        Self(value)
    }

    /// Returns the portable numeric representation.
    #[must_use]
    pub const fn value(self) -> u128 {
        self.0
    }
}

/// A stable identifier for the device-local reminder projection.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct DeviceId(u128);

impl DeviceId {
    /// Creates an identifier from storage- or shell-owned data.
    #[must_use]
    pub const fn new(value: u64) -> Self {
        Self(value as u128)
    }

    /// Creates an identifier from a full 128-bit, UUID-compatible value.
    #[must_use]
    pub const fn from_u128(value: u128) -> Self {
        Self(value)
    }

    /// Returns the portable numeric representation.
    #[must_use]
    pub const fn value(self) -> u128 {
        self.0
    }
}

/// An absolute instant expressed as Unix seconds.
///
/// The shell is responsible for obtaining and converting wall-clock time. The
/// core treats this as an ordered integer and never reads a clock itself.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub struct Timestamp(i64);

impl Timestamp {
    /// Creates a timestamp from Unix seconds supplied by a shell.
    #[must_use]
    pub const fn from_unix_seconds(seconds: i64) -> Self {
        Self(seconds)
    }

    /// Returns the timestamp as Unix seconds.
    #[must_use]
    pub const fn unix_seconds(self) -> i64 {
        self.0
    }

    fn checked_add(self, duration: TimeSpan) -> Option<Self> {
        let result = i128::from(self.0) + i128::from(duration.seconds());
        i64::try_from(result).ok().map(Self)
    }

    fn checked_sub(self, duration: TimeSpan) -> Option<Self> {
        let result = i128::from(self.0) - i128::from(duration.seconds());
        i64::try_from(result).ok().map(Self)
    }

    const fn elapsed_since(self, earlier: Self) -> TimeSpan {
        TimeSpan::from_seconds(self.0.abs_diff(earlier.0))
    }
}

/// A non-negative duration used by portable reminder policy.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub struct TimeSpan(u64);

impl TimeSpan {
    /// Creates a duration from seconds.
    #[must_use]
    pub const fn from_seconds(seconds: u64) -> Self {
        Self(seconds)
    }

    /// Returns the duration in seconds.
    #[must_use]
    pub const fn seconds(self) -> u64 {
        self.0
    }
}

/// Immutable policy and identity for one scheduled dose.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ScheduledDose {
    dose_id: DoseId,
    medication_id: MedicationId,
    scheduled_at: Timestamp,
    early_window: TimeSpan,
    available_from: Timestamp,
    late_window: TimeSpan,
    late_deadline: Timestamp,
    expires_at: Timestamp,
}

impl ScheduledDose {
    /// Creates a scheduled dose, rejecting a deadline that cannot be represented.
    ///
    /// # Errors
    ///
    /// Returns [`ConfigurationError::LateDeadlineOverflow`] when adding the late
    /// window to the scheduled time cannot be represented by [`Timestamp`], or
    /// [`ConfigurationError::ExpirationOverflow`] when the first second after
    /// that inclusive deadline cannot be represented.
    pub fn new(
        dose_id: DoseId,
        medication_id: MedicationId,
        scheduled_at: Timestamp,
        late_window: TimeSpan,
    ) -> Result<Self, ConfigurationError> {
        Self::new_with_early_window(
            dose_id,
            medication_id,
            scheduled_at,
            TimeSpan::from_seconds(0),
            late_window,
        )
    }

    /// Creates a scheduled dose with explicit early- and late-taking windows.
    ///
    /// The early window begins inclusively before the scheduled time. The late
    /// window ends inclusively after it. Recording and reminder presentation may
    /// begin anywhere within that actionable interval.
    ///
    /// # Errors
    ///
    /// Returns [`ConfigurationError::EarlyAvailabilityUnderflow`] when
    /// subtracting the early window cannot be represented, in addition to the
    /// deadline errors documented by [`ScheduledDose::new`].
    pub fn new_with_early_window(
        dose_id: DoseId,
        medication_id: MedicationId,
        scheduled_at: Timestamp,
        early_window: TimeSpan,
        late_window: TimeSpan,
    ) -> Result<Self, ConfigurationError> {
        let available_from = scheduled_at
            .checked_sub(early_window)
            .ok_or(ConfigurationError::EarlyAvailabilityUnderflow)?;
        let late_deadline = scheduled_at
            .checked_add(late_window)
            .ok_or(ConfigurationError::LateDeadlineOverflow)?;
        let expires_at = late_deadline
            .checked_add(TimeSpan::from_seconds(1))
            .ok_or(ConfigurationError::ExpirationOverflow)?;
        Ok(Self {
            dose_id,
            medication_id,
            scheduled_at,
            early_window,
            available_from,
            late_window,
            late_deadline,
            expires_at,
        })
    }

    /// Identifies this schedule occurrence.
    #[must_use]
    pub const fn dose_id(self) -> DoseId {
        self.dose_id
    }

    /// Identifies the medication to which the occurrence belongs.
    #[must_use]
    pub const fn medication_id(self) -> MedicationId {
        self.medication_id
    }

    /// Returns when this dose first becomes due.
    #[must_use]
    pub const fn scheduled_at(self) -> Timestamp {
        self.scheduled_at
    }

    /// Returns how long before the due time quiet presentation and recording begin.
    #[must_use]
    pub const fn early_window(self) -> TimeSpan {
        self.early_window
    }

    /// Returns the inclusive start of the early-taking window.
    #[must_use]
    pub const fn available_from(self) -> Timestamp {
        self.available_from
    }

    /// Returns the configured period during which recording remains available.
    #[must_use]
    pub const fn late_window(self) -> TimeSpan {
        self.late_window
    }

    /// Returns the inclusive end of the late-dose window.
    #[must_use]
    pub const fn late_deadline(self) -> Timestamp {
        self.late_deadline
    }

    /// Returns the first second after the inclusive late-dose window.
    #[must_use]
    pub const fn expires_at(self) -> Timestamp {
        self.expires_at
    }
}

/// Failure to construct valid reminder policy.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ConfigurationError {
    /// Subtracting the early window from the scheduled time underflowed the timestamp.
    EarlyAvailabilityUnderflow,
    /// Adding the late window to the scheduled time overflowed the timestamp.
    LateDeadlineOverflow,
    /// The first instant after the inclusive deadline cannot be represented.
    ExpirationOverflow,
}

impl std::fmt::Display for ConfigurationError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::EarlyAvailabilityUnderflow => {
                formatter.write_str("early-taking availability underflowed")
            }
            Self::LateDeadlineOverflow => formatter.write_str("late-dose deadline overflowed"),
            Self::ExpirationOverflow => formatter.write_str("expiration time overflowed"),
        }
    }
}

impl std::error::Error for ConfigurationError {}

/// Explicit platform facts authorizing a sensitive foreground action.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ForegroundAuthorization {
    app_is_foreground: bool,
    device_is_unlocked: bool,
}

impl ForegroundAuthorization {
    /// Creates authorization evidence from current shell-owned lifecycle state.
    #[must_use]
    pub const fn new(app_is_foreground: bool, device_is_unlocked: bool) -> Self {
        Self {
            app_is_foreground,
            device_is_unlocked,
        }
    }

    /// Returns whether both required platform conditions are satisfied.
    #[must_use]
    pub const fn permits_sensitive_action(self) -> bool {
        self.app_is_foreground && self.device_is_unlocked
    }
}

/// Whether meaningful phone-carried activity has been accepted for this dose.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ActivityState {
    /// No accepted activity is currently associated with the dose.
    Inactive,
    /// Activity was accepted at the supplied instant.
    Active { accepted_at: Timestamp },
}

/// The two platform presentation channels modeled by the first slice.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ReminderKind {
    /// A non-intrusive notification or in-app indication.
    Quiet,
    /// An alarm-like presentation allowed only after activity is accepted.
    Intrusive,
}

/// Current presentation state as last requested or reported by the shell.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct PresentationState {
    quiet_visible: bool,
    intrusive_visible: bool,
}

impl PresentationState {
    /// Returns whether quiet presentation is believed to be visible.
    #[must_use]
    pub const fn quiet_visible(self) -> bool {
        self.quiet_visible
    }

    /// Returns whether intrusive presentation is believed to be visible.
    #[must_use]
    pub const fn intrusive_visible(self) -> bool {
        self.intrusive_visible
    }
}

/// An explicit, authorized record that the medication was taken.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct IntakeRecord {
    intake_at: Timestamp,
    recorded_at: Timestamp,
    outside_window_override: bool,
}

impl IntakeRecord {
    /// Returns the user-confirmed time at which medication was taken.
    #[must_use]
    pub const fn intake_at(self) -> Timestamp {
        self.intake_at
    }

    /// Returns when the unlocked foreground app recorded the action.
    #[must_use]
    pub const fn recorded_at(self) -> Timestamp {
        self.recorded_at
    }

    /// Whether an explicit override admitted intake before the early-taking window.
    #[must_use]
    pub const fn outside_window_override(self) -> bool {
        self.outside_window_override
    }
}

/// User-facing interpretation of a dose at an explicitly supplied time.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DoseGuidance {
    /// The early-taking window has not opened.
    Upcoming { due_in: TimeSpan },
    /// Recording is available before the scheduled time.
    EarlyAvailable { due_in: TimeSpan },
    /// The scheduled time is exactly now.
    Due,
    /// The dose is late but can still be recorded.
    Overdue {
        late_by: TimeSpan,
        remaining: TimeSpan,
    },
    /// The inclusive late-dose deadline has passed.
    LateWindowElapsed { late_by: TimeSpan },
    /// An explicit intake record completes this occurrence.
    Recorded { intake_at: Timestamp },
}

/// Whether the foreground app may currently offer the record action.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RecordingAvailability {
    /// Recording is unavailable before the early-taking window.
    NotYetDue,
    /// Recording is available through the inclusive late deadline.
    Available,
    /// Recording is unavailable once the late deadline has passed.
    LateWindowElapsed,
    /// This occurrence already has an intake record.
    AlreadyRecorded,
}

/// An environmental observation or explicit user action supplied to the core.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Event {
    /// Re-evaluates policy at a shell-supplied current time.
    ObserveTime { now: Timestamp },
    /// Reports activity evidence that an upstream policy has accepted.
    AcceptActivity { now: Timestamp },
    /// Temporarily suppresses intrusive presentation without changing activity.
    Snooze { now: Timestamp, until: Timestamp },
    /// Explicitly returns the occurrence to inactivity from the foreground app.
    ReturnToInactive {
        now: Timestamp,
        authorization: ForegroundAuthorization,
    },
    /// Reports that a platform presentation was dismissed by the user.
    DismissReminder { now: Timestamp, kind: ReminderKind },
    /// Explicitly records intake from the unlocked foreground app.
    RecordDose {
        now: Timestamp,
        intake_at: Option<Timestamp>,
        authorization: ForegroundAuthorization,
    },
    /// Explicitly records a historical pre-window intake after user confirmation.
    RecordDoseOutsideWindow {
        now: Timestamp,
        intake_at: Timestamp,
        authorization: ForegroundAuthorization,
    },
}

impl Event {
    const fn now(self) -> Timestamp {
        match self {
            Self::ObserveTime { now }
            | Self::AcceptActivity { now }
            | Self::Snooze { now, .. }
            | Self::ReturnToInactive { now, .. }
            | Self::DismissReminder { now, .. }
            | Self::RecordDose { now, .. }
            | Self::RecordDoseOutsideWindow { now, .. } => now,
        }
    }
}

/// Platform work requested by a successful transition.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Effect {
    /// Show or update a quiet presentation for this occurrence.
    PresentQuiet {
        device_id: DeviceId,
        dose_id: DoseId,
    },
    /// Show or update an intrusive presentation for this occurrence.
    PresentIntrusively {
        device_id: DeviceId,
        dose_id: DoseId,
    },
    /// Remove a currently visible presentation.
    CancelPresentation {
        device_id: DeviceId,
        dose_id: DoseId,
        kind: ReminderKind,
    },
    /// Arrange for the shell to re-evaluate policy at an explicit time.
    ScheduleEvaluation {
        device_id: DeviceId,
        dose_id: DoseId,
        at: Timestamp,
    },
}

/// Why an event's requested action was rejected.
///
/// Non-stale events still advance time-driven state and may request effects.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Rejection {
    /// The event predates the latest event observed by this device projection.
    StaleEvent {
        now: Timestamp,
        last_observed_at: Timestamp,
    },
    /// The action requires an unlocked foreground application.
    ForegroundAuthorizationRequired,
    /// This dose has not reached its early-taking window.
    DoseNotDue,
    /// The late-dose deadline has passed.
    LateWindowElapsed,
    /// Intake has already been recorded for this occurrence.
    AlreadyRecorded,
    /// An intrusive-only action was requested before activity was accepted.
    ActivityNotAccepted,
    /// A snooze must end after the event's current time.
    SnoozeMustEndInFuture,
    /// A snooze cannot extend past the late-dose deadline.
    SnoozeBeyondLateWindow,
    /// The supplied intake time was in the future relative to the action.
    IntakeTimeInFuture,
    /// The supplied intake time predates this occurrence's early-taking window.
    IntakeBeforeAvailableWindow,
}

impl std::fmt::Display for Rejection {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{self:?}")
    }
}

impl std::error::Error for Rejection {}

/// Whether the requested event action was accepted after policy time advanced.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum EventOutcome {
    /// The event was applied.
    Accepted,
    /// The action was not applied, though time-driven state may have advanced.
    Rejected(Rejection),
}

/// Device-local state for one occurrence; collections and persistence are external.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DoseState {
    device_id: DeviceId,
    schedule: ScheduledDose,
    activity: ActivityState,
    presentation: PresentationState,
    snoozed_until: Option<Timestamp>,
    intake: Option<IntakeRecord>,
    expiration_evaluation_scheduled: bool,
    expired_at: Option<Timestamp>,
    last_observed_at: Option<Timestamp>,
}

/// Current version of the portable [`DoseState`] snapshot format.
pub const DOSE_SNAPSHOT_VERSION: u32 = 3;

/// A failure to encode or restore a portable state snapshot.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SnapshotError {
    /// The snapshot is not valid JSON or does not match its declared schema.
    InvalidEncoding,
    /// The snapshot declares a version this core does not understand.
    UnsupportedVersion(u32),
    /// The decoded snapshot violates a domain invariant.
    InvalidState,
}

impl std::fmt::Display for SnapshotError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidEncoding => formatter.write_str("dose snapshot encoding is invalid"),
            Self::UnsupportedVersion(version) => {
                write!(formatter, "dose snapshot version {version} is unsupported")
            }
            Self::InvalidState => formatter.write_str("dose snapshot state is invalid"),
        }
    }
}

impl std::error::Error for SnapshotError {}

#[derive(serde::Deserialize)]
struct SnapshotVersionProbe {
    version: u32,
}

#[derive(serde::Deserialize, serde::Serialize)]
#[serde(deny_unknown_fields)]
struct DoseSnapshotEnvelopeV1 {
    version: u32,
    state: DoseSnapshotV1,
}

#[derive(serde::Deserialize, serde::Serialize)]
#[serde(deny_unknown_fields)]
struct DoseSnapshotV1 {
    device_id: u128,
    dose_id: u128,
    medication_id: u128,
    scheduled_at: i64,
    late_window_seconds: u64,
    activity_accepted_at: Option<i64>,
    quiet_visible: bool,
    intrusive_visible: bool,
    snoozed_until: Option<i64>,
    intake_at: Option<i64>,
    recorded_at: Option<i64>,
    expiration_evaluation_scheduled: bool,
    expired_at: Option<i64>,
    last_observed_at: Option<i64>,
}

#[derive(serde::Deserialize, serde::Serialize)]
#[serde(deny_unknown_fields)]
struct DoseSnapshotEnvelopeV2 {
    version: u32,
    state: DoseSnapshotV2,
}

#[derive(serde::Deserialize, serde::Serialize)]
#[serde(deny_unknown_fields)]
struct DoseSnapshotV2 {
    device_id: u128,
    dose_id: u128,
    medication_id: u128,
    scheduled_at: i64,
    early_window_seconds: u64,
    late_window_seconds: u64,
    activity_accepted_at: Option<i64>,
    quiet_visible: bool,
    intrusive_visible: bool,
    snoozed_until: Option<i64>,
    intake_at: Option<i64>,
    recorded_at: Option<i64>,
    expiration_evaluation_scheduled: bool,
    expired_at: Option<i64>,
    last_observed_at: Option<i64>,
}

#[derive(serde::Deserialize, serde::Serialize)]
#[serde(deny_unknown_fields)]
struct DoseSnapshotEnvelopeV3 {
    version: u32,
    state: DoseSnapshotV3,
}

#[derive(serde::Deserialize, serde::Serialize)]
#[serde(deny_unknown_fields)]
#[allow(clippy::struct_excessive_bools)] // Persisted schema keeps independent flags explicit.
struct DoseSnapshotV3 {
    device_id: u128,
    dose_id: u128,
    medication_id: u128,
    scheduled_at: i64,
    early_window_seconds: u64,
    late_window_seconds: u64,
    activity_accepted_at: Option<i64>,
    quiet_visible: bool,
    intrusive_visible: bool,
    snoozed_until: Option<i64>,
    intake_at: Option<i64>,
    recorded_at: Option<i64>,
    outside_window_override: bool,
    expiration_evaluation_scheduled: bool,
    expired_at: Option<i64>,
    last_observed_at: Option<i64>,
}

impl DoseState {
    /// Creates device-local state and requests evaluation at each policy boundary.
    ///
    /// The initialization effect is part of the contract: shells should persist
    /// the returned state and execute the returned platform work.
    #[must_use]
    pub fn initialize(schedule: ScheduledDose, device_id: DeviceId) -> Transition {
        Transition {
            state: Self {
                device_id,
                schedule,
                activity: ActivityState::Inactive,
                presentation: PresentationState {
                    quiet_visible: false,
                    intrusive_visible: false,
                },
                snoozed_until: None,
                intake: None,
                expiration_evaluation_scheduled: false,
                expired_at: None,
                last_observed_at: None,
            },
            effects: {
                let mut effects = vec![Effect::ScheduleEvaluation {
                    device_id,
                    dose_id: schedule.dose_id,
                    at: schedule.available_from,
                }];
                if schedule.available_from != schedule.scheduled_at {
                    effects.push(Effect::ScheduleEvaluation {
                        device_id,
                        dose_id: schedule.dose_id,
                        at: schedule.scheduled_at,
                    });
                }
                effects
            },
            outcome: EventOutcome::Accepted,
        }
    }

    /// Identifies the device-local presentation projection.
    #[must_use]
    pub const fn device_id(&self) -> DeviceId {
        self.device_id
    }

    /// Returns immutable identity and policy for this occurrence.
    #[must_use]
    pub const fn schedule(&self) -> ScheduledDose {
        self.schedule
    }

    /// Returns the accepted activity state.
    #[must_use]
    pub const fn activity(&self) -> ActivityState {
        self.activity
    }

    /// Returns the latest known presentation state.
    #[must_use]
    pub const fn presentation(&self) -> PresentationState {
        self.presentation
    }

    /// Returns when intrusive presentation may resume, if snoozed.
    #[must_use]
    pub const fn snoozed_until(&self) -> Option<Timestamp> {
        self.snoozed_until
    }

    /// Returns an explicit intake record, if one exists.
    #[must_use]
    pub const fn intake(&self) -> Option<IntakeRecord> {
        self.intake
    }

    /// Returns the latest event time accepted by this device projection.
    #[must_use]
    pub const fn last_observed_at(&self) -> Option<Timestamp> {
        self.last_observed_at
    }

    /// Encodes all portable state in a versioned, core-owned JSON snapshot.
    ///
    /// The shell may store this value opaquely, but must not interpret or alter
    /// the private state-machine fields.
    ///
    /// # Errors
    ///
    /// Returns [`SnapshotError::InvalidEncoding`] if serialization fails.
    pub fn snapshot_json(&self) -> Result<String, SnapshotError> {
        let activity_accepted_at = match self.activity {
            ActivityState::Inactive => None,
            ActivityState::Active { accepted_at } => Some(accepted_at.unix_seconds()),
        };
        let (intake_at, recorded_at, outside_window_override) =
            self.intake.map_or((None, None, false), |intake| {
                (
                    Some(intake.intake_at.unix_seconds()),
                    Some(intake.recorded_at.unix_seconds()),
                    intake.outside_window_override,
                )
            });
        let snapshot = DoseSnapshotEnvelopeV3 {
            version: DOSE_SNAPSHOT_VERSION,
            state: DoseSnapshotV3 {
                device_id: self.device_id.value(),
                dose_id: self.schedule.dose_id.value(),
                medication_id: self.schedule.medication_id.value(),
                scheduled_at: self.schedule.scheduled_at.unix_seconds(),
                early_window_seconds: self.schedule.early_window.seconds(),
                late_window_seconds: self.schedule.late_window.seconds(),
                activity_accepted_at,
                quiet_visible: self.presentation.quiet_visible,
                intrusive_visible: self.presentation.intrusive_visible,
                snoozed_until: self.snoozed_until.map(Timestamp::unix_seconds),
                intake_at,
                recorded_at,
                outside_window_override,
                expiration_evaluation_scheduled: self.expiration_evaluation_scheduled,
                expired_at: self.expired_at.map(Timestamp::unix_seconds),
                last_observed_at: self.last_observed_at.map(Timestamp::unix_seconds),
            },
        };
        serde_json::to_string(&snapshot).map_err(|_| SnapshotError::InvalidEncoding)
    }

    /// Restores a snapshot created by [`DoseState::snapshot_json`].
    ///
    /// # Errors
    ///
    /// Rejects malformed data, unknown versions, configuration overflow, and
    /// state combinations that cannot be produced by the state machine.
    pub fn restore_json(snapshot_json: &str) -> Result<Self, SnapshotError> {
        let version_probe: SnapshotVersionProbe =
            serde_json::from_str(snapshot_json).map_err(|_| SnapshotError::InvalidEncoding)?;
        match version_probe.version {
            1 => {
                let snapshot: DoseSnapshotEnvelopeV1 = serde_json::from_str(snapshot_json)
                    .map_err(|_| SnapshotError::InvalidEncoding)?;
                Self::restore_v1(&snapshot.state)
            }
            2 => {
                let snapshot: DoseSnapshotEnvelopeV2 = serde_json::from_str(snapshot_json)
                    .map_err(|_| SnapshotError::InvalidEncoding)?;
                Self::restore_v2(&snapshot.state)
            }
            DOSE_SNAPSHOT_VERSION => {
                let snapshot: DoseSnapshotEnvelopeV3 = serde_json::from_str(snapshot_json)
                    .map_err(|_| SnapshotError::InvalidEncoding)?;
                Self::restore_v3(&snapshot.state)
            }
            version => Err(SnapshotError::UnsupportedVersion(version)),
        }
    }

    fn restore_v1(snapshot: &DoseSnapshotV1) -> Result<Self, SnapshotError> {
        Self::restore_fields(snapshot, TimeSpan::from_seconds(0), false)
    }

    fn restore_v2(snapshot: &DoseSnapshotV2) -> Result<Self, SnapshotError> {
        let legacy_fields = DoseSnapshotV1 {
            device_id: snapshot.device_id,
            dose_id: snapshot.dose_id,
            medication_id: snapshot.medication_id,
            scheduled_at: snapshot.scheduled_at,
            late_window_seconds: snapshot.late_window_seconds,
            activity_accepted_at: snapshot.activity_accepted_at,
            quiet_visible: snapshot.quiet_visible,
            intrusive_visible: snapshot.intrusive_visible,
            snoozed_until: snapshot.snoozed_until,
            intake_at: snapshot.intake_at,
            recorded_at: snapshot.recorded_at,
            expiration_evaluation_scheduled: snapshot.expiration_evaluation_scheduled,
            expired_at: snapshot.expired_at,
            last_observed_at: snapshot.last_observed_at,
        };
        Self::restore_fields(
            &legacy_fields,
            TimeSpan::from_seconds(snapshot.early_window_seconds),
            false,
        )
    }

    fn restore_v3(snapshot: &DoseSnapshotV3) -> Result<Self, SnapshotError> {
        let legacy_fields = DoseSnapshotV1 {
            device_id: snapshot.device_id,
            dose_id: snapshot.dose_id,
            medication_id: snapshot.medication_id,
            scheduled_at: snapshot.scheduled_at,
            late_window_seconds: snapshot.late_window_seconds,
            activity_accepted_at: snapshot.activity_accepted_at,
            quiet_visible: snapshot.quiet_visible,
            intrusive_visible: snapshot.intrusive_visible,
            snoozed_until: snapshot.snoozed_until,
            intake_at: snapshot.intake_at,
            recorded_at: snapshot.recorded_at,
            expiration_evaluation_scheduled: snapshot.expiration_evaluation_scheduled,
            expired_at: snapshot.expired_at,
            last_observed_at: snapshot.last_observed_at,
        };
        Self::restore_fields(
            &legacy_fields,
            TimeSpan::from_seconds(snapshot.early_window_seconds),
            snapshot.outside_window_override,
        )
    }

    fn restore_fields(
        snapshot: &DoseSnapshotV1,
        early_window: TimeSpan,
        outside_window_override: bool,
    ) -> Result<Self, SnapshotError> {
        let schedule = ScheduledDose::new_with_early_window(
            DoseId::from_u128(snapshot.dose_id),
            MedicationId::from_u128(snapshot.medication_id),
            Timestamp::from_unix_seconds(snapshot.scheduled_at),
            early_window,
            TimeSpan::from_seconds(snapshot.late_window_seconds),
        )
        .map_err(|_| SnapshotError::InvalidState)?;
        let activity = snapshot
            .activity_accepted_at
            .map_or(ActivityState::Inactive, |value| ActivityState::Active {
                accepted_at: Timestamp::from_unix_seconds(value),
            });
        let intake = match (snapshot.intake_at, snapshot.recorded_at) {
            (None, None) if !outside_window_override => None,
            (Some(intake_at), Some(recorded_at)) => Some(IntakeRecord {
                intake_at: Timestamp::from_unix_seconds(intake_at),
                recorded_at: Timestamp::from_unix_seconds(recorded_at),
                outside_window_override,
            }),
            _ => return Err(SnapshotError::InvalidState),
        };
        let state = Self {
            device_id: DeviceId::from_u128(snapshot.device_id),
            schedule,
            activity,
            presentation: PresentationState {
                quiet_visible: snapshot.quiet_visible,
                intrusive_visible: snapshot.intrusive_visible,
            },
            snoozed_until: snapshot.snoozed_until.map(Timestamp::from_unix_seconds),
            intake,
            expiration_evaluation_scheduled: snapshot.expiration_evaluation_scheduled,
            expired_at: snapshot.expired_at.map(Timestamp::from_unix_seconds),
            last_observed_at: snapshot.last_observed_at.map(Timestamp::from_unix_seconds),
        };
        state.validate_restored()?;
        Ok(state)
    }

    fn validate_restored(&self) -> Result<(), SnapshotError> {
        if self.intake.is_some() && self.expired_at.is_some() {
            return Err(SnapshotError::InvalidState);
        }
        if let Some(intake) = self.intake
            && (((intake.intake_at < self.schedule.available_from)
                != intake.outside_window_override)
                || intake.intake_at > intake.recorded_at
                || intake.recorded_at < self.schedule.available_from
                || intake.recorded_at > self.schedule.late_deadline)
        {
            return Err(SnapshotError::InvalidState);
        }
        if let Some(expired_at) = self.expired_at
            && expired_at < self.schedule.expires_at
        {
            return Err(SnapshotError::InvalidState);
        }
        if let Some(snoozed_until) = self.snoozed_until
            && (self.intake.is_some()
                || self.expired_at.is_some()
                || !matches!(self.activity, ActivityState::Active { .. })
                || snoozed_until > self.schedule.late_deadline)
        {
            return Err(SnapshotError::InvalidState);
        }
        if (self.intake.is_some() || self.expired_at.is_some())
            && (self.presentation.quiet_visible || self.presentation.intrusive_visible)
        {
            return Err(SnapshotError::InvalidState);
        }
        if self.presentation.intrusive_visible
            && (!matches!(self.activity, ActivityState::Active { .. })
                || self.snoozed_until.is_some())
        {
            return Err(SnapshotError::InvalidState);
        }
        Ok(())
    }

    /// Returns the current guidance without changing state.
    #[must_use]
    pub fn guidance(&self, now: Timestamp) -> DoseGuidance {
        if let Some(intake) = self.intake {
            return DoseGuidance::Recorded {
                intake_at: intake.intake_at,
            };
        }
        if let Some(expired_at) = self.expired_at {
            let effective_now = now.max(expired_at);
            return DoseGuidance::LateWindowElapsed {
                late_by: effective_now.elapsed_since(self.schedule.scheduled_at),
            };
        }
        if now < self.schedule.available_from {
            return DoseGuidance::Upcoming {
                due_in: self.schedule.scheduled_at.elapsed_since(now),
            };
        }
        if now < self.schedule.scheduled_at {
            return DoseGuidance::EarlyAvailable {
                due_in: self.schedule.scheduled_at.elapsed_since(now),
            };
        }
        if now == self.schedule.scheduled_at {
            return DoseGuidance::Due;
        }
        if now <= self.schedule.late_deadline {
            return DoseGuidance::Overdue {
                late_by: now.elapsed_since(self.schedule.scheduled_at),
                remaining: self.schedule.late_deadline.elapsed_since(now),
            };
        }
        DoseGuidance::LateWindowElapsed {
            late_by: now.elapsed_since(self.schedule.scheduled_at),
        }
    }

    /// Returns whether intake may be recorded at an explicit current time.
    #[must_use]
    pub fn recording_availability(&self, now: Timestamp) -> RecordingAvailability {
        if self.intake.is_some() {
            RecordingAvailability::AlreadyRecorded
        } else if self.expired_at.is_some() {
            RecordingAvailability::LateWindowElapsed
        } else if now < self.schedule.available_from {
            RecordingAvailability::NotYetDue
        } else if now <= self.schedule.late_deadline {
            RecordingAvailability::Available
        } else {
            RecordingAvailability::LateWindowElapsed
        }
    }

    /// Applies one event as a deterministic state transition.
    ///
    /// Late-window expiration is advanced before an action is validated. A
    /// rejected action can therefore still return replacement state and
    /// cancellation effects. Other presentation reconciliation is event-specific.
    /// Callers should persist every returned transition and inspect
    /// [`Transition::outcome`].
    #[must_use]
    pub fn transition(&self, event: Event) -> Transition {
        let now = event.now();
        if let Some(last_observed_at) = self.last_observed_at
            && now < last_observed_at
        {
            return Transition {
                state: self.clone(),
                effects: Vec::new(),
                outcome: EventOutcome::Rejected(Rejection::StaleEvent {
                    now,
                    last_observed_at,
                }),
            };
        }
        let mut next = self.clone();
        next.last_observed_at = Some(now);
        let mut effects = Vec::new();
        next.expire_if_needed(now, &mut effects);

        let result = match event {
            Event::ObserveTime { .. } => {
                next.reconcile(now, &mut effects);
                Ok(())
            }
            Event::AcceptActivity { .. } => {
                if next.intake.is_none()
                    && next.expired_at.is_none()
                    && !matches!(next.activity, ActivityState::Active { .. })
                {
                    next.activity = ActivityState::Active { accepted_at: now };
                }
                next.reconcile(now, &mut effects);
                Ok(())
            }
            Event::Snooze { until, .. } => next.snooze(now, until, &mut effects),
            Event::ReturnToInactive { authorization, .. } => next
                .return_to_inactive(authorization, &mut effects)
                .map(|()| next.reconcile(now, &mut effects)),
            Event::DismissReminder { kind, .. } => {
                next.dismiss(kind);
                Ok(())
            }
            Event::RecordDose {
                intake_at,
                authorization,
                ..
            } => next.record(now, intake_at, authorization, false, &mut effects),
            Event::RecordDoseOutsideWindow {
                intake_at,
                authorization,
                ..
            } => next.record(now, Some(intake_at), authorization, true, &mut effects),
        };
        let outcome = match result {
            Ok(()) => EventOutcome::Accepted,
            Err(rejection) => EventOutcome::Rejected(rejection),
        };

        Transition {
            state: next,
            effects,
            outcome,
        }
    }

    fn reconcile(&mut self, now: Timestamp, effects: &mut Vec<Effect>) {
        if self.intake.is_some() {
            return;
        }
        if self.expire_if_needed(now, effects) {
            return;
        }
        if now < self.schedule.available_from {
            return;
        }
        if !self.presentation.quiet_visible {
            self.presentation.quiet_visible = true;
            effects.push(Effect::PresentQuiet {
                device_id: self.device_id,
                dose_id: self.schedule.dose_id,
            });
        }
        if !self.expiration_evaluation_scheduled {
            self.expiration_evaluation_scheduled = true;
            effects.push(Effect::ScheduleEvaluation {
                device_id: self.device_id,
                dose_id: self.schedule.dose_id,
                at: self.schedule.expires_at,
            });
        }

        if let Some(until) = self.snoozed_until {
            if now < until {
                return;
            }
            self.snoozed_until = None;
        }

        if matches!(self.activity, ActivityState::Active { .. })
            && !self.presentation.intrusive_visible
        {
            self.presentation.intrusive_visible = true;
            effects.push(Effect::PresentIntrusively {
                device_id: self.device_id,
                dose_id: self.schedule.dose_id,
            });
        }
    }

    fn expire_if_needed(&mut self, now: Timestamp, effects: &mut Vec<Effect>) -> bool {
        if self.intake.is_some() || now < self.schedule.expires_at {
            return false;
        }
        if self.expired_at.is_none() {
            self.expired_at = Some(now);
        }
        self.cancel_visible_presentations(effects);
        self.snoozed_until = None;
        true
    }

    fn snooze(
        &mut self,
        now: Timestamp,
        until: Timestamp,
        effects: &mut Vec<Effect>,
    ) -> Result<(), Rejection> {
        self.ensure_pending_and_available(now)?;
        if !matches!(self.activity, ActivityState::Active { .. }) {
            return Err(Rejection::ActivityNotAccepted);
        }
        if until <= now {
            return Err(Rejection::SnoozeMustEndInFuture);
        }
        if until > self.schedule.late_deadline {
            return Err(Rejection::SnoozeBeyondLateWindow);
        }

        self.snoozed_until = Some(until);
        self.cancel_if_visible(ReminderKind::Intrusive, effects);
        effects.push(Effect::ScheduleEvaluation {
            device_id: self.device_id,
            dose_id: self.schedule.dose_id,
            at: until,
        });
        Ok(())
    }

    fn return_to_inactive(
        &mut self,
        authorization: ForegroundAuthorization,
        effects: &mut Vec<Effect>,
    ) -> Result<(), Rejection> {
        if !authorization.permits_sensitive_action() {
            return Err(Rejection::ForegroundAuthorizationRequired);
        }
        self.activity = ActivityState::Inactive;
        self.snoozed_until = None;
        self.cancel_if_visible(ReminderKind::Intrusive, effects);
        Ok(())
    }

    const fn dismiss(&mut self, kind: ReminderKind) {
        match kind {
            ReminderKind::Quiet => self.presentation.quiet_visible = false,
            ReminderKind::Intrusive => self.presentation.intrusive_visible = false,
        }
    }

    fn record(
        &mut self,
        now: Timestamp,
        intake_at: Option<Timestamp>,
        authorization: ForegroundAuthorization,
        allow_outside_window: bool,
        effects: &mut Vec<Effect>,
    ) -> Result<(), Rejection> {
        if !authorization.permits_sensitive_action() {
            return Err(Rejection::ForegroundAuthorizationRequired);
        }
        self.ensure_pending_and_available(now)?;
        let intake_at = intake_at.unwrap_or(now);
        if intake_at > now {
            return Err(Rejection::IntakeTimeInFuture);
        }
        let outside_window_override = intake_at < self.schedule.available_from;
        if outside_window_override && !allow_outside_window {
            return Err(Rejection::IntakeBeforeAvailableWindow);
        }

        self.intake = Some(IntakeRecord {
            intake_at,
            recorded_at: now,
            outside_window_override,
        });
        self.snoozed_until = None;
        self.cancel_visible_presentations(effects);
        Ok(())
    }

    fn ensure_pending_and_available(&self, now: Timestamp) -> Result<(), Rejection> {
        match self.recording_availability(now) {
            RecordingAvailability::Available => Ok(()),
            RecordingAvailability::NotYetDue => Err(Rejection::DoseNotDue),
            RecordingAvailability::LateWindowElapsed => Err(Rejection::LateWindowElapsed),
            RecordingAvailability::AlreadyRecorded => Err(Rejection::AlreadyRecorded),
        }
    }

    fn cancel_visible_presentations(&mut self, effects: &mut Vec<Effect>) {
        self.cancel_if_visible(ReminderKind::Quiet, effects);
        self.cancel_if_visible(ReminderKind::Intrusive, effects);
    }

    fn cancel_if_visible(&mut self, kind: ReminderKind, effects: &mut Vec<Effect>) {
        let visible = match kind {
            ReminderKind::Quiet => &mut self.presentation.quiet_visible,
            ReminderKind::Intrusive => &mut self.presentation.intrusive_visible,
        };
        if *visible {
            *visible = false;
            effects.push(Effect::CancelPresentation {
                device_id: self.device_id,
                dose_id: self.schedule.dose_id,
                kind,
            });
        }
    }
}

/// A successful transition's replacement state and inspectable platform work.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Transition {
    state: DoseState,
    effects: Vec<Effect>,
    outcome: EventOutcome,
}

impl Transition {
    /// Returns the replacement state.
    #[must_use]
    pub const fn state(&self) -> &DoseState {
        &self.state
    }

    /// Returns platform effects in deterministic request order.
    #[must_use]
    pub fn effects(&self) -> &[Effect] {
        &self.effects
    }

    /// Returns whether the requested action was accepted.
    #[must_use]
    pub const fn outcome(&self) -> EventOutcome {
        self.outcome
    }

    /// Splits the transition into owned state, effects, and event outcome.
    #[must_use]
    pub fn into_parts(self) -> (DoseState, Vec<Effect>, EventOutcome) {
        (self.state, self.effects, self.outcome)
    }
}
