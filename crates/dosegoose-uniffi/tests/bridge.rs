use dosegoose_uniffi::{
    CoreBridgeError, EffectKind, GuidanceKind, InitializeDoseInput, RecordingAvailabilityKind,
    RejectionCode, ReminderKindInput, accept_dose_activity, dismiss_dose_reminder, initialize_dose,
    observe_dose, project_dose, record_dose, record_dose_outside_window, return_dose_to_inactive,
    snooze_dose,
};

const OCCURRENCE_ID: &str = "dbb6cead-85da-5a69-9c5f-bd59559bb39e";
const MEDICATION_ID: &str = "42aa830d-371f-42d0-9305-b8ddfe902731";
const DEVICE_ID: &str = "174f334a-e284-4468-b940-27432a80949a";

fn input(scheduled_at: i64, late_window_seconds: i64) -> InitializeDoseInput {
    InitializeDoseInput {
        occurrence_id: OCCURRENCE_ID.to_owned(),
        medication_id: MEDICATION_ID.to_owned(),
        device_id: DEVICE_ID.to_owned(),
        scheduled_at_unix_seconds: scheduled_at,
        early_window_seconds: 0,
        late_window_seconds,
    }
}

#[test]
fn initialize_projects_upcoming_and_preserves_full_identifiers()
-> Result<(), Box<dyn std::error::Error>> {
    let result = initialize_dose(input(100, 20), 90)?;

    assert!(result.accepted);
    assert_eq!(result.rejection, None);
    assert_eq!(result.projection.guidance, GuidanceKind::Upcoming);
    assert_eq!(
        result.projection.recording_availability,
        RecordingAvailabilityKind::NotYetDue
    );
    assert_eq!(result.projection.due_in_seconds, Some(10));
    assert_eq!(result.effects.len(), 1);
    assert_eq!(result.effects[0].kind, EffectKind::ScheduleEvaluation);
    assert_eq!(result.effects[0].occurrence_id, OCCURRENCE_ID);
    assert_eq!(result.effects[0].device_id, DEVICE_ID);
    assert_eq!(result.effects[0].at_unix_seconds, Some(100));
    Ok(())
}

#[test]
fn initialization_rejects_each_invalid_identifier() {
    let mut occurrence = input(100, 20);
    occurrence.occurrence_id = "invalid".to_owned();
    let mut medication = input(100, 20);
    medication.medication_id = "invalid".to_owned();
    let mut device = input(100, 20);
    device.device_id = "invalid".to_owned();

    for (field, value) in [
        ("occurrence", occurrence),
        ("medication", medication),
        ("device", device),
    ] {
        assert_eq!(
            initialize_dose(value, 90),
            Err(CoreBridgeError::InvalidIdentifier),
            "field {field}",
        );
    }
}

#[test]
fn initialization_rejects_negative_window_and_timestamp_overflow() {
    let mut negative_early = input(100, 20);
    negative_early.early_window_seconds = -1;
    assert_eq!(
        initialize_dose(negative_early, 100),
        Err(CoreBridgeError::InvalidEarlyWindow)
    );
    assert_eq!(
        initialize_dose(input(100, -1), 100),
        Err(CoreBridgeError::InvalidLateWindow)
    );
    assert_eq!(
        initialize_dose(input(i64::MAX, 1), i64::MAX),
        Err(CoreBridgeError::InvalidConfiguration)
    );
}

#[test]
fn early_window_projects_actionable_guidance_and_both_boundaries()
-> Result<(), Box<dyn std::error::Error>> {
    let mut early_input = input(100, 20);
    early_input.early_window_seconds = 10;

    let result = initialize_dose(early_input, 90)?;

    assert_eq!(result.projection.guidance, GuidanceKind::EarlyAvailable);
    assert_eq!(
        result.projection.recording_availability,
        RecordingAvailabilityKind::Available,
    );
    assert_eq!(result.projection.due_in_seconds, Some(10));
    assert_eq!(
        result
            .effects
            .iter()
            .filter_map(|effect| effect.at_unix_seconds)
            .collect::<Vec<_>>(),
        [90, 100, 121],
    );
    Ok(())
}

#[test]
fn observe_projects_all_unrecorded_guidance_boundaries() -> Result<(), Box<dyn std::error::Error>> {
    let initial = initialize_dose(input(100, 20), 90)?;
    let cases = [
        (100, GuidanceKind::Due, None, None, None),
        (101, GuidanceKind::Overdue, None, Some(1), Some(19)),
        (120, GuidanceKind::Overdue, None, Some(20), Some(0)),
        (121, GuidanceKind::LateWindowElapsed, None, Some(21), None),
    ];

    for (now, guidance, due_in, late_by, remaining) in cases {
        let result = observe_dose(initial.snapshot_json.clone(), now)?;
        assert_eq!(result.projection.guidance, guidance, "now={now}");
        assert_eq!(result.projection.due_in_seconds, due_in, "now={now}");
        assert_eq!(result.projection.late_by_seconds, late_by, "now={now}");
        assert_eq!(result.projection.remaining_seconds, remaining, "now={now}");
    }
    Ok(())
}

