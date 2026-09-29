use dosegoose::{
    DeviceId, DoseId, DoseState, Event, ForegroundAuthorization, IntakeRecord, MedicationId,
    ScheduledDose, SnapshotError, TimeSpan, Timestamp,
};
use serde_json::{Value, json};

fn initialized_state() -> Result<DoseState, Box<dyn std::error::Error>> {
    let schedule = ScheduledDose::new(
        DoseId::new(1),
        MedicationId::new(2),
        Timestamp::from_unix_seconds(100),
        TimeSpan::from_seconds(20),
    )?;
    Ok(DoseState::initialize(schedule, DeviceId::new(3))
        .into_parts()
        .0)
}

fn snapshot_value(state: &DoseState) -> Result<Value, Box<dyn std::error::Error>> {
    Ok(serde_json::from_str(&state.snapshot_json()?)?)
}

fn restore_value(value: &Value) -> Result<DoseState, SnapshotError> {
    DoseState::restore_json(&value.to_string())
}

#[test]
fn snapshot_round_trips_full_width_ids_and_initialized_state()
-> Result<(), Box<dyn std::error::Error>> {
    let schedule = ScheduledDose::new(
        DoseId::from_u128(u128::MAX - 1),
        MedicationId::from_u128(u128::MAX - 2),
        Timestamp::from_unix_seconds(100),
        TimeSpan::from_seconds(20),
    )?;
    let state = DoseState::initialize(schedule, DeviceId::from_u128(u128::MAX - 3))
        .into_parts()
        .0;

    let restored = DoseState::restore_json(&state.snapshot_json()?)?;

    assert_eq!(restored, state);
    assert_eq!(restored.device_id().value(), u128::MAX - 3);
    assert_eq!(restored.schedule().dose_id().value(), u128::MAX - 1);
    assert_eq!(restored.schedule().medication_id().value(), u128::MAX - 2);
    Ok(())
}

#[test]
fn snapshots_restore_every_meaningful_transition_state() -> Result<(), Box<dyn std::error::Error>> {
    let initialized = initialized_state()?;
    let due = initialized
        .transition(Event::ObserveTime {
            now: Timestamp::from_unix_seconds(100),
        })
        .into_parts()
        .0;
    let active = due
        .transition(Event::AcceptActivity {
            now: Timestamp::from_unix_seconds(101),
        })
        .into_parts()
        .0;
    let snoozed = active
        .transition(Event::Snooze {
            now: Timestamp::from_unix_seconds(102),
            until: Timestamp::from_unix_seconds(110),
        })
        .into_parts()
        .0;
    let recorded = snoozed
        .transition(Event::RecordDose {
            now: Timestamp::from_unix_seconds(111),
            intake_at: Some(Timestamp::from_unix_seconds(105)),
            authorization: ForegroundAuthorization::new(true, true),
        })
        .into_parts()
        .0;
    let expired = initialized
        .transition(Event::ObserveTime {
            now: Timestamp::from_unix_seconds(121),
        })
        .into_parts()
        .0;

    for state in [initialized, due, active, snoozed, recorded, expired] {
        assert_eq!(DoseState::restore_json(&state.snapshot_json()?)?, state);
    }
    Ok(())
}

#[test]
fn restored_state_remains_transition_equivalent() -> Result<(), Box<dyn std::error::Error>> {
    let state = initialized_state()?
        .transition(Event::ObserveTime {
            now: Timestamp::from_unix_seconds(105),
        })
        .into_parts()
        .0;
    let restored = DoseState::restore_json(&state.snapshot_json()?)?;
    let event = Event::RecordDose {
        now: Timestamp::from_unix_seconds(110),
        intake_at: None,
        authorization: ForegroundAuthorization::new(true, true),
    };

    assert_eq!(restored.transition(event), state.transition(event));
    Ok(())
}

#[test]
fn malformed_snapshot_is_rejected() {
    assert_eq!(
        DoseState::restore_json("not json"),
        Err(SnapshotError::InvalidEncoding)
    );
}

#[test]
fn unsupported_snapshot_version_is_distinct() -> Result<(), Box<dyn std::error::Error>> {
    let mut value = snapshot_value(&initialized_state()?)?;
    value["version"] = json!(4);

    assert_eq!(
        restore_value(&value),
        Err(SnapshotError::UnsupportedVersion(4))
    );
    Ok(())
}

