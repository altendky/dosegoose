use dosegoose::{
    ActivityState, ConfigurationError, DeviceId, DoseGuidance, DoseId, DoseState, Effect, Event,
    EventOutcome, ForegroundAuthorization, MedicationId, RecordingAvailability, Rejection,
    ReminderKind, ScheduledDose, TimeSpan, Timestamp,
};

const DUE: Timestamp = Timestamp::from_unix_seconds(10_000);
const AVAILABLE: Timestamp = Timestamp::from_unix_seconds(2_800);
const DEADLINE: Timestamp = Timestamp::from_unix_seconds(13_600);
const DEVICE: DeviceId = DeviceId::new(3);

type TestResult<T = ()> = Result<T, Box<dyn std::error::Error>>;

fn schedule() -> Result<ScheduledDose, ConfigurationError> {
    ScheduledDose::new_with_early_window(
        DoseId::new(1),
        MedicationId::new(2),
        DUE,
        TimeSpan::from_seconds(7_200),
        TimeSpan::from_seconds(3_600),
    )
}

fn state() -> Result<DoseState, ConfigurationError> {
    Ok(DoseState::initialize(schedule()?, DEVICE).into_parts().0)
}

const fn authorized() -> ForegroundAuthorization {
    ForegroundAuthorization::new(true, true)
}

#[test]
fn schedule_exposes_early_window_and_inclusive_start() -> TestResult {
    let schedule = schedule()?;

    assert_eq!(schedule.early_window(), TimeSpan::from_seconds(7_200));
    assert_eq!(schedule.available_from(), AVAILABLE);
    assert_eq!(schedule.late_deadline(), DEADLINE);
    Ok(())
}

#[test]
fn early_window_underflow_is_rejected() {
    assert_eq!(
        ScheduledDose::new_with_early_window(
            DoseId::new(1),
            MedicationId::new(2),
            Timestamp::from_unix_seconds(i64::MIN),
            TimeSpan::from_seconds(1),
            TimeSpan::from_seconds(0),
        ),
        Err(ConfigurationError::EarlyAvailabilityUnderflow),
    );
}

#[test]
fn initialization_schedules_early_and_due_boundaries_in_order() -> TestResult {
    let transition = DoseState::initialize(schedule()?, DEVICE);

    assert_eq!(
        transition.effects(),
        [
            Effect::ScheduleEvaluation {
                device_id: DEVICE,
                dose_id: DoseId::new(1),
                at: AVAILABLE,
            },
            Effect::ScheduleEvaluation {
                device_id: DEVICE,
                dose_id: DoseId::new(1),
                at: DUE,
            },
        ],
    );
    Ok(())
}

#[test]
fn zero_early_window_keeps_one_due_boundary() -> TestResult {
    let schedule = ScheduledDose::new(
        DoseId::new(1),
        MedicationId::new(2),
        DUE,
        TimeSpan::from_seconds(3_600),
    )?;

    assert_eq!(DoseState::initialize(schedule, DEVICE).effects().len(), 1);
    Ok(())
}

#[test]
fn guidance_distinguishes_before_early_early_due_and_late_boundaries() -> TestResult {
    let state = state()?;
    let cases = [
        (
            Timestamp::from_unix_seconds(2_799),
            DoseGuidance::Upcoming {
                due_in: TimeSpan::from_seconds(7_201),
            },
        ),
        (
            AVAILABLE,
            DoseGuidance::EarlyAvailable {
                due_in: TimeSpan::from_seconds(7_200),
            },
        ),
        (
            Timestamp::from_unix_seconds(9_999),
            DoseGuidance::EarlyAvailable {
                due_in: TimeSpan::from_seconds(1),
            },
        ),
        (DUE, DoseGuidance::Due),
        (
            Timestamp::from_unix_seconds(10_001),
            DoseGuidance::Overdue {
                late_by: TimeSpan::from_seconds(1),
                remaining: TimeSpan::from_seconds(3_599),
            },
        ),
        (
            DEADLINE,
            DoseGuidance::Overdue {
                late_by: TimeSpan::from_seconds(3_600),
                remaining: TimeSpan::from_seconds(0),
            },
        ),
        (
            Timestamp::from_unix_seconds(13_601),
            DoseGuidance::LateWindowElapsed {
                late_by: TimeSpan::from_seconds(3_601),
            },
        ),
    ];

    for (now, expected) in cases {
        assert_eq!(
            state.guidance(now),
            expected,
            "unexpected guidance at {now:?}"
        );
    }
    Ok(())
}

