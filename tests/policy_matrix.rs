use dosegoose::{
    ActivityState, DeviceId, DoseGuidance, DoseId, DoseState, Effect, Event, EventOutcome,
    ForegroundAuthorization, MedicationId, RecordingAvailability, Rejection, ReminderKind,
    ScheduledDose, TimeSpan, Timestamp,
};

const DEVICE_ID: DeviceId = DeviceId::new(11);
const DOSE_ID: DoseId = DoseId::new(23);
const MEDICATION_ID: MedicationId = MedicationId::new(7);
const SCHEDULED: Timestamp = Timestamp::from_unix_seconds(1_000);
const DEADLINE: Timestamp = Timestamp::from_unix_seconds(1_600);
const EXPIRES: Timestamp = Timestamp::from_unix_seconds(1_601);
const AUTHORIZED: ForegroundAuthorization = ForegroundAuthorization::new(true, true);

const fn timestamp(seconds: i64) -> Timestamp {
    Timestamp::from_unix_seconds(seconds)
}

fn initial_state() -> Result<DoseState, dosegoose::ConfigurationError> {
    let schedule = ScheduledDose::new(
        DOSE_ID,
        MEDICATION_ID,
        SCHEDULED,
        TimeSpan::from_seconds(600),
    )?;
    Ok(DoseState::initialize(schedule, DEVICE_ID).into_parts().0)
}

fn apply(state: &mut DoseState, event: Event) -> Result<Vec<Effect>, Rejection> {
    let (next, effects, outcome) = state.transition(event).into_parts();
    *state = next;
    match outcome {
        EventOutcome::Accepted => Ok(effects),
        EventOutcome::Rejected(rejection) => Err(rejection),
    }
}

fn active_recorded_state() -> Result<DoseState, Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(
        &mut state,
        Event::AcceptActivity {
            now: timestamp(1_100),
        },
    )?;
    apply(
        &mut state,
        Event::RecordDose {
            now: timestamp(1_200),
            intake_at: None,
            authorization: AUTHORIZED,
        },
    )?;
    Ok(state)
}

fn active_expired_state() -> Result<DoseState, Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(
        &mut state,
        Event::AcceptActivity {
            now: timestamp(1_100),
        },
    )?;
    apply(&mut state, Event::ObserveTime { now: EXPIRES })?;
    Ok(state)
}

macro_rules! authorization_case {
    ($name:ident, $foreground:expr, $unlocked:expr, $expected:expr) => {
        #[test]
        fn $name() {
            let authorization = ForegroundAuthorization::new(
                std::hint::black_box($foreground),
                std::hint::black_box($unlocked),
            );
            assert_eq!(authorization.permits_sensitive_action(), $expected);
        }
    };
}

authorization_case!(authorization_denies_background_locked, false, false, false);
authorization_case!(authorization_denies_background_unlocked, false, true, false);
authorization_case!(authorization_denies_foreground_locked, true, false, false);
authorization_case!(authorization_allows_foreground_unlocked, true, true, true);

macro_rules! guidance_case {
    ($name:ident, $now:expr, $expected:expr) => {
        #[test]
        fn $name() -> Result<(), Box<dyn std::error::Error>> {
            let state = initial_state()?;
            assert_eq!(state.guidance(timestamp($now)), $expected);
            Ok(())
        }
    };
}

guidance_case!(
    guidance_one_second_before_due,
    999,
    DoseGuidance::Upcoming {
        due_in: TimeSpan::from_seconds(1)
    }
);
guidance_case!(guidance_exactly_at_due, 1_000, DoseGuidance::Due);
guidance_case!(
    guidance_one_second_overdue,
    1_001,
    DoseGuidance::Overdue {
        late_by: TimeSpan::from_seconds(1),
        remaining: TimeSpan::from_seconds(599)
    }
);
guidance_case!(
    guidance_one_second_before_deadline,
    1_599,
    DoseGuidance::Overdue {
        late_by: TimeSpan::from_seconds(599),
        remaining: TimeSpan::from_seconds(1)
    }
);
guidance_case!(
    guidance_exactly_at_deadline,
    1_600,
    DoseGuidance::Overdue {
        late_by: TimeSpan::from_seconds(600),
        remaining: TimeSpan::from_seconds(0)
    }
);
guidance_case!(
    guidance_at_expiration,
    1_601,
    DoseGuidance::LateWindowElapsed {
        late_by: TimeSpan::from_seconds(601)
    }
);

