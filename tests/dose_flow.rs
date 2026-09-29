use dosegoose::{
    ActivityState, DeviceId, DoseGuidance, DoseId, DoseState, Effect, Event, EventOutcome,
    ForegroundAuthorization, MedicationId, RecordingAvailability, Rejection, ReminderKind,
    ScheduledDose, TimeSpan, Timestamp,
};

const DOSE_ID: DoseId = DoseId::new(23);
const MEDICATION_ID: MedicationId = MedicationId::new(7);
const DEVICE_ID: DeviceId = DeviceId::new(11);
const SCHEDULED: Timestamp = Timestamp::from_unix_seconds(1_000);
const DEADLINE: Timestamp = Timestamp::from_unix_seconds(1_600);
const AUTHORIZED: ForegroundAuthorization = ForegroundAuthorization::new(true, true);
const LOCKED: ForegroundAuthorization = ForegroundAuthorization::new(true, false);
const BACKGROUND: ForegroundAuthorization = ForegroundAuthorization::new(false, true);

const fn timestamp(seconds: i64) -> Timestamp {
    Timestamp::from_unix_seconds(seconds)
}

fn scheduled_dose() -> Result<ScheduledDose, dosegoose::ConfigurationError> {
    ScheduledDose::new(
        DOSE_ID,
        MEDICATION_ID,
        SCHEDULED,
        TimeSpan::from_seconds(600),
    )
}

fn initial_state() -> Result<DoseState, dosegoose::ConfigurationError> {
    scheduled_dose().map(|schedule| DoseState::initialize(schedule, DEVICE_ID).into_parts().0)
}

#[test]
fn initialization_requests_device_scoped_due_evaluation() -> Result<(), Box<dyn std::error::Error>>
{
    let initialization = DoseState::initialize(scheduled_dose()?, DEVICE_ID);

    assert_eq!(initialization.state().device_id(), DEVICE_ID);
    assert_eq!(
        initialization.effects(),
        &[Effect::ScheduleEvaluation {
            device_id: DEVICE_ID,
            dose_id: DOSE_ID,
            at: SCHEDULED,
        }]
    );
    Ok(())
}

fn apply(state: &mut DoseState, event: Event) -> Result<Vec<Effect>, Rejection> {
    let transition = state.transition(event);
    let (new_state, effects, outcome) = transition.into_parts();
    *state = new_state;
    match outcome {
        EventOutcome::Accepted => Ok(effects),
        EventOutcome::Rejected(rejection) => Err(rejection),
    }
}

#[test]
fn due_time_starts_quiet_presentation_only_once() -> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;

    assert!(
        apply(
            &mut state,
            Event::ObserveTime {
                now: timestamp(999)
            }
        )?
        .is_empty()
    );
    assert_eq!(
        apply(&mut state, Event::ObserveTime { now: SCHEDULED })?,
        vec![
            Effect::PresentQuiet {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
            },
            Effect::ScheduleEvaluation {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
                at: timestamp(1_601),
            },
        ]
    );
    assert!(state.presentation().quiet_visible());
    assert!(!state.presentation().intrusive_visible());
    assert_eq!(state.guidance(SCHEDULED), DoseGuidance::Due);
    assert!(apply(&mut state, Event::ObserveTime { now: SCHEDULED })?.is_empty());
    Ok(())
}

#[test]
fn intrusive_presentation_requires_accepted_activity() -> Result<(), Box<dyn std::error::Error>> {
    let mut inactive = initial_state()?;
    apply(&mut inactive, Event::ObserveTime { now: SCHEDULED })?;
    assert!(
        apply(
            &mut inactive,
            Event::ObserveTime {
                now: timestamp(1_001)
            }
        )?
        .is_empty()
    );

    assert_eq!(
        apply(
            &mut inactive,
            Event::AcceptActivity {
                now: timestamp(1_002)
            }
        )?,
        vec![Effect::PresentIntrusively {
            device_id: DEVICE_ID,
            dose_id: DOSE_ID,
        }]
    );

    let mut active_before_due = initial_state()?;
    assert!(
        apply(
            &mut active_before_due,
            Event::AcceptActivity {
                now: timestamp(900)
            }
        )?
        .is_empty()
    );
    assert_eq!(
        apply(
            &mut active_before_due,
            Event::ObserveTime { now: SCHEDULED }
        )?,
        vec![
            Effect::PresentQuiet {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
            },
            Effect::ScheduleEvaluation {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
                at: timestamp(1_601),
            },
            Effect::PresentIntrusively {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
            },
        ]
    );
    Ok(())
}

