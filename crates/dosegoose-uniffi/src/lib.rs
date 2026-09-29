//! Mobile-language binding adapter for [`dosegoose`].
//!
//! UniFFI-specific types belong here rather than in the portable core.

use dosegoose::{
    ConfigurationError, DeviceId, DoseGuidance, DoseId, DoseState, Effect, Event, EventOutcome,
    ForegroundAuthorization, MedicationId, RecordingAvailability, Rejection, ReminderKind,
    ScheduledDose, SnapshotError, TimeSpan, Timestamp,
};
use uuid::Uuid;

uniffi::setup_scaffolding!();

#[derive(Clone, Debug, uniffi::Record)]
pub struct InitializeDoseInput {
    pub occurrence_id: String,
    pub medication_id: String,
    pub device_id: String,
    pub scheduled_at_unix_seconds: i64,
    pub early_window_seconds: i64,
    pub late_window_seconds: i64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum GuidanceKind {
    Upcoming,
    EarlyAvailable,
    Due,
    Overdue,
    LateWindowElapsed,
    Recorded,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum RecordingAvailabilityKind {
    NotYetDue,
    Available,
    LateWindowElapsed,
    AlreadyRecorded,
}

#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct DoseProjection {
    pub guidance: GuidanceKind,
    pub recording_availability: RecordingAvailabilityKind,
    pub due_in_seconds: Option<u64>,
    pub late_by_seconds: Option<u64>,
    pub remaining_seconds: Option<u64>,
    pub intake_at_unix_seconds: Option<i64>,
    pub activity_accepted_at_unix_seconds: Option<i64>,
    pub quiet_visible: bool,
    pub intrusive_visible: bool,
    pub snoozed_until_unix_seconds: Option<i64>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum ReminderKindInput {
    Quiet,
    Intrusive,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum EffectKind {
    PresentQuiet,
    PresentIntrusively,
    CancelQuiet,
    CancelIntrusive,
    ScheduleEvaluation,
}

#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct RequestedEffect {
    pub kind: EffectKind,
    pub occurrence_id: String,
    pub device_id: String,
    pub at_unix_seconds: Option<i64>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum RejectionCode {
    StaleEvent,
    ForegroundAuthorizationRequired,
    DoseNotDue,
    LateWindowElapsed,
    AlreadyRecorded,
    ActivityNotAccepted,
    SnoozeMustEndInFuture,
    SnoozeBeyondLateWindow,
    IntakeTimeInFuture,
    IntakeBeforeAvailableWindow,
}

#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct CoreTransition {
    pub snapshot_json: String,
    pub projection: DoseProjection,
    pub effects: Vec<RequestedEffect>,
    pub accepted: bool,
    pub rejection: Option<RejectionCode>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Error)]
pub enum CoreBridgeError {
    InvalidIdentifier,
    InvalidEarlyWindow,
    InvalidLateWindow,
    InvalidConfiguration,
    InvalidSnapshot,
}

impl std::fmt::Display for CoreBridgeError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let message = match self {
            Self::InvalidIdentifier => "identifier must be a UUID",
            Self::InvalidEarlyWindow => "early window must be non-negative",
            Self::InvalidLateWindow => "late window must be non-negative",
            Self::InvalidConfiguration => "dose configuration is invalid",
            Self::InvalidSnapshot => "dose snapshot cannot be restored",
        };
        formatter.write_str(message)
    }
}

impl std::error::Error for CoreBridgeError {}

/// Creates a portable dose occurrence and immediately observes the supplied time.
///
/// # Errors
///
/// Returns an error for malformed UUIDs, a negative window, or schedule
/// arithmetic that cannot be represented by the portable core.
#[allow(clippy::needless_pass_by_value)] // UniFFI exports owned record values.
#[uniffi::export]
pub fn initialize_dose(
    input: InitializeDoseInput,
    now_unix_seconds: i64,
) -> Result<CoreTransition, CoreBridgeError> {
    let schedule = ScheduledDose::new_with_early_window(
        DoseId::from_u128(parse_uuid(&input.occurrence_id)?),
        MedicationId::from_u128(parse_uuid(&input.medication_id)?),
        Timestamp::from_unix_seconds(input.scheduled_at_unix_seconds),
        TimeSpan::from_seconds(
            input
                .early_window_seconds
                .try_into()
                .map_err(|_| CoreBridgeError::InvalidEarlyWindow)?,
        ),
        TimeSpan::from_seconds(
            input
                .late_window_seconds
                .try_into()
                .map_err(|_| CoreBridgeError::InvalidLateWindow)?,
        ),
    )
    .map_err(map_configuration_error)?;
    let initialized =
        DoseState::initialize(schedule, DeviceId::from_u128(parse_uuid(&input.device_id)?));
    let (state, mut effects, _) = initialized.into_parts();
    let observed = state.transition(Event::ObserveTime {
        now: Timestamp::from_unix_seconds(now_unix_seconds),
    });
    let (state, observed_effects, outcome) = observed.into_parts();
    effects.extend(observed_effects);
    transition_result(&state, effects, outcome, now_unix_seconds)
}