macro_rules! availability_case {
    ($name:ident, $now:expr, $expected:expr) => {
        #[test]
        fn $name() -> Result<(), Box<dyn std::error::Error>> {
            let state = initial_state()?;
            assert_eq!(state.recording_availability(timestamp($now)), $expected);
            Ok(())
        }
    };
}

availability_case!(
    recording_is_unavailable_one_second_before_due,
    999,
    RecordingAvailability::NotYetDue
);
availability_case!(
    recording_is_available_at_due,
    1_000,
    RecordingAvailability::Available
);
availability_case!(
    recording_is_available_one_second_before_deadline,
    1_599,
    RecordingAvailability::Available
);
availability_case!(
    recording_is_available_at_deadline,
    1_600,
    RecordingAvailability::Available
);
availability_case!(
    recording_is_unavailable_at_expiration,
    1_601,
    RecordingAvailability::LateWindowElapsed
);

macro_rules! stale_event_case {
    ($name:ident, $event:expr) => {
        #[test]
        fn $name() -> Result<(), Box<dyn std::error::Error>> {
            let mut state = initial_state()?;
            apply(
                &mut state,
                Event::ObserveTime {
                    now: timestamp(1_100),
                },
            )?;
            let before = state.clone();
            let transition = state.transition($event);

            assert_eq!(
                transition.outcome(),
                EventOutcome::Rejected(Rejection::StaleEvent {
                    now: timestamp(1_099),
                    last_observed_at: timestamp(1_100),
                })
            );
            assert_eq!(transition.state(), &before);
            assert!(transition.effects().is_empty());
            Ok(())
        }
    };
}

stale_event_case!(
    stale_time_observation_is_inert,
    Event::ObserveTime {
        now: timestamp(1_099)
    }
);
stale_event_case!(
    stale_activity_evidence_is_inert,
    Event::AcceptActivity {
        now: timestamp(1_099)
    }
);
stale_event_case!(
    stale_snooze_is_inert,
    Event::Snooze {
        now: timestamp(1_099),
        until: timestamp(1_200)
    }
);
stale_event_case!(
    stale_inactivity_reset_is_inert,
    Event::ReturnToInactive {
        now: timestamp(1_099),
        authorization: AUTHORIZED
    }
);
stale_event_case!(
    stale_dismissal_is_inert,
    Event::DismissReminder {
        now: timestamp(1_099),
        kind: ReminderKind::Quiet
    }
);
stale_event_case!(
    stale_record_action_is_inert,
    Event::RecordDose {
        now: timestamp(1_099),
        intake_at: None,
        authorization: AUTHORIZED
    }
);

macro_rules! snooze_boundary_case {
    ($name:ident, $until:expr, $expected:expr) => {
        #[test]
        fn $name() -> Result<(), Box<dyn std::error::Error>> {
            let mut state = initial_state()?;
            apply(
                &mut state,
                Event::AcceptActivity {
                    now: timestamp(1_100),
                },
            )?;
            let transition = state.transition(Event::Snooze {
                now: timestamp(1_200),
                until: timestamp($until),
            });
            assert_eq!(transition.outcome(), $expected);
            assert_eq!(
                transition.state().last_observed_at(),
                Some(timestamp(1_200))
            );
            if matches!($expected, EventOutcome::Rejected(_)) {
                assert_eq!(transition.state().snoozed_until(), None);
                assert!(transition.effects().is_empty());
            }
            Ok(())
        }
    };
}

