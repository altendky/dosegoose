use dosegoose::{
    ActivityState, ConfigurationError, DeviceId, DoseId, DoseState, EventOutcome,
    ForegroundAuthorization, MedicationId, RecordingAvailability, Rejection, ScheduledDose,
    TimeSpan, Timestamp,
};

#[test]
fn identifier_and_time_value_types_round_trip_runtime_values() {
    let medication_value = std::hint::black_box(41_u64);
    let dose_value = std::hint::black_box(42_u64);
    let device_value = std::hint::black_box(43_u64);
    let timestamp_value = std::hint::black_box(-44_i64);
    let duration_value = std::hint::black_box(45_u64);

    assert_eq!(
        MedicationId::new(medication_value).value(),
        u128::from(medication_value)
    );
    assert_eq!(DoseId::new(dose_value).value(), u128::from(dose_value));
    assert_eq!(
        DeviceId::new(device_value).value(),
        u128::from(device_value)
    );
    assert_eq!(
        Timestamp::from_unix_seconds(timestamp_value).unix_seconds(),
        timestamp_value
    );
    assert_eq!(
        TimeSpan::from_seconds(duration_value).seconds(),
        duration_value
    );
}

#[test]
fn scheduled_dose_exposes_all_configured_values() -> Result<(), Box<dyn std::error::Error>> {
    let dose_id = DoseId::new(std::hint::black_box(10));
    let medication_id = MedicationId::new(std::hint::black_box(20));
    let scheduled_at = Timestamp::from_unix_seconds(std::hint::black_box(30));
    let late_window = TimeSpan::from_seconds(std::hint::black_box(40));
    let schedule = ScheduledDose::new(dose_id, medication_id, scheduled_at, late_window)?;

    assert_eq!(schedule.dose_id(), dose_id);
    assert_eq!(schedule.medication_id(), medication_id);
    assert_eq!(schedule.scheduled_at(), scheduled_at);
    assert_eq!(schedule.late_window(), late_window);
    assert_eq!(schedule.late_deadline(), Timestamp::from_unix_seconds(70));
    assert_eq!(schedule.expires_at(), Timestamp::from_unix_seconds(71));
    Ok(())
}

macro_rules! configuration_error_case {
    ($name:ident, $error:expr, $message:expr) => {
        #[test]
        fn $name() {
            assert_eq!($error.to_string(), $message);
        }
    };
}

configuration_error_case!(
    early_availability_underflow_has_a_stable_message,
    ConfigurationError::EarlyAvailabilityUnderflow,
    "early-taking availability underflowed"
);
configuration_error_case!(
    late_deadline_overflow_has_a_stable_message,
    ConfigurationError::LateDeadlineOverflow,
    "late-dose deadline overflowed"
);
configuration_error_case!(
    expiration_overflow_has_a_stable_message,
    ConfigurationError::ExpirationOverflow,
    "expiration time overflowed"
);

#[test]
fn every_rejection_has_a_nonempty_display_message() {
    let timestamp = Timestamp::from_unix_seconds(10);
    let rejections = [
        Rejection::StaleEvent {
            now: timestamp,
            last_observed_at: Timestamp::from_unix_seconds(11),
        },
        Rejection::ForegroundAuthorizationRequired,
        Rejection::DoseNotDue,
        Rejection::LateWindowElapsed,
        Rejection::AlreadyRecorded,
        Rejection::ActivityNotAccepted,
        Rejection::SnoozeMustEndInFuture,
        Rejection::SnoozeBeyondLateWindow,
        Rejection::IntakeTimeInFuture,
        Rejection::IntakeBeforeAvailableWindow,
    ];

    for rejection in rejections {
        assert!(!rejection.to_string().is_empty());
    }
}

#[test]
fn initialization_returns_complete_inert_state() -> Result<(), Box<dyn std::error::Error>> {
    let dose_id = DoseId::new(1);
    let medication_id = MedicationId::new(2);
    let device_id = DeviceId::new(3);
    let scheduled_at = Timestamp::from_unix_seconds(100);
    let schedule = ScheduledDose::new(
        dose_id,
        medication_id,
        scheduled_at,
        TimeSpan::from_seconds(50),
    )?;
    let transition = DoseState::initialize(schedule, device_id);

    assert_eq!(transition.outcome(), EventOutcome::Accepted);
    assert_eq!(transition.state().device_id(), device_id);
    assert_eq!(transition.state().schedule(), schedule);
    assert_eq!(transition.state().activity(), ActivityState::Inactive);
    assert_eq!(transition.state().last_observed_at(), None);
    assert_eq!(transition.state().snoozed_until(), None);
    assert_eq!(transition.state().intake(), None);
    assert!(!transition.state().presentation().quiet_visible());
    assert!(!transition.state().presentation().intrusive_visible());
    assert_eq!(
        transition
            .state()
            .recording_availability(Timestamp::from_unix_seconds(99)),
        RecordingAvailability::NotYetDue
    );
    Ok(())
}

macro_rules! arithmetic_case {
    ($name:ident, $scheduled:expr, $window:expr, $expected:expr) => {
        #[test]
        fn $name() {
            let result = ScheduledDose::new(
                DoseId::new(1),
                MedicationId::new(1),
                Timestamp::from_unix_seconds($scheduled),
                TimeSpan::from_seconds($window),
            );
            assert_eq!(result.map(|schedule| schedule.expires_at()), $expected);
        }
    };
}

arithmetic_case!(
    minimum_timestamp_with_zero_window_is_valid,
    i64::MIN,
    0,
    Ok(Timestamp::from_unix_seconds(i64::MIN + 1))
);
arithmetic_case!(
    maximum_minus_one_with_zero_window_is_valid,
    i64::MAX - 1,
    0,
    Ok(Timestamp::from_unix_seconds(i64::MAX))
);
arithmetic_case!(
    maximum_timestamp_with_zero_window_cannot_expire,
    i64::MAX,
    0,
    Err(ConfigurationError::ExpirationOverflow)
);
arithmetic_case!(
    maximum_timestamp_with_positive_window_overflows_deadline,
    i64::MAX,
    1,
    Err(ConfigurationError::LateDeadlineOverflow)
);
arithmetic_case!(
    full_unsigned_window_from_minimum_cannot_expire,
    i64::MIN,
    u64::MAX,
    Err(ConfigurationError::ExpirationOverflow)
);

#[test]
fn rejected_transition_parts_retain_time_driven_state() -> Result<(), Box<dyn std::error::Error>> {
    let schedule = ScheduledDose::new(
        DoseId::new(1),
        MedicationId::new(2),
        Timestamp::from_unix_seconds(100),
        TimeSpan::from_seconds(10),
    )?;
    let state = DoseState::initialize(schedule, DeviceId::new(3))
        .into_parts()
        .0;
    let transition = state.transition(dosegoose::Event::RecordDose {
        now: Timestamp::from_unix_seconds(111),
        intake_at: None,
        authorization: ForegroundAuthorization::new(true, true),
    });
    let (state, effects, outcome) = transition.into_parts();

    assert_eq!(
        outcome,
        EventOutcome::Rejected(Rejection::LateWindowElapsed)
    );
    assert!(effects.is_empty());
    assert_eq!(
        state.recording_availability(Timestamp::from_unix_seconds(110)),
        RecordingAvailability::LateWindowElapsed
    );
    assert_eq!(
        state.last_observed_at(),
        Some(Timestamp::from_unix_seconds(111))
    );
    Ok(())
}