/// Applies an explicit time observation to a restored portable snapshot.
///
/// # Errors
///
/// Returns an error when the versioned snapshot cannot be restored.
#[allow(clippy::needless_pass_by_value)] // UniFFI exports owned strings.
#[uniffi::export]
pub fn observe_dose(
    snapshot_json: String,
    now_unix_seconds: i64,
) -> Result<CoreTransition, CoreBridgeError> {
    apply_event(
        &snapshot_json,
        Event::ObserveTime {
            now: Timestamp::from_unix_seconds(now_unix_seconds),
        },
        now_unix_seconds,
    )
}

/// Reports accepted activity evidence from an upstream platform policy.
///
/// # Errors
///
/// Returns an error when the versioned snapshot cannot be restored.
#[allow(clippy::needless_pass_by_value)]
#[uniffi::export]
pub fn accept_dose_activity(
    snapshot_json: String,
    now_unix_seconds: i64,
) -> Result<CoreTransition, CoreBridgeError> {
    apply_event(
        &snapshot_json,
        Event::AcceptActivity {
            now: Timestamp::from_unix_seconds(now_unix_seconds),
        },
        now_unix_seconds,
    )
}

/// Snoozes intrusive presentation until an explicit future instant.
///
/// # Errors
///
/// Returns an error when the versioned snapshot cannot be restored.
#[allow(clippy::needless_pass_by_value)]
#[uniffi::export]
pub fn snooze_dose(
    snapshot_json: String,
    now_unix_seconds: i64,
    until_unix_seconds: i64,
) -> Result<CoreTransition, CoreBridgeError> {
    apply_event(
        &snapshot_json,
        Event::Snooze {
            now: Timestamp::from_unix_seconds(now_unix_seconds),
            until: Timestamp::from_unix_seconds(until_unix_seconds),
        },
        now_unix_seconds,
    )
}

/// Explicitly returns reminder activity to inactive with foreground authorization.
///
/// # Errors
///
/// Returns an error when the versioned snapshot cannot be restored.
#[allow(clippy::needless_pass_by_value)]
#[uniffi::export]
pub fn return_dose_to_inactive(
    snapshot_json: String,
    now_unix_seconds: i64,
    app_is_foreground: bool,
    device_is_unlocked: bool,
) -> Result<CoreTransition, CoreBridgeError> {
    apply_event(
        &snapshot_json,
        Event::ReturnToInactive {
            now: Timestamp::from_unix_seconds(now_unix_seconds),
            authorization: ForegroundAuthorization::new(app_is_foreground, device_is_unlocked),
        },
        now_unix_seconds,
    )
}

/// Reports dismissal of a platform reminder without recording intake.
///
/// # Errors
///
/// Returns an error when the versioned snapshot cannot be restored.
#[allow(clippy::needless_pass_by_value)]
#[uniffi::export]
pub fn dismiss_dose_reminder(
    snapshot_json: String,
    now_unix_seconds: i64,
    kind: ReminderKindInput,
) -> Result<CoreTransition, CoreBridgeError> {
    apply_event(
        &snapshot_json,
        Event::DismissReminder {
            now: Timestamp::from_unix_seconds(now_unix_seconds),
            kind: match kind {
                ReminderKindInput::Quiet => ReminderKind::Quiet,
                ReminderKindInput::Intrusive => ReminderKind::Intrusive,
            },
        },
        now_unix_seconds,
    )
}