snooze_boundary_case!(
    snooze_rejects_an_end_before_now,
    1_199,
    EventOutcome::Rejected(Rejection::SnoozeMustEndInFuture)
);
snooze_boundary_case!(
    snooze_rejects_an_end_equal_to_now,
    1_200,
    EventOutcome::Rejected(Rejection::SnoozeMustEndInFuture)
);
snooze_boundary_case!(
    snooze_accepts_the_next_second,
    1_201,
    EventOutcome::Accepted
);
snooze_boundary_case!(
    snooze_accepts_the_inclusive_deadline,
    1_600,
    EventOutcome::Accepted
);
snooze_boundary_case!(
    snooze_rejects_expiration,
    1_601,
    EventOutcome::Rejected(Rejection::SnoozeBeyondLateWindow)
);

macro_rules! intake_boundary_case {
    ($name:ident, $intake_at:expr, $expected:expr) => {
        #[test]
        fn $name() -> Result<(), Box<dyn std::error::Error>> {
            let state = initial_state()?;
            let transition = state.transition(Event::RecordDose {
                now: timestamp(1_300),
                intake_at: Some(timestamp($intake_at)),
                authorization: AUTHORIZED,
            });
            assert_eq!(transition.outcome(), $expected);
            assert_eq!(
                transition.state().last_observed_at(),
                Some(timestamp(1_300))
            );
            if matches!($expected, EventOutcome::Rejected(_)) {
                assert_eq!(transition.state().intake(), None);
                assert!(transition.effects().is_empty());
            }
            Ok(())
        }
    };
}

intake_boundary_case!(
    intake_rejects_one_second_before_schedule,
    999,
    EventOutcome::Rejected(Rejection::IntakeBeforeAvailableWindow)
);
intake_boundary_case!(
    intake_accepts_the_scheduled_time,
    1_000,
    EventOutcome::Accepted
);
intake_boundary_case!(intake_accepts_now, 1_300, EventOutcome::Accepted);
intake_boundary_case!(
    intake_rejects_one_second_in_the_future,
    1_301,
    EventOutcome::Rejected(Rejection::IntakeTimeInFuture)
);

#[test]
fn record_is_rejected_before_due() -> Result<(), Box<dyn std::error::Error>> {
    let state = initial_state()?;

    let transition = state.transition(Event::RecordDose {
        now: timestamp(999),
        intake_at: None,
        authorization: AUTHORIZED,
    });
    assert_eq!(
        transition.outcome(),
        EventOutcome::Rejected(Rejection::DoseNotDue)
    );
    assert_eq!(transition.state().last_observed_at(), Some(timestamp(999)));
    assert_eq!(transition.state().intake(), None);
    assert!(transition.effects().is_empty());
    Ok(())
}

#[test]
fn snooze_is_rejected_before_due() -> Result<(), Box<dyn std::error::Error>> {
    let state = initial_state()?;

    let transition = state.transition(Event::Snooze {
        now: timestamp(999),
        until: timestamp(1_100),
    });
    assert_eq!(
        transition.outcome(),
        EventOutcome::Rejected(Rejection::DoseNotDue)
    );
    assert_eq!(transition.state().last_observed_at(), Some(timestamp(999)));
    assert_eq!(transition.state().snoozed_until(), None);
    assert!(transition.effects().is_empty());
    Ok(())
}

#[test]
fn rejected_action_advances_only_the_watermark() -> Result<(), Box<dyn std::error::Error>> {
    let state = initial_state()?;
    let transition = state.transition(Event::Snooze {
        now: timestamp(1_100),
        until: timestamp(1_200),
    });

    assert_eq!(
        transition.outcome(),
        EventOutcome::Rejected(Rejection::ActivityNotAccepted)
    );
    assert_eq!(transition.state().activity(), ActivityState::Inactive);
    assert_eq!(transition.state().snoozed_until(), None);
    assert_eq!(
        transition.state().last_observed_at(),
        Some(timestamp(1_100))
    );
    assert_eq!(state.last_observed_at(), None);
    assert!(transition.effects().is_empty());
    Ok(())
}

