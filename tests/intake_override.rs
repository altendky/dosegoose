use dosegoose::{
    DeviceId, DoseId, DoseState, Effect, Event, EventOutcome, ForegroundAuthorization,
    IntakeRecord, MedicationId, Rejection, ScheduledDose, TimeSpan, Timestamp,
};

type TestResult = Result<(), Box<dyn std::error::Error>>;

const fn time(seconds: i64) -> Timestamp {
    Timestamp::from_unix_seconds(seconds)
}

fn state() -> Result<DoseState, dosegoose::ConfigurationError> {
    let schedule = ScheduledDose::new_with_early_window(
        DoseId::new(1),
        MedicationId::new(2),
        time(100),
        TimeSpan::from_seconds(10),
        TimeSpan::from_seconds(20),
    )?;
    Ok(DoseState::initialize(schedule, DeviceId::new(3))
        .into_parts()
        .0)
}

const fn override_event(now: i64, intake_at: i64, foreground: bool, unlocked: bool) -> Event {
    Event::RecordDoseOutsideWindow {
        now: time(now),
        intake_at: time(intake_at),
        authorization: ForegroundAuthorization::new(foreground, unlocked),
    }
}

#[test]
fn only_explicit_override_admits_pre_window_intake_and_cancels_presentation() -> TestResult {
    let due = state()?.transition(Event::ObserveTime { now: time(100) });
    let normal = due.state().transition(Event::RecordDose {
        now: time(105),
        intake_at: Some(time(89)),
        authorization: ForegroundAuthorization::new(true, true),
    });
    assert_eq!(
        normal.outcome(),
        EventOutcome::Rejected(Rejection::IntakeBeforeAvailableWindow)
    );
    assert_eq!(normal.state().intake(), None);

    let overridden = normal
        .state()
        .transition(override_event(105, 89, true, true));
    assert_eq!(overridden.outcome(), EventOutcome::Accepted);
    assert_eq!(
        overridden.state().intake().map(IntakeRecord::intake_at),
        Some(time(89))
    );
    assert_eq!(
        overridden.state().intake().map(IntakeRecord::recorded_at),
        Some(time(105))
    );
    assert_eq!(
        overridden
            .state()
            .intake()
            .map(IntakeRecord::outside_window_override),
        Some(true)
    );
    assert_eq!(
        overridden.effects(),
        &[Effect::CancelPresentation {
            device_id: DeviceId::new(3),
            dose_id: DoseId::new(1),
            kind: dosegoose::ReminderKind::Quiet,
        }]
    );
    assert_eq!(
        DoseState::restore_json(&overridden.state().snapshot_json()?)?,
        *overridden.state()
    );
    Ok(())
}

#[test]
fn override_preserves_authorization_availability_future_and_duplicate_guards() -> TestResult {
    let cases = [
        (
            105,
            89,
            false,
            true,
            Rejection::ForegroundAuthorizationRequired,
        ),
        (
            105,
            89,
            true,
            false,
            Rejection::ForegroundAuthorizationRequired,
        ),
        (89, 88, true, true, Rejection::DoseNotDue),
        (121, 89, true, true, Rejection::LateWindowElapsed),
        (105, 106, true, true, Rejection::IntakeTimeInFuture),
    ];
    for (now, intake_at, foreground, unlocked, expected) in cases {
        let transition = state()?.transition(override_event(now, intake_at, foreground, unlocked));
        assert_eq!(transition.outcome(), EventOutcome::Rejected(expected));
        assert_eq!(transition.state().intake(), None);
    }

    let recorded = state()?.transition(override_event(105, 89, true, true));
    let duplicate = recorded
        .state()
        .transition(override_event(106, 88, true, true));
    assert_eq!(
        duplicate.outcome(),
        EventOutcome::Rejected(Rejection::AlreadyRecorded)
    );
    assert_eq!(duplicate.state().intake(), recorded.state().intake());
    Ok(())
}

#[test]
fn override_request_for_in_window_time_records_without_override_provenance() -> TestResult {
    for intake_at in [90, 100, 105] {
        let transition = state()?.transition(override_event(105, intake_at, true, true));
        assert_eq!(transition.outcome(), EventOutcome::Accepted);
        assert_eq!(
            transition
                .state()
                .intake()
                .map(IntakeRecord::outside_window_override),
            Some(false)
        );
    }
    Ok(())
}