#[test]
fn version_one_snapshot_restores_with_no_early_window() -> Result<(), Box<dyn std::error::Error>> {
    let mut value = snapshot_value(&initialized_state()?)?;
    value["version"] = json!(1);
    value["state"]
        .as_object_mut()
        .ok_or_else(|| std::io::Error::other("snapshot state is not an object"))?
        .remove("early_window_seconds");
    value["state"]
        .as_object_mut()
        .ok_or_else(|| std::io::Error::other("snapshot state is not an object"))?
        .remove("outside_window_override");

    let restored = restore_value(&value)?;

    assert_eq!(
        restored.schedule().early_window(),
        TimeSpan::from_seconds(0)
    );
    assert_eq!(
        restored.schedule().available_from(),
        restored.schedule().scheduled_at()
    );
    Ok(())
}

#[test]
fn version_two_snapshot_restores_without_override_provenance()
-> Result<(), Box<dyn std::error::Error>> {
    let recorded = initialized_state()?
        .transition(Event::RecordDose {
            now: Timestamp::from_unix_seconds(105),
            intake_at: Some(Timestamp::from_unix_seconds(100)),
            authorization: ForegroundAuthorization::new(true, true),
        })
        .into_parts()
        .0;
    let mut value = snapshot_value(&recorded)?;
    value["version"] = json!(2);
    value["state"]
        .as_object_mut()
        .ok_or_else(|| std::io::Error::other("snapshot state is not an object"))?
        .remove("outside_window_override");

    let restored = restore_value(&value)?;
    assert_eq!(restored, recorded);
    assert_eq!(
        restored.intake().map(IntakeRecord::outside_window_override),
        Some(false)
    );
    Ok(())
}

#[test]
fn snapshot_override_flag_must_match_a_pre_window_intake() -> Result<(), Box<dyn std::error::Error>>
{
    let state = initialized_state()?;
    let mut flag_without_intake = snapshot_value(&state)?;
    flag_without_intake["state"]["outside_window_override"] = json!(true);
    let mut pre_window_without_flag = snapshot_value(&state)?;
    pre_window_without_flag["state"]["intake_at"] = json!(99);
    pre_window_without_flag["state"]["recorded_at"] = json!(105);
    let mut flag_for_normal_intake = snapshot_value(&state)?;
    flag_for_normal_intake["state"]["intake_at"] = json!(100);
    flag_for_normal_intake["state"]["recorded_at"] = json!(105);
    flag_for_normal_intake["state"]["outside_window_override"] = json!(true);
    let mut override_with_late_record = snapshot_value(&state)?;
    override_with_late_record["state"]["intake_at"] = json!(99);
    override_with_late_record["state"]["recorded_at"] = json!(121);
    override_with_late_record["state"]["outside_window_override"] = json!(true);

    for value in [
        &flag_without_intake,
        &pre_window_without_flag,
        &flag_for_normal_intake,
        &override_with_late_record,
    ] {
        assert_eq!(restore_value(value), Err(SnapshotError::InvalidState));
    }
    Ok(())
}

#[test]
fn unknown_snapshot_fields_are_rejected() -> Result<(), Box<dyn std::error::Error>> {
    let mut value = snapshot_value(&initialized_state()?)?;
    value["state"]["unexpected"] = json!(true);

    assert_eq!(restore_value(&value), Err(SnapshotError::InvalidEncoding));
    Ok(())
}

#[test]
fn overflowing_schedule_in_snapshot_is_rejected() -> Result<(), Box<dyn std::error::Error>> {
    let mut value = snapshot_value(&initialized_state()?)?;
    value["state"]["scheduled_at"] = json!(i64::MAX);

    assert_eq!(restore_value(&value), Err(SnapshotError::InvalidState));
    Ok(())
}

#[test]
fn intake_fields_must_both_be_present_or_absent() -> Result<(), Box<dyn std::error::Error>> {
    let state = initialized_state()?;
    let mut intake_only = snapshot_value(&state)?;
    intake_only["state"]["intake_at"] = json!(105);
    let mut recorded_only = snapshot_value(&state)?;
    recorded_only["state"]["recorded_at"] = json!(105);

    assert_eq!(
        restore_value(&intake_only),
        Err(SnapshotError::InvalidState)
    );
    assert_eq!(
        restore_value(&recorded_only),
        Err(SnapshotError::InvalidState)
    );
    Ok(())
}