#[test]
fn snooze_preserves_activity_and_intrusive_presentation_resumes()
-> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(&mut state, Event::ObserveTime { now: SCHEDULED })?;
    apply(
        &mut state,
        Event::AcceptActivity {
            now: timestamp(1_010),
        },
    )?;

    assert_eq!(
        apply(
            &mut state,
            Event::Snooze {
                now: timestamp(1_020),
                until: timestamp(1_320),
            }
        )?,
        vec![
            Effect::CancelPresentation {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
                kind: ReminderKind::Intrusive,
            },
            Effect::ScheduleEvaluation {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
                at: timestamp(1_320),
            },
        ]
    );
    assert_eq!(
        state.activity(),
        ActivityState::Active {
            accepted_at: timestamp(1_010)
        }
    );
    assert_eq!(state.snoozed_until(), Some(timestamp(1_320)));
    assert!(
        apply(
            &mut state,
            Event::ObserveTime {
                now: timestamp(1_319)
            }
        )?
        .is_empty()
    );
    assert_eq!(
        apply(
            &mut state,
            Event::ObserveTime {
                now: timestamp(1_320)
            }
        )?,
        vec![Effect::PresentIntrusively {
            device_id: DEVICE_ID,
            dose_id: DOSE_ID,
        }]
    );
    assert!(matches!(state.activity(), ActivityState::Active { .. }));
    Ok(())
}

#[test]
fn only_an_authorized_foreground_action_returns_to_inactivity()
-> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(&mut state, Event::ObserveTime { now: SCHEDULED })?;
    apply(
        &mut state,
        Event::AcceptActivity {
            now: timestamp(1_010),
        },
    )?;
    let before_rejection = state.clone();

    assert_eq!(
        state
            .transition(Event::ReturnToInactive {
                now: timestamp(1_020),
                authorization: LOCKED,
            })
            .outcome(),
        EventOutcome::Rejected(Rejection::ForegroundAuthorizationRequired)
    );
    assert_eq!(
        state
            .transition(Event::ReturnToInactive {
                now: timestamp(1_020),
                authorization: BACKGROUND,
            })
            .outcome(),
        EventOutcome::Rejected(Rejection::ForegroundAuthorizationRequired)
    );
    assert_eq!(state, before_rejection);
    assert_eq!(
        apply(
            &mut state,
            Event::ReturnToInactive {
                now: timestamp(1_020),
                authorization: AUTHORIZED,
            }
        )?,
        vec![Effect::CancelPresentation {
            device_id: DEVICE_ID,
            dose_id: DOSE_ID,
            kind: ReminderKind::Intrusive,
        }]
    );
    assert_eq!(state.activity(), ActivityState::Inactive);
    assert!(state.presentation().quiet_visible());
    assert!(
        apply(
            &mut state,
            Event::ObserveTime {
                now: timestamp(1_030)
            }
        )?
        .is_empty()
    );
    Ok(())
}

#[test]
fn dismissing_reminders_never_records_intake() -> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(&mut state, Event::ObserveTime { now: SCHEDULED })?;
    apply(
        &mut state,
        Event::AcceptActivity {
            now: timestamp(1_010),
        },
    )?;

    assert!(
        apply(
            &mut state,
            Event::DismissReminder {
                now: timestamp(1_020),
                kind: ReminderKind::Quiet,
            }
        )?
        .is_empty()
    );
    assert!(
        apply(
            &mut state,
            Event::DismissReminder {
                now: timestamp(1_020),
                kind: ReminderKind::Intrusive,
            }
        )?
        .is_empty()
    );
    assert_eq!(state.intake(), None);
    assert_eq!(
        apply(
            &mut state,
            Event::ObserveTime {
                now: timestamp(1_021)
            }
        )?,
        vec![
            Effect::PresentQuiet {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
            },
            Effect::PresentIntrusively {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
            },
        ]
    );
    Ok(())
}