#[test]
fn unauthorized_record_advances_watermark_without_recording()
-> Result<(), Box<dyn std::error::Error>> {
    let state = initial_state()?;
    let transition = state.transition(Event::RecordDose {
        now: timestamp(1_100),
        intake_at: None,
        authorization: ForegroundAuthorization::new(false, false),
    });

    assert_eq!(
        transition.outcome(),
        EventOutcome::Rejected(Rejection::ForegroundAuthorizationRequired)
    );
    assert_eq!(
        transition.state().last_observed_at(),
        Some(timestamp(1_100))
    );
    assert_eq!(transition.state().intake(), None);
    assert_eq!(transition.state().activity(), ActivityState::Inactive);
    assert!(transition.effects().is_empty());
    Ok(())
}

#[test]
fn authorization_rejection_precedes_availability_but_retains_expiration()
-> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(&mut state, Event::ObserveTime { now: SCHEDULED })?;
    let transition = state.transition(Event::RecordDose {
        now: EXPIRES,
        intake_at: None,
        authorization: ForegroundAuthorization::new(false, false),
    });

    assert_eq!(
        transition.outcome(),
        EventOutcome::Rejected(Rejection::ForegroundAuthorizationRequired)
    );
    assert_eq!(transition.state().last_observed_at(), Some(EXPIRES));
    assert_eq!(transition.state().intake(), None);
    assert_eq!(
        transition.state().recording_availability(DEADLINE),
        RecordingAvailability::LateWindowElapsed
    );
    assert_eq!(
        transition.effects(),
        &[Effect::CancelPresentation {
            device_id: DEVICE_ID,
            dose_id: DOSE_ID,
            kind: ReminderKind::Quiet,
        }]
    );
    Ok(())
}

#[test]
fn authorization_rejection_precedes_already_recorded() -> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(
        &mut state,
        Event::RecordDose {
            now: timestamp(1_100),
            intake_at: None,
            authorization: AUTHORIZED,
        },
    )?;
    let intake = state.intake();
    let transition = state.transition(Event::RecordDose {
        now: timestamp(1_200),
        intake_at: None,
        authorization: ForegroundAuthorization::new(false, false),
    });

    assert_eq!(
        transition.outcome(),
        EventOutcome::Rejected(Rejection::ForegroundAuthorizationRequired)
    );
    assert_eq!(transition.state().intake(), intake);
    assert_eq!(
        transition.state().last_observed_at(),
        Some(timestamp(1_200))
    );
    assert!(transition.effects().is_empty());
    Ok(())
}

#[test]
fn duplicate_activity_evidence_is_idempotent() -> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(
        &mut state,
        Event::AcceptActivity {
            now: timestamp(1_100),
        },
    )?;

    assert!(
        apply(
            &mut state,
            Event::AcceptActivity {
                now: timestamp(1_200),
            }
        )?
        .is_empty()
    );
    assert_eq!(
        state.activity(),
        ActivityState::Active {
            accepted_at: timestamp(1_100),
        }
    );
    assert_eq!(state.last_observed_at(), Some(timestamp(1_200)));
    Ok(())
}

#[test]
fn equal_timestamp_events_are_accepted_in_order() -> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(&mut state, Event::ObserveTime { now: SCHEDULED })?;
    let effects = apply(&mut state, Event::AcceptActivity { now: SCHEDULED })?;

    assert_eq!(
        effects,
        vec![Effect::PresentIntrusively {
            device_id: DEVICE_ID,
            dose_id: DOSE_ID,
        }]
    );
    Ok(())
}

#[test]
fn direct_jump_to_overdue_requests_quiet_and_expiration_evaluation()
-> Result<(), Box<dyn std::error::Error>> {
    let state = initial_state()?;
    let transition = state.transition(Event::ObserveTime {
        now: timestamp(1_300),
    });

    assert_eq!(transition.outcome(), EventOutcome::Accepted);
    assert_eq!(
        transition.effects(),
        &[
            Effect::PresentQuiet {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
            },
            Effect::ScheduleEvaluation {
                device_id: DEVICE_ID,
                dose_id: DOSE_ID,
                at: EXPIRES,
            },
        ]
    );
    Ok(())
}