/// Attempts to record intake using explicit action time and authorization facts.
///
/// # Errors
///
/// Returns an error when the versioned snapshot cannot be restored. Domain
/// rejections are returned as a successful [`CoreTransition`].
#[allow(clippy::needless_pass_by_value)] // UniFFI exports owned strings.
#[uniffi::export]
pub fn record_dose(
    snapshot_json: String,
    now_unix_seconds: i64,
    intake_at_unix_seconds: Option<i64>,
    app_is_foreground: bool,
    device_is_unlocked: bool,
) -> Result<CoreTransition, CoreBridgeError> {
    apply_event(
        &snapshot_json,
        Event::RecordDose {
            now: Timestamp::from_unix_seconds(now_unix_seconds),
            intake_at: intake_at_unix_seconds.map(Timestamp::from_unix_seconds),
            authorization: ForegroundAuthorization::new(app_is_foreground, device_is_unlocked),
        },
        now_unix_seconds,
    )
}

/// Records an explicitly confirmed historical intake before the early window.
///
/// This does not bypass foreground authorization, action-time availability, or
/// the prohibition on future intake. Normal recording remains `record_dose`.
///
/// # Errors
///
/// Returns an error when the versioned snapshot cannot be restored. Domain
/// rejections are returned as a successful [`CoreTransition`].
#[allow(clippy::needless_pass_by_value)] // UniFFI exports owned strings.
#[uniffi::export]
pub fn record_dose_outside_window(
    snapshot_json: String,
    now_unix_seconds: i64,
    intake_at_unix_seconds: i64,
    app_is_foreground: bool,
    device_is_unlocked: bool,
) -> Result<CoreTransition, CoreBridgeError> {
    apply_event(
        &snapshot_json,
        Event::RecordDoseOutsideWindow {
            now: Timestamp::from_unix_seconds(now_unix_seconds),
            intake_at: Timestamp::from_unix_seconds(intake_at_unix_seconds),
            authorization: ForegroundAuthorization::new(app_is_foreground, device_is_unlocked),
        },
        now_unix_seconds,
    )
}

/// Projects a restored portable snapshot at an explicit time without changing it.
///
/// # Errors
///
/// Returns an error when the versioned snapshot cannot be restored.
#[allow(clippy::needless_pass_by_value)] // UniFFI exports owned strings.
#[uniffi::export]
pub fn project_dose(
    snapshot_json: String,
    now_unix_seconds: i64,
) -> Result<DoseProjection, CoreBridgeError> {
    let state = DoseState::restore_json(&snapshot_json).map_err(map_snapshot_error)?;
    Ok(project(&state, now_unix_seconds))
}

fn apply_event(
    snapshot_json: &str,
    event: Event,
    now_unix_seconds: i64,
) -> Result<CoreTransition, CoreBridgeError> {
    let state = DoseState::restore_json(snapshot_json).map_err(map_snapshot_error)?;
    let (state, effects, outcome) = state.transition(event).into_parts();
    transition_result(&state, effects, outcome, now_unix_seconds)
}

fn transition_result(
    state: &DoseState,
    effects: Vec<Effect>,
    outcome: EventOutcome,
    now_unix_seconds: i64,
) -> Result<CoreTransition, CoreBridgeError> {
    let (accepted, rejection) = match outcome {
        EventOutcome::Accepted => (true, None),
        EventOutcome::Rejected(rejection) => (false, Some(map_rejection(rejection))),
    };
    Ok(CoreTransition {
        snapshot_json: state.snapshot_json().map_err(map_snapshot_error)?,
        projection: project(state, now_unix_seconds),
        effects: effects.into_iter().map(map_effect).collect(),
        accepted,
        rejection,
    })
}