#[test]
fn recording_requires_authorization_and_defaults_intake_to_now()
-> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(&mut state, Event::ObserveTime { now: SCHEDULED })?;
    apply(
        &mut state,
        Event::AcceptActivity {
            now: timestamp(1_010),
        },
    )?;
    let before_rejection = state.clone();

    assert_eq!(
        state
            .transition(Event::RecordDose {
                now: timestamp(1_050),
                intake_at: None,
                authorization: LOCKED,
            })
            .outcome(),
        EventOutcome::Rejected(Rejection::ForegroundAuthorizationRequired)
    );
    assert_eq!(state, before_rejection);
    assert_eq!(
        apply(
            &mut state,
            Event::RecordDose {
                now: timestamp(1_050),
                intake_at: None,
                authorization: AUTHORIZED,
            }
        )?,
        vec![
            Effect::CancelPresentation {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
                kind: ReminderKind::Quiet,
            },
            Effect::CancelPresentation {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
                kind: ReminderKind::Intrusive,
            },
        ]
    );
    let intake = state.intake().ok_or("intake should have been recorded")?;
    assert_eq!(intake.intake_at(), timestamp(1_050));
    assert_eq!(intake.recorded_at(), timestamp(1_050));
    assert_eq!(
        state.recording_availability(timestamp(1_050)),
        RecordingAvailability::AlreadyRecorded
    );
    assert_eq!(
        state
            .transition(Event::RecordDose {
                now: timestamp(1_051),
                intake_at: None,
                authorization: AUTHORIZED,
            })
            .outcome(),
        EventOutcome::Rejected(Rejection::AlreadyRecorded)
    );
    Ok(())
}

#[test]
fn earlier_intake_must_be_between_schedule_and_now() -> Result<(), Box<dyn std::error::Error>> {
    let state = initial_state()?;
    let event = |intake_at| Event::RecordDose {
        now: timestamp(1_300),
        intake_at: Some(intake_at),
        authorization: AUTHORIZED,
    };

    assert_eq!(
        state.transition(event(timestamp(1_301))).outcome(),
        EventOutcome::Rejected(Rejection::IntakeTimeInFuture)
    );
    assert_eq!(
        state.transition(event(timestamp(999))).outcome(),
        EventOutcome::Rejected(Rejection::IntakeBeforeAvailableWindow)
    );
    let transition = state.transition(event(timestamp(1_100)));
    assert_eq!(transition.outcome(), EventOutcome::Accepted);
    let intake = transition
        .state()
        .intake()
        .ok_or("earlier intake should have been recorded")?;
    assert_eq!(intake.intake_at(), timestamp(1_100));
    assert_eq!(intake.recorded_at(), timestamp(1_300));
    Ok(())
}