#[test]
fn direct_jump_to_expiration_is_terminal_without_presenting()
-> Result<(), Box<dyn std::error::Error>> {
    let state = initial_state()?;
    let transition = state.transition(Event::ObserveTime { now: EXPIRES });

    assert_eq!(transition.outcome(), EventOutcome::Accepted);
    assert!(transition.effects().is_empty());
    assert_eq!(
        transition.state().recording_availability(DEADLINE),
        RecordingAvailability::LateWindowElapsed
    );
    assert!(!transition.state().presentation().quiet_visible());
    assert!(!transition.state().presentation().intrusive_visible());
    Ok(())
}

#[test]
fn repeated_expiration_is_idempotent() -> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(&mut state, Event::ObserveTime { now: SCHEDULED })?;
    apply(&mut state, Event::ObserveTime { now: EXPIRES })?;

    assert!(
        apply(
            &mut state,
            Event::ObserveTime {
                now: timestamp(1_700),
            }
        )?
        .is_empty()
    );
    assert_eq!(
        state.recording_availability(DEADLINE),
        RecordingAvailability::LateWindowElapsed
    );
    Ok(())
}

#[test]
fn snooze_after_recording_is_rejected_without_changing_intake()
-> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(
        &mut state,
        Event::RecordDose {
            now: timestamp(1_100),
            intake_at: None,
            authorization: AUTHORIZED,
        },
    )?;
    let intake = state.intake();
    let transition = state.transition(Event::Snooze {
        now: timestamp(1_200),
        until: timestamp(1_300),
    });

    assert_eq!(
        transition.outcome(),
        EventOutcome::Rejected(Rejection::AlreadyRecorded)
    );
    assert_eq!(transition.state().intake(), intake);
    assert_eq!(transition.state().snoozed_until(), None);
    assert!(transition.effects().is_empty());
    Ok(())
}

#[test]
fn snooze_after_expiration_is_rejected_and_keeps_expiration_terminal()
-> Result<(), Box<dyn std::error::Error>> {
    let state = initial_state()?;
    let transition = state.transition(Event::Snooze {
        now: EXPIRES,
        until: timestamp(1_700),
    });

    assert_eq!(
        transition.outcome(),
        EventOutcome::Rejected(Rejection::LateWindowElapsed)
    );
    assert_eq!(
        transition.state().recording_availability(DEADLINE),
        RecordingAvailability::LateWindowElapsed
    );
    assert_eq!(transition.state().snoozed_until(), None);
    assert!(transition.effects().is_empty());
    Ok(())
}

#[test]
fn dismissing_an_invisible_reminder_is_idempotent() -> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;

    assert!(
        apply(
            &mut state,
            Event::DismissReminder {
                now: timestamp(900),
                kind: ReminderKind::Quiet,
            }
        )?
        .is_empty()
    );
    assert!(
        apply(
            &mut state,
            Event::DismissReminder {
                now: timestamp(900),
                kind: ReminderKind::Intrusive,
            }
        )?
        .is_empty()
    );
    assert!(!state.presentation().quiet_visible());
    assert!(!state.presentation().intrusive_visible());
    Ok(())
}

#[test]
fn snooze_after_intrusive_dismissal_schedules_without_duplicate_cancellation()
-> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(
        &mut state,
        Event::AcceptActivity {
            now: timestamp(1_100),
        },
    )?;
    apply(
        &mut state,
        Event::DismissReminder {
            now: timestamp(1_150),
            kind: ReminderKind::Intrusive,
        },
    )?;

    assert_eq!(
        apply(
            &mut state,
            Event::Snooze {
                now: timestamp(1_200),
                until: timestamp(1_300),
            }
        )?,
        vec![Effect::ScheduleEvaluation {
            device_id: DEVICE_ID,
            dose_id: DOSE_ID,
            at: timestamp(1_300),
        }]
    );
    Ok(())
}

#[test]
fn expiration_while_snoozed_clears_snooze_and_cancels_quiet()
-> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
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
        apply(&mut state, Event::ObserveTime { now: EXPIRES })?,
        vec![Effect::CancelPresentation {
            device_id: DEVICE_ID,
            dose_id: DOSE_ID,
            kind: ReminderKind::Quiet,
        }]
    );
    assert_eq!(state.snoozed_until(), None);
    assert!(!state.presentation().quiet_visible());
    assert!(!state.presentation().intrusive_visible());
    Ok(())
}