fn project(state: &DoseState, now_unix_seconds: i64) -> DoseProjection {
    let now = Timestamp::from_unix_seconds(now_unix_seconds);
    let (guidance, due_in_seconds, late_by_seconds, remaining_seconds, intake_at) =
        match state.guidance(now) {
            DoseGuidance::Upcoming { due_in } => (
                GuidanceKind::Upcoming,
                Some(due_in.seconds()),
                None,
                None,
                None,
            ),
            DoseGuidance::EarlyAvailable { due_in } => (
                GuidanceKind::EarlyAvailable,
                Some(due_in.seconds()),
                None,
                None,
                None,
            ),
            DoseGuidance::Due => (GuidanceKind::Due, None, None, None, None),
            DoseGuidance::Overdue { late_by, remaining } => (
                GuidanceKind::Overdue,
                None,
                Some(late_by.seconds()),
                Some(remaining.seconds()),
                None,
            ),
            DoseGuidance::LateWindowElapsed { late_by } => (
                GuidanceKind::LateWindowElapsed,
                None,
                Some(late_by.seconds()),
                None,
                None,
            ),
            DoseGuidance::Recorded { intake_at } => (
                GuidanceKind::Recorded,
                None,
                None,
                None,
                Some(intake_at.unix_seconds()),
            ),
        };
    let presentation = state.presentation();
    let activity_accepted_at_unix_seconds = match state.activity() {
        dosegoose::ActivityState::Inactive => None,
        dosegoose::ActivityState::Active { accepted_at } => Some(accepted_at.unix_seconds()),
    };
    DoseProjection {
        guidance,
        recording_availability: match state.recording_availability(now) {
            RecordingAvailability::NotYetDue => RecordingAvailabilityKind::NotYetDue,
            RecordingAvailability::Available => RecordingAvailabilityKind::Available,
            RecordingAvailability::LateWindowElapsed => {
                RecordingAvailabilityKind::LateWindowElapsed
            }
            RecordingAvailability::AlreadyRecorded => RecordingAvailabilityKind::AlreadyRecorded,
        },
        due_in_seconds,
        late_by_seconds,
        remaining_seconds,
        intake_at_unix_seconds: intake_at,
        activity_accepted_at_unix_seconds,
        quiet_visible: presentation.quiet_visible(),
        intrusive_visible: presentation.intrusive_visible(),
        snoozed_until_unix_seconds: state.snoozed_until().map(Timestamp::unix_seconds),
    }
}

fn map_effect(effect: Effect) -> RequestedEffect {
    let (kind, device_id, dose_id, at) = match effect {
        Effect::PresentQuiet { device_id, dose_id } => {
            (EffectKind::PresentQuiet, device_id, dose_id, None)
        }
        Effect::PresentIntrusively { device_id, dose_id } => {
            (EffectKind::PresentIntrusively, device_id, dose_id, None)
        }
        Effect::CancelPresentation {
            device_id,
            dose_id,
            kind: dosegoose::ReminderKind::Quiet,
        } => (EffectKind::CancelQuiet, device_id, dose_id, None),
        Effect::CancelPresentation {
            device_id,
            dose_id,
            kind: dosegoose::ReminderKind::Intrusive,
        } => (EffectKind::CancelIntrusive, device_id, dose_id, None),
        Effect::ScheduleEvaluation {
            device_id,
            dose_id,
            at,
        } => (
            EffectKind::ScheduleEvaluation,
            device_id,
            dose_id,
            Some(at.unix_seconds()),
        ),
    };
    RequestedEffect {
        kind,
        occurrence_id: uuid_string(dose_id.value()),
        device_id: uuid_string(device_id.value()),
        at_unix_seconds: at,
    }
}

const fn map_rejection(rejection: Rejection) -> RejectionCode {
    match rejection {
        Rejection::StaleEvent { .. } => RejectionCode::StaleEvent,
        Rejection::ForegroundAuthorizationRequired => {
            RejectionCode::ForegroundAuthorizationRequired
        }
        Rejection::DoseNotDue => RejectionCode::DoseNotDue,
        Rejection::LateWindowElapsed => RejectionCode::LateWindowElapsed,
        Rejection::AlreadyRecorded => RejectionCode::AlreadyRecorded,
        Rejection::ActivityNotAccepted => RejectionCode::ActivityNotAccepted,
        Rejection::SnoozeMustEndInFuture => RejectionCode::SnoozeMustEndInFuture,
        Rejection::SnoozeBeyondLateWindow => RejectionCode::SnoozeBeyondLateWindow,
        Rejection::IntakeTimeInFuture => RejectionCode::IntakeTimeInFuture,
        Rejection::IntakeBeforeAvailableWindow => RejectionCode::IntakeBeforeAvailableWindow,
    }
}

fn parse_uuid(value: &str) -> Result<u128, CoreBridgeError> {
    Uuid::parse_str(value)
        .map(|uuid| uuid.as_u128())
        .map_err(|_| CoreBridgeError::InvalidIdentifier)
}

fn uuid_string(value: u128) -> String {
    Uuid::from_u128(value).to_string()
}

const fn map_configuration_error(_: ConfigurationError) -> CoreBridgeError {
    CoreBridgeError::InvalidConfiguration
}

const fn map_snapshot_error(_: SnapshotError) -> CoreBridgeError {
    CoreBridgeError::InvalidSnapshot
}