#[test]
fn late_window_controls_guidance_and_recording_at_its_boundaries()
-> Result<(), Box<dyn std::error::Error>> {
    let state = initial_state()?;

    assert_eq!(
        state.guidance(timestamp(900)),
        DoseGuidance::Upcoming {
            due_in: TimeSpan::from_seconds(100)
        }
    );
    assert_eq!(state.guidance(SCHEDULED), DoseGuidance::Due);
    assert_eq!(
        state.guidance(timestamp(1_200)),
        DoseGuidance::Overdue {
            late_by: TimeSpan::from_seconds(200),
            remaining: TimeSpan::from_seconds(400),
        }
    );
    assert_eq!(
        state.guidance(DEADLINE),
        DoseGuidance::Overdue {
            late_by: TimeSpan::from_seconds(600),
            remaining: TimeSpan::from_seconds(0),
        }
    );
    assert_eq!(
        state.guidance(timestamp(1_601)),
        DoseGuidance::LateWindowElapsed {
            late_by: TimeSpan::from_seconds(601)
        }
    );
    assert_eq!(
        state.recording_availability(DEADLINE),
        RecordingAvailability::Available
    );
    assert_eq!(
        state.recording_availability(timestamp(1_601)),
        RecordingAvailability::LateWindowElapsed
    );
    assert_eq!(
        state
            .transition(Event::RecordDose {
                now: DEADLINE,
                intake_at: None,
                authorization: AUTHORIZED,
            })
            .outcome(),
        EventOutcome::Accepted
    );
    assert_eq!(
        state
            .transition(Event::RecordDose {
                now: timestamp(1_601),
                intake_at: Some(DEADLINE),
                authorization: AUTHORIZED,
            })
            .outcome(),
        EventOutcome::Rejected(Rejection::LateWindowElapsed)
    );
    Ok(())
}

#[test]
fn passing_the_late_deadline_cancels_presentations() -> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(&mut state, Event::ObserveTime { now: SCHEDULED })?;
    apply(
        &mut state,
        Event::AcceptActivity {
            now: timestamp(1_010),
        },
    )?;

    assert_eq!(
        apply(
            &mut state,
            Event::ObserveTime {
                now: timestamp(1_601)
            }
        )?,
        vec![
            Effect::CancelPresentation {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
                kind: ReminderKind::Quiet,
            },
            Effect::CancelPresentation {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
                kind: ReminderKind::Intrusive,
            },
        ]
    );
    assert!(!state.presentation().quiet_visible());
    assert!(!state.presentation().intrusive_visible());
    Ok(())
}

#[test]
fn snooze_may_end_at_deadline_and_expiration_is_still_scheduled()
-> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(&mut state, Event::ObserveTime { now: SCHEDULED })?;
    apply(
        &mut state,
        Event::AcceptActivity {
            now: timestamp(1_100),
        },
    )?;
    apply(
        &mut state,
        Event::Snooze {
            now: timestamp(1_200),
            until: DEADLINE,
        },
    )?;

    assert_eq!(
        apply(&mut state, Event::ObserveTime { now: DEADLINE })?,
        vec![Effect::PresentIntrusively {
            device_id: DEVICE_ID,
            dose_id: DOSE_ID,
        }]
    );
    assert_eq!(
        apply(
            &mut state,
            Event::ObserveTime {
                now: timestamp(1_601),
            }
        )?,
        vec![
            Effect::CancelPresentation {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
                kind: ReminderKind::Quiet,
            },
            Effect::CancelPresentation {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
                kind: ReminderKind::Intrusive,
            },
        ]
    );
    Ok(())
}

#[test]
fn expiration_is_terminal_for_stale_device_events() -> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(&mut state, Event::ObserveTime { now: SCHEDULED })?;
    let late_record = state.transition(Event::RecordDose {
        now: timestamp(1_601),
        intake_at: None,
        authorization: AUTHORIZED,
    });
    assert_eq!(
        late_record.outcome(),
        EventOutcome::Rejected(Rejection::LateWindowElapsed)
    );
    assert_eq!(
        late_record.effects(),
        &[Effect::CancelPresentation {
            device_id: DEVICE_ID,
            dose_id: DOSE_ID,
            kind: ReminderKind::Quiet,
        }]
    );
    state = late_record.into_parts().0;
    let expired = state.clone();

    assert_eq!(
        state
            .transition(Event::RecordDose {
                now: DEADLINE,
                intake_at: None,
                authorization: AUTHORIZED,
            })
            .outcome(),
        EventOutcome::Rejected(Rejection::StaleEvent {
            now: DEADLINE,
            last_observed_at: timestamp(1_601),
        })
    );
    assert_eq!(state, expired);
    assert_eq!(
        state.recording_availability(DEADLINE),
        RecordingAvailability::LateWindowElapsed
    );
    Ok(())
}