#[test]
fn authorized_reset_is_idempotent_while_already_inactive() -> Result<(), Box<dyn std::error::Error>>
{
    let mut state = initial_state()?;

    assert!(
        apply(
            &mut state,
            Event::ReturnToInactive {
                now: timestamp(900),
                authorization: AUTHORIZED,
            }
        )?
        .is_empty()
    );
    assert_eq!(state.activity(), ActivityState::Inactive);
    Ok(())
}

#[test]
fn reset_while_snoozed_clears_snooze_and_prevents_restart() -> Result<(), Box<dyn std::error::Error>>
{
    let mut state = initial_state()?;
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
            until: timestamp(1_300),
        },
    )?;
    apply(
        &mut state,
        Event::ReturnToInactive {
            now: timestamp(1_250),
            authorization: AUTHORIZED,
        },
    )?;

    assert_eq!(state.activity(), ActivityState::Inactive);
    assert_eq!(state.snoozed_until(), None);
    assert!(
        apply(
            &mut state,
            Event::ObserveTime {
                now: timestamp(1_300),
            }
        )?
        .is_empty()
    );
    assert!(!state.presentation().intrusive_visible());
    Ok(())
}

#[test]
fn recording_while_snoozed_clears_snooze_and_cancels_quiet_only()
-> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
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
            until: timestamp(1_300),
        },
    )?;

    assert_eq!(
        apply(
            &mut state,
            Event::RecordDose {
                now: timestamp(1_250),
                intake_at: None,
                authorization: AUTHORIZED,
            }
        )?,
        vec![Effect::CancelPresentation {
            device_id: DEVICE_ID,
            dose_id: DOSE_ID,
            kind: ReminderKind::Quiet,
        }]
    );
    assert_eq!(state.snoozed_until(), None);
    assert!(state.intake().is_some());
    Ok(())
}

#[test]
fn recorded_guidance_is_stable_for_all_query_times() -> Result<(), Box<dyn std::error::Error>> {
    let mut state = initial_state()?;
    apply(
        &mut state,
        Event::RecordDose {
            now: timestamp(1_300),
            intake_at: Some(timestamp(1_100)),
            authorization: AUTHORIZED,
        },
    )?;
    let expected = DoseGuidance::Recorded {
        intake_at: timestamp(1_100),
    };

    assert_eq!(state.guidance(timestamp(i64::MIN)), expected);
    assert_eq!(state.guidance(timestamp(1_300)), expected);
    assert_eq!(state.guidance(timestamp(i64::MAX)), expected);
    assert!(
        apply(
            &mut state,
            Event::ObserveTime {
                now: timestamp(1_400),
            }
        )?
        .is_empty()
    );
    Ok(())
}

macro_rules! recorded_passive_event_case {
    ($name:ident, $event:expr) => {
        #[test]
        fn $name() -> Result<(), Box<dyn std::error::Error>> {
            let state = active_recorded_state()?;
            let intake = state.intake();
            let activity = state.activity();
            let transition = state.transition($event);

            assert_eq!(transition.outcome(), EventOutcome::Accepted);
            assert!(transition.effects().is_empty());
            assert_eq!(transition.state().intake(), intake);
            assert_eq!(transition.state().activity(), activity);
            assert_eq!(
                transition.state().last_observed_at(),
                Some(timestamp(1_300))
            );
            Ok(())
        }
    };
}

recorded_passive_event_case!(
    time_observation_after_recording_is_a_domain_noop,
    Event::ObserveTime {
        now: timestamp(1_300)
    }
);
recorded_passive_event_case!(
    activity_evidence_after_recording_is_a_domain_noop,
    Event::AcceptActivity {
        now: timestamp(1_300)
    }
);
recorded_passive_event_case!(
    quiet_dismissal_after_recording_is_a_domain_noop,
    Event::DismissReminder {
        now: timestamp(1_300),
        kind: ReminderKind::Quiet
    }
);
recorded_passive_event_case!(
    intrusive_dismissal_after_recording_is_a_domain_noop,
    Event::DismissReminder {
        now: timestamp(1_300),
        kind: ReminderKind::Intrusive
    }
);