#[test]
fn intake_snapshot_enforces_schedule_action_and_deadline_bounds()
-> Result<(), Box<dyn std::error::Error>> {
    let state = initialized_state()?;
    let mut before_schedule = snapshot_value(&state)?;
    before_schedule["state"]["intake_at"] = json!(99);
    before_schedule["state"]["recorded_at"] = json!(100);
    let mut after_action = snapshot_value(&state)?;
    after_action["state"]["intake_at"] = json!(106);
    after_action["state"]["recorded_at"] = json!(105);
    let mut after_deadline = snapshot_value(&state)?;
    after_deadline["state"]["intake_at"] = json!(120);
    after_deadline["state"]["recorded_at"] = json!(121);

    for value in [&before_schedule, &after_action, &after_deadline] {
        assert_eq!(restore_value(value), Err(SnapshotError::InvalidState));
    }
    Ok(())
}

#[test]
fn intake_and_expiration_cannot_both_be_present() -> Result<(), Box<dyn std::error::Error>> {
    let state = initialized_state()?;
    let mut value = snapshot_value(&state)?;
    value["state"]["intake_at"] = json!(105);
    value["state"]["recorded_at"] = json!(106);
    value["state"]["expired_at"] = json!(121);

    assert_eq!(restore_value(&value), Err(SnapshotError::InvalidState));
    Ok(())
}

#[test]
fn expiration_cannot_precede_the_first_instant_after_deadline()
-> Result<(), Box<dyn std::error::Error>> {
    let mut value = snapshot_value(&initialized_state()?)?;
    value["state"]["expired_at"] = json!(120);

    assert_eq!(restore_value(&value), Err(SnapshotError::InvalidState));
    Ok(())
}

#[test]
fn snooze_snapshot_requires_active_pending_state_within_deadline()
-> Result<(), Box<dyn std::error::Error>> {
    let state = initialized_state()?;
    let mut inactive = snapshot_value(&state)?;
    inactive["state"]["snoozed_until"] = json!(110);
    let mut recorded = snapshot_value(&state)?;
    recorded["state"]["activity_accepted_at"] = json!(101);
    recorded["state"]["snoozed_until"] = json!(110);
    recorded["state"]["intake_at"] = json!(105);
    recorded["state"]["recorded_at"] = json!(106);
    let mut expired = snapshot_value(&state)?;
    expired["state"]["activity_accepted_at"] = json!(101);
    expired["state"]["snoozed_until"] = json!(110);
    expired["state"]["expired_at"] = json!(121);
    let mut beyond_deadline = snapshot_value(&state)?;
    beyond_deadline["state"]["activity_accepted_at"] = json!(101);
    beyond_deadline["state"]["snoozed_until"] = json!(121);

    for value in [&inactive, &recorded, &expired, &beyond_deadline] {
        assert_eq!(restore_value(value), Err(SnapshotError::InvalidState));
    }
    Ok(())
}

#[test]
fn terminal_snapshot_cannot_claim_visible_presentation() -> Result<(), Box<dyn std::error::Error>> {
    let state = initialized_state()?;
    let mut recorded_quiet = snapshot_value(&state)?;
    recorded_quiet["state"]["intake_at"] = json!(105);
    recorded_quiet["state"]["recorded_at"] = json!(106);
    recorded_quiet["state"]["quiet_visible"] = json!(true);
    let mut expired_intrusive = snapshot_value(&state)?;
    expired_intrusive["state"]["expired_at"] = json!(121);
    expired_intrusive["state"]["intrusive_visible"] = json!(true);

    assert_eq!(
        restore_value(&recorded_quiet),
        Err(SnapshotError::InvalidState)
    );
    assert_eq!(
        restore_value(&expired_intrusive),
        Err(SnapshotError::InvalidState)
    );
    Ok(())
}

#[test]
fn intrusive_snapshot_requires_active_unsnoozed_state() -> Result<(), Box<dyn std::error::Error>> {
    let state = initialized_state()?;
    let mut inactive = snapshot_value(&state)?;
    inactive["state"]["intrusive_visible"] = json!(true);
    let mut snoozed = snapshot_value(&state)?;
    snoozed["state"]["activity_accepted_at"] = json!(101);
    snoozed["state"]["snoozed_until"] = json!(110);
    snoozed["state"]["intrusive_visible"] = json!(true);

    assert_eq!(restore_value(&inactive), Err(SnapshotError::InvalidState));
    assert_eq!(restore_value(&snoozed), Err(SnapshotError::InvalidState));
    Ok(())
}

#[test]
fn snapshot_errors_have_stable_messages() {
    assert_eq!(
        SnapshotError::InvalidEncoding.to_string(),
        "dose snapshot encoding is invalid"
    );
    assert_eq!(
        SnapshotError::UnsupportedVersion(7).to_string(),
        "dose snapshot version 7 is unsupported"
    );
    assert_eq!(
        SnapshotError::InvalidState.to_string(),
        "dose snapshot state is invalid"
    );
}