#[test]
fn due_observation_returns_quiet_and_expiration_effects() -> Result<(), Box<dyn std::error::Error>>
{
    let initial = initialize_dose(input(100, 20), 90)?;

    let due = observe_dose(initial.snapshot_json, 100)?;

    assert_eq!(
        due.effects
            .iter()
            .map(|effect| effect.kind)
            .collect::<Vec<_>>(),
        [EffectKind::PresentQuiet, EffectKind::ScheduleEvaluation]
    );
    assert_eq!(due.effects[1].at_unix_seconds, Some(121));
    Ok(())
}

#[test]
fn activity_snooze_and_resume_round_trip_intrusive_presentation()
-> Result<(), Box<dyn std::error::Error>> {
    let due = initialize_dose(input(100, 20), 100)?;
    let active = accept_dose_activity(due.snapshot_json, 101)?;
    assert_eq!(
        active.projection.activity_accepted_at_unix_seconds,
        Some(101)
    );
    assert!(active.projection.quiet_visible);
    assert!(active.projection.intrusive_visible);
    assert_eq!(active.effects[0].kind, EffectKind::PresentIntrusively);

    let snoozed = snooze_dose(active.snapshot_json, 102, 110)?;
    assert!(snoozed.projection.quiet_visible);
    assert!(!snoozed.projection.intrusive_visible);
    assert_eq!(snoozed.projection.snoozed_until_unix_seconds, Some(110));
    assert_eq!(
        snoozed
            .effects
            .iter()
            .map(|effect| effect.kind)
            .collect::<Vec<_>>(),
        [EffectKind::CancelIntrusive, EffectKind::ScheduleEvaluation]
    );

    let resumed = observe_dose(snoozed.snapshot_json, 110)?;
    assert!(resumed.projection.intrusive_visible);
    assert_eq!(resumed.effects[0].kind, EffectKind::PresentIntrusively);
    Ok(())
}

#[test]
fn dismiss_and_authorized_reset_never_record_intake() -> Result<(), Box<dyn std::error::Error>> {
    let due = initialize_dose(input(100, 20), 100)?;
    let active = accept_dose_activity(due.snapshot_json, 101)?;

    let dismissed = dismiss_dose_reminder(active.snapshot_json, 102, ReminderKindInput::Intrusive)?;
    assert!(dismissed.accepted);
    assert!(!dismissed.projection.intrusive_visible);
    assert_eq!(dismissed.projection.intake_at_unix_seconds, None);

    let reset = return_dose_to_inactive(dismissed.snapshot_json, 103, true, true)?;
    assert!(reset.accepted);
    assert!(reset.projection.quiet_visible);
    assert!(!reset.projection.intrusive_visible);
    assert_eq!(reset.projection.intake_at_unix_seconds, None);
    assert_eq!(reset.projection.activity_accepted_at_unix_seconds, None);
    Ok(())
}

#[test]
fn take_now_records_explicit_action_time_and_cancels_quiet()
-> Result<(), Box<dyn std::error::Error>> {
    let due = initialize_dose(input(100, 20), 100)?;

    let recorded = record_dose(due.snapshot_json, 105, None, true, true)?;

    assert!(recorded.accepted);
    assert_eq!(recorded.projection.guidance, GuidanceKind::Recorded);
    assert_eq!(recorded.projection.intake_at_unix_seconds, Some(105));
    assert_eq!(
        recorded.projection.recording_availability,
        RecordingAvailabilityKind::AlreadyRecorded
    );
    assert_eq!(
        recorded
            .effects
            .iter()
            .map(|effect| effect.kind)
            .collect::<Vec<_>>(),
        [EffectKind::CancelQuiet]
    );
    Ok(())
}

#[test]
fn backdated_intake_accepts_both_inclusive_bounds() -> Result<(), Box<dyn std::error::Error>> {
    let due = initialize_dose(input(100, 20), 100)?;

    for intake_at in [100, 110] {
        let recorded = record_dose(due.snapshot_json.clone(), 110, Some(intake_at), true, true)?;
        assert!(recorded.accepted);
        assert_eq!(recorded.projection.intake_at_unix_seconds, Some(intake_at));
    }
    Ok(())
}

#[test]
fn explicit_override_records_pre_window_intake_and_survives_snapshot_restore()
-> Result<(), Box<dyn std::error::Error>> {
    let mut early_input = input(100, 20);
    early_input.early_window_seconds = 10;
    let due = initialize_dose(early_input, 100)?;

    let normal = record_dose(due.snapshot_json.clone(), 105, Some(89), true, true)?;
    assert_eq!(
        normal.rejection,
        Some(RejectionCode::IntakeBeforeAvailableWindow)
    );

    let overridden = record_dose_outside_window(due.snapshot_json, 105, 89, true, true)?;
    assert!(overridden.accepted);
    assert_eq!(overridden.projection.guidance, GuidanceKind::Recorded);
    assert_eq!(overridden.projection.intake_at_unix_seconds, Some(89));
    assert_eq!(
        project_dose(overridden.snapshot_json.clone(), 106)?.intake_at_unix_seconds,
        Some(89)
    );
    let duplicate = record_dose_outside_window(overridden.snapshot_json, 106, 88, true, true)?;
    assert_eq!(duplicate.rejection, Some(RejectionCode::AlreadyRecorded));
    Ok(())
}