macro_rules! expired_passive_event_case {
    ($name:ident, $event:expr) => {
        #[test]
        fn $name() -> Result<(), Box<dyn std::error::Error>> {
            let state = active_expired_state()?;
            let activity = state.activity();
            let transition = state.transition($event);

            assert_eq!(transition.outcome(), EventOutcome::Accepted);
            assert!(transition.effects().is_empty());
            assert_eq!(transition.state().activity(), activity);
            assert_eq!(transition.state().intake(), None);
            assert_eq!(
                transition.state().recording_availability(DEADLINE),
                RecordingAvailability::LateWindowElapsed
            );
            assert_eq!(
                transition.state().last_observed_at(),
                Some(timestamp(1_700))
            );
            Ok(())
        }
    };
}

expired_passive_event_case!(
    time_observation_after_expiration_is_a_domain_noop,
    Event::ObserveTime {
        now: timestamp(1_700)
    }
);
expired_passive_event_case!(
    activity_evidence_after_expiration_is_a_domain_noop,
    Event::AcceptActivity {
        now: timestamp(1_700)
    }
);
expired_passive_event_case!(
    quiet_dismissal_after_expiration_is_a_domain_noop,
    Event::DismissReminder {
        now: timestamp(1_700),
        kind: ReminderKind::Quiet
    }
);
expired_passive_event_case!(
    intrusive_dismissal_after_expiration_is_a_domain_noop,
    Event::DismissReminder {
        now: timestamp(1_700),
        kind: ReminderKind::Intrusive
    }
);

#[test]
fn authorized_reset_after_recording_is_the_only_activity_reset()
-> Result<(), Box<dyn std::error::Error>> {
    let state = active_recorded_state()?;
    let intake = state.intake();
    let transition = state.transition(Event::ReturnToInactive {
        now: timestamp(1_300),
        authorization: AUTHORIZED,
    });

    assert_eq!(transition.outcome(), EventOutcome::Accepted);
    assert_eq!(transition.state().activity(), ActivityState::Inactive);
    assert_eq!(transition.state().intake(), intake);
    assert!(transition.effects().is_empty());
    Ok(())
}

#[test]
fn authorized_reset_after_expiration_is_the_only_activity_reset()
-> Result<(), Box<dyn std::error::Error>> {
    let state = active_expired_state()?;
    let transition = state.transition(Event::ReturnToInactive {
        now: timestamp(1_700),
        authorization: AUTHORIZED,
    });

    assert_eq!(transition.outcome(), EventOutcome::Accepted);
    assert_eq!(transition.state().activity(), ActivityState::Inactive);
    assert_eq!(
        transition.state().recording_availability(DEADLINE),
        RecordingAvailability::LateWindowElapsed
    );
    assert!(transition.effects().is_empty());
    Ok(())
}

#[test]
fn record_after_expiration_is_rejected_without_losing_terminal_state()
-> Result<(), Box<dyn std::error::Error>> {
    let state = active_expired_state()?;
    let activity = state.activity();
    let transition = state.transition(Event::RecordDose {
        now: timestamp(1_700),
        intake_at: None,
        authorization: AUTHORIZED,
    });

    assert_eq!(
        transition.outcome(),
        EventOutcome::Rejected(Rejection::LateWindowElapsed)
    );
    assert_eq!(transition.state().activity(), activity);
    assert_eq!(transition.state().intake(), None);
    assert_eq!(
        transition.state().recording_availability(DEADLINE),
        RecordingAvailability::LateWindowElapsed
    );
    assert!(transition.effects().is_empty());
    Ok(())
}