#[test]
fn recording_availability_spans_the_full_early_through_late_window() -> TestResult {
    let state = state()?;
    let cases = [
        (2_799, RecordingAvailability::NotYetDue),
        (2_800, RecordingAvailability::Available),
        (9_999, RecordingAvailability::Available),
        (10_000, RecordingAvailability::Available),
        (13_600, RecordingAvailability::Available),
        (13_601, RecordingAvailability::LateWindowElapsed),
    ];

    for (seconds, expected) in cases {
        assert_eq!(
            state.recording_availability(Timestamp::from_unix_seconds(seconds)),
            expected,
            "unexpected availability at {seconds}",
        );
    }
    Ok(())
}

#[test]
fn quiet_presentation_begins_at_early_boundary() -> TestResult {
    let transition = state()?.transition(Event::ObserveTime { now: AVAILABLE });

    assert!(transition.state().presentation().quiet_visible());
    assert!(!transition.state().presentation().intrusive_visible());
    assert!(transition.effects().contains(&Effect::PresentQuiet {
        device_id: DEVICE,
        dose_id: DoseId::new(1),
    }));
    Ok(())
}

#[test]
fn accepted_activity_presents_intrusively_at_the_early_boundary() -> TestResult {
    let early = state()?.transition(Event::AcceptActivity { now: AVAILABLE });

    assert!(matches!(
        early.state().activity(),
        ActivityState::Active { .. }
    ));
    assert!(early.state().presentation().intrusive_visible());
    assert!(early.effects().contains(&Effect::PresentIntrusively {
        device_id: DEVICE,
        dose_id: DoseId::new(1),
    }));
    Ok(())
}

#[test]
fn due_boundary_re_presents_an_early_notification_that_was_dismissed() -> TestResult {
    let early = state()?.transition(Event::ObserveTime { now: AVAILABLE });
    let dismissed = early.state().transition(Event::DismissReminder {
        now: Timestamp::from_unix_seconds(5_000),
        kind: ReminderKind::Quiet,
    });
    let due = dismissed
        .state()
        .transition(Event::ObserveTime { now: DUE });

    assert!(due.state().presentation().quiet_visible());
    assert_eq!(
        due.effects(),
        [Effect::PresentQuiet {
            device_id: DEVICE,
            dose_id: DoseId::new(1),
        }],
    );
    Ok(())
}

#[test]
fn record_now_is_accepted_during_early_window() -> TestResult {
    let now = Timestamp::from_unix_seconds(5_000);
    let transition = state()?.transition(Event::RecordDose {
        now,
        intake_at: None,
        authorization: authorized(),
    });

    assert_eq!(transition.outcome(), EventOutcome::Accepted);
    assert_eq!(
        transition
            .state()
            .intake()
            .map(dosegoose::IntakeRecord::intake_at),
        Some(now)
    );
    Ok(())
}

#[test]
fn explicit_intake_accepts_exact_early_boundary() -> TestResult {
    let transition = state()?.transition(Event::RecordDose {
        now: Timestamp::from_unix_seconds(5_000),
        intake_at: Some(AVAILABLE),
        authorization: authorized(),
    });

    assert_eq!(transition.outcome(), EventOutcome::Accepted);
    Ok(())
}

#[test]
fn explicit_intake_rejects_one_second_before_early_boundary() -> TestResult {
    let transition = state()?.transition(Event::RecordDose {
        now: Timestamp::from_unix_seconds(5_000),
        intake_at: Some(Timestamp::from_unix_seconds(2_799)),
        authorization: authorized(),
    });

    assert_eq!(
        transition.outcome(),
        EventOutcome::Rejected(Rejection::IntakeBeforeAvailableWindow),
    );
    Ok(())
}

#[test]
fn snooze_is_available_during_the_early_window() -> TestResult {
    let active = state()?.transition(Event::AcceptActivity { now: AVAILABLE });
    let transition = active.state().transition(Event::Snooze {
        now: Timestamp::from_unix_seconds(5_000),
        until: Timestamp::from_unix_seconds(5_600),
    });

    assert_eq!(transition.outcome(), EventOutcome::Accepted);
    assert!(matches!(
        transition.state().activity(),
        ActivityState::Active { .. }
    ));
    assert!(!transition.state().presentation().intrusive_visible());
    Ok(())
}

#[test]
fn snapshot_round_trip_preserves_early_policy() -> TestResult {
    let state = state()?;
    let restored = DoseState::restore_json(&state.snapshot_json()?)?;

    assert_eq!(restored, state);
    assert_eq!(restored.schedule().available_from(), AVAILABLE);
    Ok(())
}