#[test]
fn explicit_override_keeps_authorization_action_window_and_future_guards()
-> Result<(), Box<dyn std::error::Error>> {
    let due = initialize_dose(input(100, 20), 100)?;
    let cases = [
        (
            105,
            99,
            false,
            true,
            RejectionCode::ForegroundAuthorizationRequired,
        ),
        (
            105,
            99,
            true,
            false,
            RejectionCode::ForegroundAuthorizationRequired,
        ),
        (121, 99, true, true, RejectionCode::LateWindowElapsed),
        (105, 106, true, true, RejectionCode::IntakeTimeInFuture),
    ];
    for (now, intake_at, foreground, unlocked, expected) in cases {
        let result = record_dose_outside_window(
            due.snapshot_json.clone(),
            now,
            intake_at,
            foreground,
            unlocked,
        )?;
        assert!(!result.accepted);
        assert_eq!(result.rejection, Some(expected));
        assert_eq!(result.projection.intake_at_unix_seconds, None);
    }
    Ok(())
}

#[test]
fn record_rejections_are_typed_and_keep_replacement_snapshots()
-> Result<(), Box<dyn std::error::Error>> {
    let upcoming = initialize_dose(input(100, 20), 90)?;
    let cases = [
        (90, None, true, true, RejectionCode::DoseNotDue),
        (
            105,
            None,
            false,
            true,
            RejectionCode::ForegroundAuthorizationRequired,
        ),
        (
            105,
            None,
            true,
            false,
            RejectionCode::ForegroundAuthorizationRequired,
        ),
        (
            105,
            Some(99),
            true,
            true,
            RejectionCode::IntakeBeforeAvailableWindow,
        ),
        (
            105,
            Some(106),
            true,
            true,
            RejectionCode::IntakeTimeInFuture,
        ),
        (121, None, true, true, RejectionCode::LateWindowElapsed),
    ];

    for (now, intake_at, foreground, unlocked, expected) in cases {
        let result = record_dose(
            upcoming.snapshot_json.clone(),
            now,
            intake_at,
            foreground,
            unlocked,
        )?;
        assert!(!result.accepted);
        assert_eq!(result.rejection, Some(expected));
        assert!(!result.snapshot_json.is_empty());
    }
    Ok(())
}

#[test]
fn repeated_record_is_rejected_as_already_recorded() -> Result<(), Box<dyn std::error::Error>> {
    let due = initialize_dose(input(100, 20), 100)?;
    let recorded = record_dose(due.snapshot_json, 105, None, true, true)?;

    let repeated = record_dose(recorded.snapshot_json, 106, None, true, true)?;

    assert_eq!(repeated.rejection, Some(RejectionCode::AlreadyRecorded));
    assert_eq!(repeated.projection.guidance, GuidanceKind::Recorded);
    assert_eq!(repeated.projection.intake_at_unix_seconds, Some(105));
    Ok(())
}

#[test]
fn stale_observation_is_rejected_without_replacing_projection()
-> Result<(), Box<dyn std::error::Error>> {
    let observed = initialize_dose(input(100, 20), 105)?;

    let stale = observe_dose(observed.snapshot_json, 104)?;

    assert_eq!(stale.rejection, Some(RejectionCode::StaleEvent));
    assert_eq!(stale.projection.guidance, GuidanceKind::Overdue);
    assert_eq!(stale.projection.late_by_seconds, Some(4));
    Ok(())
}

#[test]
fn project_is_read_only_and_invalid_snapshot_is_reported() -> Result<(), Box<dyn std::error::Error>>
{
    let initialized = initialize_dose(input(100, 20), 90)?;

    let projection = project_dose(initialized.snapshot_json, 105)?;

    assert_eq!(projection.guidance, GuidanceKind::Overdue);
    assert_eq!(
        project_dose("{}".to_owned(), 105),
        Err(CoreBridgeError::InvalidSnapshot)
    );
    assert_eq!(
        observe_dose("{}".to_owned(), 105),
        Err(CoreBridgeError::InvalidSnapshot)
    );
    Ok(())
}

#[test]
fn bridge_errors_have_stable_messages() {
    assert_eq!(
        CoreBridgeError::InvalidIdentifier.to_string(),
        "identifier must be a UUID"
    );
    assert_eq!(
        CoreBridgeError::InvalidLateWindow.to_string(),
        "late window must be non-negative"
    );
    assert_eq!(
        CoreBridgeError::InvalidConfiguration.to_string(),
        "dose configuration is invalid"
    );
    assert_eq!(
        CoreBridgeError::InvalidSnapshot.to_string(),
        "dose snapshot cannot be restored"
    );
}