#[test]
fn dismissal_after_expiration_cannot_block_expiration_evaluation()
-> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(&mut state, Event::ObserveTime { now: SCHEDULED })?;

    assert_eq!(
        apply(
            &mut state,
            Event::DismissReminder {
                now: timestamp(1_700),
                kind: ReminderKind::Quiet,
            }
        )?,
        vec![Effect::CancelPresentation {
            device_id: DEVICE_ID,
            dose_id: DOSE_ID,
            kind: ReminderKind::Quiet,
        }]
    );
    assert_eq!(
        state.recording_availability(timestamp(1_500)),
        RecordingAvailability::LateWindowElapsed
    );
    assert_eq!(
        state
            .transition(Event::ObserveTime {
                now: timestamp(1_601),
            })
            .outcome(),
        EventOutcome::Rejected(Rejection::StaleEvent {
            now: timestamp(1_601),
            last_observed_at: timestamp(1_700),
        })
    );
    Ok(())
}

#[test]
fn zero_length_late_window_expires_one_second_after_due() -> Result<(), Box<dyn std::error::Error>>
{
    let schedule =
        ScheduledDose::new(DOSE_ID, MEDICATION_ID, SCHEDULED, TimeSpan::from_seconds(0))?;
    let mut state = DoseState::initialize(schedule, DEVICE_ID).into_parts().0;

    apply(&mut state, Event::ObserveTime { now: SCHEDULED })?;
    assert_eq!(
        state.recording_availability(SCHEDULED),
        RecordingAvailability::Available
    );
    apply(
        &mut state,
        Event::ObserveTime {
            now: timestamp(1_001),
        },
    )?;
    assert_eq!(
        state.guidance(timestamp(1_001)),
        DoseGuidance::LateWindowElapsed {
            late_by: TimeSpan::from_seconds(1),
        }
    );
    Ok(())
}

#[test]
fn invalid_snooze_actions_are_rejected() -> Result<(), Box<dyn std::error::Error>> {
    let mut inactive = initial_state()?;
    apply(&mut inactive, Event::ObserveTime { now: SCHEDULED })?;
    assert_eq!(
        inactive
            .transition(Event::Snooze {
                now: SCHEDULED,
                until: timestamp(1_100),
            })
            .outcome(),
        EventOutcome::Rejected(Rejection::ActivityNotAccepted)
    );

    let mut active = inactive.clone();
    apply(&mut active, Event::AcceptActivity { now: SCHEDULED })?;
    assert_eq!(
        active
            .transition(Event::Snooze {
                now: SCHEDULED,
                until: SCHEDULED,
            })
            .outcome(),
        EventOutcome::Rejected(Rejection::SnoozeMustEndInFuture)
    );
    assert_eq!(
        active
            .transition(Event::Snooze {
                now: SCHEDULED,
                until: timestamp(1_601),
            })
            .outcome(),
        EventOutcome::Rejected(Rejection::SnoozeBeyondLateWindow)
    );
    Ok(())
}

#[test]
fn overflowing_late_deadline_is_rejected() {
    assert_eq!(
        ScheduledDose::new(
            DOSE_ID,
            MEDICATION_ID,
            timestamp(i64::MAX),
            TimeSpan::from_seconds(1),
        ),
        Err(dosegoose::ConfigurationError::LateDeadlineOverflow)
    );
    assert_eq!(
        ScheduledDose::new(
            DOSE_ID,
            MEDICATION_ID,
            timestamp(i64::MAX),
            TimeSpan::from_seconds(0),
        ),
        Err(dosegoose::ConfigurationError::ExpirationOverflow)
    );
}

#[test]
fn large_unsigned_window_uses_the_full_timestamp_range() -> Result<(), Box<dyn std::error::Error>> {
    let schedule = ScheduledDose::new(
        DOSE_ID,
        MEDICATION_ID,
        timestamp(i64::MIN),
        TimeSpan::from_seconds(u64::MAX - 1),
    )?;

    assert_eq!(schedule.late_deadline(), timestamp(i64::MAX - 1));
    assert_eq!(schedule.expires_at(), timestamp(i64::MAX));
    Ok(())
}