#[test]
fn two_device_projections_are_independent() -> Result<(), Box<dyn std::error::Error>> {
    let schedule = ScheduledDose::new(
        DOSE_ID,
        MEDICATION_ID,
        SCHEDULED,
        TimeSpan::from_seconds(600),
    )?;
    let device_a = DeviceId::new(1);
    let device_b = DeviceId::new(2);
    let state_a = DoseState::initialize(schedule, device_a).into_parts().0;
    let state_b = DoseState::initialize(schedule, device_b).into_parts().0;
    let transition_a = state_a.transition(Event::ObserveTime { now: SCHEDULED });

    assert_eq!(state_b.last_observed_at(), None);
    assert!(!state_b.presentation().quiet_visible());
    assert!(transition_a.effects().iter().all(|effect| match effect {
        Effect::PresentQuiet { device_id, .. }
        | Effect::PresentIntrusively { device_id, .. }
        | Effect::CancelPresentation { device_id, .. }
        | Effect::ScheduleEvaluation { device_id, .. } => *device_id == device_a,
    }));
    Ok(())
}

#[test]
fn identical_inputs_produce_identical_transitions() -> Result<(), Box<dyn std::error::Error>> {
    let state = initial_state()?;
    let event = Event::AcceptActivity {
        now: timestamp(1_200),
    };

    assert_eq!(state.transition(event), state.transition(event));
    Ok(())
}

#[derive(Clone, Copy)]
enum EventKind {
    Observe,
    Activity,
    Snooze,
    Reset,
    DismissQuiet,
    DismissIntrusive,
    Record,
}

const fn event_for(kind: EventKind, now: Timestamp) -> Event {
    match kind {
        EventKind::Observe => Event::ObserveTime { now },
        EventKind::Activity => Event::AcceptActivity { now },
        EventKind::Snooze => Event::Snooze {
            now,
            until: timestamp(now.unix_seconds() + 10),
        },
        EventKind::Reset => Event::ReturnToInactive {
            now,
            authorization: AUTHORIZED,
        },
        EventKind::DismissQuiet => Event::DismissReminder {
            now,
            kind: ReminderKind::Quiet,
        },
        EventKind::DismissIntrusive => Event::DismissReminder {
            now,
            kind: ReminderKind::Intrusive,
        },
        EventKind::Record => Event::RecordDose {
            now,
            intake_at: None,
            authorization: AUTHORIZED,
        },
    }
}

fn assert_state_invariants(state: &DoseState, now: Timestamp) {
    let presentation = state.presentation();
    if presentation.intrusive_visible() {
        assert!(matches!(state.activity(), ActivityState::Active { .. }));
        assert_eq!(state.snoozed_until(), None);
        assert_eq!(state.intake(), None);
    }
    if let Some(until) = state.snoozed_until() {
        assert!(matches!(state.activity(), ActivityState::Active { .. }));
        assert!(!presentation.intrusive_visible());
        assert_eq!(state.intake(), None);
        assert!(until > state.last_observed_at().unwrap_or(now));
        assert!(until <= DEADLINE);
    }
    if state.intake().is_some() {
        assert!(!presentation.quiet_visible());
        assert!(!presentation.intrusive_visible());
        assert_eq!(state.snoozed_until(), None);
        assert_eq!(
            state.recording_availability(now),
            RecordingAvailability::AlreadyRecorded
        );
    }
}

#[test]
fn all_three_event_sequences_are_deterministic_and_preserve_invariants()
-> Result<(), Box<dyn std::error::Error>> {
    let kinds = [
        EventKind::Observe,
        EventKind::Activity,
        EventKind::Snooze,
        EventKind::Reset,
        EventKind::DismissQuiet,
        EventKind::DismissIntrusive,
        EventKind::Record,
    ];

    for first_kind in kinds {
        for second_kind in kinds {
            for third_kind in kinds {
                let mut first_state = initial_state()?;
                let mut second_state = initial_state()?;
                for (offset, kind) in [first_kind, second_kind, third_kind]
                    .into_iter()
                    .enumerate()
                {
                    let now = timestamp(1_100 + i64::try_from(offset)?);
                    let event = event_for(kind, now);
                    let first_transition = first_state.transition(event);
                    let second_transition = second_state.transition(event);
                    assert_eq!(first_transition, second_transition);
                    first_state = first_transition.into_parts().0;
                    second_state = second_transition.into_parts().0;
                    assert_state_invariants(&first_state, now);
                }
            }
        }
    }
    Ok(())
}
