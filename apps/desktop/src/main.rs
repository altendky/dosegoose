use dosegoose::{
    ActivityState, DeviceId, DoseGuidance, DoseId, DoseState, Event, EventOutcome,
    ForegroundAuthorization, MedicationId, RecordingAvailability, ReminderKind, ScheduledDose,
    TimeSpan, Timestamp,
};
use eframe::egui;

const DOSE_ID: DoseId = DoseId::new(1);
const MEDICATION_ID: MedicationId = MedicationId::new(1);
const DEVICE_ID: DeviceId = DeviceId::new(1);

#[derive(Clone, Copy)]
struct PreviewPalette {
    phone_shell: egui::Color32,
    screen: egui::Color32,
    card: egui::Color32,
    card_secondary: egui::Color32,
    accent: egui::Color32,
    accent_soft: egui::Color32,
    warning: egui::Color32,
    danger: egui::Color32,
    border: egui::Color32,
}

#[derive(Default)]
enum PreviewAction {
    #[default]
    None,
    Dismiss(ReminderKind),
    Snooze,
    Record,
}

struct PreviewModel {
    state: DoseState,
    now: Timestamp,
    guidance: DoseGuidance,
    availability: RecordingAvailability,
    status: String,
    status_detail: String,
    notification_detail: String,
    can_record: bool,
}

impl PreviewModel {
    fn new(state: DoseState, now: Timestamp, authorization: ForegroundAuthorization) -> Self {
        let guidance = state.guidance(now);
        let availability = state.recording_availability(now);
        let (status, status_detail) = guidance_copy(guidance);
        let notification_detail = notification_copy(guidance);
        let can_record = availability == RecordingAvailability::Available
            && authorization.permits_sensitive_action();
        Self {
            state,
            now,
            guidance,
            availability,
            status,
            status_detail,
            notification_detail,
            can_record,
        }
    }
}

impl PreviewPalette {
    fn from_ui(ui: &egui::Ui) -> Self {
        if ui.visuals().dark_mode {
            Self {
                phone_shell: egui::Color32::from_rgb(7, 15, 14),
                screen: egui::Color32::from_rgb(16, 27, 24),
                card: egui::Color32::from_rgb(28, 43, 38),
                card_secondary: egui::Color32::from_rgb(35, 53, 47),
                accent: egui::Color32::from_rgb(105, 210, 183),
                accent_soft: egui::Color32::from_rgb(32, 73, 62),
                warning: egui::Color32::from_rgb(245, 181, 78),
                danger: egui::Color32::from_rgb(255, 129, 112),
                border: egui::Color32::from_rgb(59, 81, 73),
            }
        } else {
            Self {
                phone_shell: egui::Color32::from_rgb(25, 38, 35),
                screen: egui::Color32::from_rgb(246, 244, 237),
                card: egui::Color32::from_rgb(255, 253, 247),
                card_secondary: egui::Color32::from_rgb(235, 240, 234),
                accent: egui::Color32::from_rgb(27, 124, 103),
                accent_soft: egui::Color32::from_rgb(216, 239, 229),
                warning: egui::Color32::from_rgb(173, 101, 8),
                danger: egui::Color32::from_rgb(185, 61, 47),
                border: egui::Color32::from_rgb(203, 211, 202),
            }
        }
    }
}

fn format_sim_time(timestamp: Timestamp) -> String {
    let seconds = timestamp.unix_seconds().rem_euclid(24 * 60 * 60);
    let hours = seconds / (60 * 60);
    let minutes = (seconds % (60 * 60)) / 60;
    format!("{hours:02}:{minutes:02}")
}

fn format_duration(duration: TimeSpan) -> String {
    let seconds = duration.seconds();
    let hours = seconds / (60 * 60);
    let minutes = (seconds % (60 * 60)) / 60;
    let seconds = seconds % 60;

    if hours > 0 {
        format!("{hours}h {minutes}m")
    } else if minutes > 0 {
        format!("{minutes}m {seconds}s")
    } else {
        format!("{seconds}s")
    }
}

fn guidance_copy(guidance: DoseGuidance) -> (String, String) {
    match guidance {
        DoseGuidance::Upcoming { due_in } => (
            format!("Due in {}", format_duration(due_in)),
            "The early-taking window has not opened yet.".to_owned(),
        ),
        DoseGuidance::EarlyAvailable { due_in } => (
            format!("Available early · due in {}", format_duration(due_in)),
            "You may record this dose now; intrusive reminders wait until it is due.".to_owned(),
        ),
        DoseGuidance::Due => (
            "Due now".to_owned(),
            "Take your medication when you’re ready.".to_owned(),
        ),
        DoseGuidance::Overdue { late_by, remaining } => (
            format!("{} overdue", format_duration(late_by)),
            format!(
                "{} left in the recording window",
                format_duration(remaining)
            ),
        ),
        DoseGuidance::LateWindowElapsed { late_by } => (
            "Late window ended".to_owned(),
            format!(
                "Scheduled {} ago — follow your care guidance.",
                format_duration(late_by)
            ),
        ),
        DoseGuidance::Recorded { intake_at } => (
            "Dose recorded".to_owned(),
            format!("Taken at {}", format_sim_time(intake_at)),
        ),
    }
}

fn notification_copy(guidance: DoseGuidance) -> String {
    match guidance {
        DoseGuidance::Upcoming { .. } => "A scheduled dose is coming up.".to_owned(),
        DoseGuidance::EarlyAvailable { .. } => "Your early-taking window is open.".to_owned(),
        DoseGuidance::Due => "Your scheduled medication is due now.".to_owned(),
        DoseGuidance::Overdue { late_by, .. } => {
            format!("Your dose is {} overdue.", format_duration(late_by))
        }
        DoseGuidance::LateWindowElapsed { .. } => {
            "The recording window for this dose has ended.".to_owned()
        }
        DoseGuidance::Recorded { intake_at } => {
            format!("Recorded as taken at {}.", format_sim_time(intake_at))
        }
    }
}

struct Simulator {
    state: Option<DoseState>,
    now_seconds: i64,
    scheduled_seconds: i64,
    early_window_seconds: u64,
    late_window_seconds: u64,
    snooze_seconds: u64,
    use_earlier_intake: bool,
    intake_seconds: i64,
    app_is_foreground: bool,
    device_is_unlocked: bool,
    last_result: String,
    effect_log: Vec<String>,
}

impl Default for Simulator {
    fn default() -> Self {
        let mut simulator = Self {
            state: None,
            now_seconds: 900,
            scheduled_seconds: 1_000,
            early_window_seconds: 120,
            late_window_seconds: 600,
            snooze_seconds: 300,
            use_earlier_intake: false,
            intake_seconds: 1_000,
            app_is_foreground: true,
            device_is_unlocked: true,
            last_result: String::new(),
            effect_log: Vec::new(),
        };
        simulator.reset_scenario();
        simulator
    }
}

impl Simulator {
    const fn now(&self) -> Timestamp {
        Timestamp::from_unix_seconds(self.now_seconds)
    }

    const fn authorization(&self) -> ForegroundAuthorization {
        ForegroundAuthorization::new(self.app_is_foreground, self.device_is_unlocked)
    }

    fn reset_scenario(&mut self) {
        match ScheduledDose::new_with_early_window(
            DOSE_ID,
            MEDICATION_ID,
            Timestamp::from_unix_seconds(self.scheduled_seconds),
            TimeSpan::from_seconds(self.early_window_seconds),
            TimeSpan::from_seconds(self.late_window_seconds),
        ) {
            Ok(schedule) => {
                let initialization = DoseState::initialize(schedule, DEVICE_ID);
                self.effect_log = initialization
                    .effects()
                    .iter()
                    .map(|effect| format!("{effect:?}"))
                    .collect();
                self.state = Some(initialization.into_parts().0);
                self.intake_seconds = self.scheduled_seconds;
                "Scenario reset; evaluate time to begin.".clone_into(&mut self.last_result);
            }
            Err(error) => {
                self.state = None;
                self.last_result = format!("Invalid scenario: {error}");
            }
        }
    }

    fn dispatch(&mut self, event: Event) {
        let Some(state) = self.state.as_ref() else {
            "Reset to a valid scenario first.".clone_into(&mut self.last_result);
            return;
        };
        let transition = state.transition(event);
        let effect_summary = if transition.effects().is_empty() {
            "no effects".to_owned()
        } else {
            transition
                .effects()
                .iter()
                .map(|effect| format!("{effect:?}"))
                .collect::<Vec<_>>()
                .join(", ")
        };
        for effect in transition.effects() {
            self.effect_log.push(format!("{effect:?}"));
        }
        let (state, _, outcome) = transition.into_parts();
        self.state = Some(state);
        match outcome {
            EventOutcome::Accepted => {
                self.last_result = format!("Accepted: {effect_summary}");
            }
            EventOutcome::Rejected(rejection) => {
                self.last_result = format!("Rejected: {rejection}; {effect_summary}");
            }
        }
    }

    fn advance_and_evaluate(&mut self, seconds: i64) {
        self.now_seconds = self.now_seconds.saturating_add(seconds);
        self.dispatch(Event::ObserveTime { now: self.now() });
    }

    fn snooze_intrusive(&mut self) {
        let until_seconds = i64::try_from(self.snooze_seconds)
            .ok()
            .and_then(|seconds| self.now_seconds.checked_add(seconds));
        if let Some(until_seconds) = until_seconds {
            self.dispatch(Event::Snooze {
                now: self.now(),
                until: Timestamp::from_unix_seconds(until_seconds),
            });
        } else {
            "Rejected by simulator: snooze time overflowed.".clone_into(&mut self.last_result);
        }
    }

    fn record_dose(&mut self) {
        let intake_at = self
            .use_earlier_intake
            .then(|| Timestamp::from_unix_seconds(self.intake_seconds));
        self.dispatch(Event::RecordDose {
            now: self.now(),
            intake_at,
            authorization: self.authorization(),
        });
    }

    fn scenario_is_dirty(&self) -> bool {
        self.state.as_ref().is_some_and(|state| {
            let schedule = state.schedule();
            schedule.scheduled_at().unix_seconds() != self.scheduled_seconds
                || schedule.early_window().seconds() != self.early_window_seconds
                || schedule.late_window().seconds() != self.late_window_seconds
        })
    }

    fn show_scenario_controls(&mut self, ui: &mut egui::Ui) {
        ui.heading("Scenario");
        ui.label("Schedule and window edits take effect after Reset schedule.");
        if self.scenario_is_dirty() {
            ui.colored_label(
                ui.visuals().warn_fg_color,
                "Setup has unapplied changes; reset before running more events.",
            );
        }
        egui::Grid::new("scenario-grid")
            .num_columns(2)
            .show(ui, |ui| {
                ui.label("Current time (Unix seconds)");
                ui.add(egui::DragValue::new(&mut self.now_seconds).speed(1));
                ui.end_row();

                ui.label("Scheduled time");
                ui.add(egui::DragValue::new(&mut self.scheduled_seconds).speed(1));
                ui.end_row();

                ui.label("Early window (seconds)");
                ui.add(egui::DragValue::new(&mut self.early_window_seconds).speed(60));
                ui.end_row();

                ui.label("Late window (seconds)");
                ui.add(egui::DragValue::new(&mut self.late_window_seconds).speed(60));
                ui.end_row();
            });
        ui.horizontal(|ui| {
            if ui.button("Reset schedule").clicked() {
                self.reset_scenario();
            }
            if ui.button("Evaluate now").clicked() {
                self.dispatch(Event::ObserveTime { now: self.now() });
            }
            if ui.button("+1 minute").clicked() {
                self.advance_and_evaluate(60);
            }
            if ui.button("Go to due time").clicked() {
                self.now_seconds = self.scheduled_seconds;
                self.dispatch(Event::ObserveTime { now: self.now() });
            }
        });
    }

    fn show_event_controls(&mut self, ui: &mut egui::Ui) {
        ui.heading("Observations and reminder actions");
        ui.horizontal(|ui| {
            if ui.button("Accept activity evidence").clicked() {
                self.dispatch(Event::AcceptActivity { now: self.now() });
            }
            if ui.button("Dismiss quiet").clicked() {
                self.dispatch(Event::DismissReminder {
                    now: self.now(),
                    kind: ReminderKind::Quiet,
                });
            }
            if ui.button("Dismiss intrusive").clicked() {
                self.dispatch(Event::DismissReminder {
                    now: self.now(),
                    kind: ReminderKind::Intrusive,
                });
            }
        });
        ui.horizontal(|ui| {
            ui.label("Snooze seconds");
            ui.add(egui::DragValue::new(&mut self.snooze_seconds).speed(60));
            if ui.button("Snooze intrusive").clicked() {
                self.snooze_intrusive();
            }
        });

        ui.heading("Foreground authorization");
        ui.horizontal(|ui| {
            ui.checkbox(&mut self.app_is_foreground, "App is foreground");
            ui.checkbox(&mut self.device_is_unlocked, "Device is unlocked");
        });
        if ui.button("Return to inactive").clicked() {
            self.dispatch(Event::ReturnToInactive {
                now: self.now(),
                authorization: self.authorization(),
            });
        }

        ui.heading("Record medication taken");
        ui.horizontal(|ui| {
            ui.checkbox(&mut self.use_earlier_intake, "Specify intake time");
            ui.add_enabled(
                self.use_earlier_intake,
                egui::DragValue::new(&mut self.intake_seconds).speed(1),
            );
            if ui.button("Record dose").clicked() {
                self.record_dose();
            }
        });
    }

    fn show_notification_card(
        ui: &mut egui::Ui,
        palette: PreviewPalette,
        kind: ReminderKind,
        detail: &str,
    ) -> PreviewAction {
        let intrusive = kind == ReminderKind::Intrusive;
        let border_color = if intrusive {
            palette.danger
        } else {
            palette.border
        };
        let mut snooze = false;
        let mut dismiss = false;

        egui::Frame::new()
            .fill(palette.card_secondary)
            .stroke(egui::Stroke::new(
                if intrusive { 2.0 } else { 1.0 },
                border_color,
            ))
            .corner_radius(16)
            .inner_margin(12)
            .show(ui, |ui| {
                ui.horizontal(|ui| {
                    egui::Frame::new()
                        .fill(if intrusive {
                            palette.danger
                        } else {
                            palette.accent
                        })
                        .corner_radius(7)
                        .inner_margin(5)
                        .show(ui, |ui| {
                            ui.label(
                                egui::RichText::new("DG")
                                    .strong()
                                    .color(egui::Color32::WHITE),
                            );
                        });
                    ui.vertical(|ui| {
                        ui.strong(if intrusive {
                            "Dose Goose alert"
                        } else {
                            "Dose Goose"
                        });
                        ui.label(
                            egui::RichText::new(if intrusive {
                                "Medication reminder"
                            } else {
                                "Medication due"
                            })
                            .size(16.0),
                        );
                    });
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::TOP), |ui| {
                        ui.weak("now");
                    });
                });
                ui.add_space(7.0);
                ui.label(detail);
                if intrusive {
                    ui.label(
                        egui::RichText::new("Activity accepted · alarm presentation enabled")
                            .small()
                            .color(palette.danger),
                    );
                }
                ui.add_space(7.0);
                ui.horizontal(|ui| {
                    if intrusive && ui.button("Snooze").clicked() {
                        snooze = true;
                    }
                    if ui.button("Dismiss").clicked() {
                        dismiss = true;
                    }
                });
            });
        if snooze {
            PreviewAction::Snooze
        } else if dismiss {
            PreviewAction::Dismiss(kind)
        } else {
            PreviewAction::None
        }
    }

    fn show_phone_preview(&mut self, ui: &mut egui::Ui) {
        let palette = PreviewPalette::from_ui(ui);
        let Some(state) = self.state.clone() else {
            ui.centered_and_justified(|ui| {
                ui.label("Reset to a valid scenario to show the app preview.");
            });
            return;
        };
        let model = PreviewModel::new(state, self.now(), self.authorization());
        let mut action = PreviewAction::None;

        ui.vertical_centered(|ui| {
            ui.label(
                egui::RichText::new("PLATFORM PREVIEW")
                    .small()
                    .strong()
                    .weak(),
            );
            ui.add_space(6.0);
            action = Self::show_phone_shell(ui, palette, &model);
        });

        match action {
            PreviewAction::None => {}
            PreviewAction::Dismiss(kind) => self.dismiss_preview_reminder(model.now, kind),
            PreviewAction::Snooze => self.snooze_intrusive(),
            PreviewAction::Record => self.record_dose(),
        }
    }

    fn dismiss_preview_reminder(&mut self, now: Timestamp, kind: ReminderKind) {
        self.dispatch(Event::DismissReminder { now, kind });
    }

    fn show_phone_shell(
        ui: &mut egui::Ui,
        palette: PreviewPalette,
        model: &PreviewModel,
    ) -> PreviewAction {
        let mut action = PreviewAction::None;
        egui::Frame::new()
            .fill(palette.phone_shell)
            .corner_radius(38)
            .inner_margin(8)
            .show(ui, |ui| {
                ui.set_width(350.0);
                egui::Frame::new()
                    .fill(palette.screen)
                    .corner_radius(31)
                    .inner_margin(16)
                    .show(ui, |ui| {
                        ui.set_min_height(650.0);
                        Self::show_phone_status(ui, model.now);
                        action = Self::show_notification_center(ui, palette, model);
                        ui.add_space(6.0);
                        if Self::show_app_surface(ui, palette, model) {
                            action = PreviewAction::Record;
                        }
                    });
            });
        action
    }

    fn show_phone_status(ui: &mut egui::Ui, now: Timestamp) {
        ui.horizontal(|ui| {
            ui.label(egui::RichText::new(format_sim_time(now)).strong());
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                ui.label("●  ◢  100%");
            });
        });
        ui.add_space(12.0);
        ui.horizontal(|ui| {
            ui.heading("Notifications");
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                ui.weak("simulated");
            });
        });
        ui.add_space(6.0);
    }

    fn show_notification_center(
        ui: &mut egui::Ui,
        palette: PreviewPalette,
        model: &PreviewModel,
    ) -> PreviewAction {
        let mut action = PreviewAction::None;
        let presentation = model.state.presentation();
        if presentation.intrusive_visible() {
            action = Self::show_notification_card(
                ui,
                palette,
                ReminderKind::Intrusive,
                &model.notification_detail,
            );
            ui.add_space(8.0);
        }
        if presentation.quiet_visible() {
            let quiet_action = Self::show_notification_card(
                ui,
                palette,
                ReminderKind::Quiet,
                &model.notification_detail,
            );
            if !matches!(quiet_action, PreviewAction::None) {
                action = quiet_action;
            }
            ui.add_space(8.0);
        }
        if !presentation.quiet_visible() && !presentation.intrusive_visible() {
            Self::show_empty_notification(ui, palette, model.state.snoozed_until());
            ui.add_space(8.0);
        }
        action
    }

    fn show_empty_notification(
        ui: &mut egui::Ui,
        palette: PreviewPalette,
        snoozed_until: Option<Timestamp>,
    ) {
        egui::Frame::new()
            .fill(palette.card_secondary)
            .corner_radius(14)
            .inner_margin(12)
            .show(ui, |ui| {
                ui.weak("No notification showing");
                if let Some(until) = snoozed_until {
                    ui.label(format!("Alarm snoozed until {}", format_sim_time(until)));
                }
            });
    }

    fn show_app_surface(ui: &mut egui::Ui, palette: PreviewPalette, model: &PreviewModel) -> bool {
        let mut record = false;
        egui::Frame::new()
            .fill(palette.card)
            .stroke(egui::Stroke::new(1.0, palette.border))
            .corner_radius(20)
            .inner_margin(16)
            .show(ui, |ui| {
                Self::show_app_header(ui, palette);
                ui.add_space(16.0);
                ui.label(
                    egui::RichText::new("Scheduled medication")
                        .size(16.0)
                        .strong(),
                );
                ui.label(format!(
                    "Medication #{} · dose #{}",
                    model.state.schedule().medication_id().value(),
                    model.state.schedule().dose_id().value()
                ));
                ui.add_space(16.0);
                Self::show_guidance(ui, palette, model);
                ui.add_space(14.0);
                ui.separator();
                ui.add_space(8.0);
                Self::show_app_schedule(ui, &model.state);
                ui.add_space(14.0);
                record = Self::show_record_action(ui, palette, model);
                ui.add_space(8.0);
                Self::show_activity_status(ui, palette, model.state.activity());
            });
        record
    }

    fn show_app_header(ui: &mut egui::Ui, palette: PreviewPalette) {
        ui.horizontal(|ui| {
            egui::Frame::new()
                .fill(palette.accent_soft)
                .corner_radius(12)
                .inner_margin(9)
                .show(ui, |ui| {
                    ui.label(egui::RichText::new("DG").strong().color(palette.accent));
                });
            ui.vertical(|ui| {
                ui.label(egui::RichText::new("Dose Goose").size(19.0).strong());
                ui.weak("Today’s medication");
            });
        });
    }

    fn show_guidance(ui: &mut egui::Ui, palette: PreviewPalette, model: &PreviewModel) {
        let status_color = match model.guidance {
            DoseGuidance::Overdue { .. } => palette.warning,
            DoseGuidance::LateWindowElapsed { .. } => palette.danger,
            DoseGuidance::Upcoming { .. }
            | DoseGuidance::EarlyAvailable { .. }
            | DoseGuidance::Due
            | DoseGuidance::Recorded { .. } => palette.accent,
        };
        ui.label(
            egui::RichText::new(&model.status)
                .size(28.0)
                .strong()
                .color(status_color),
        );
        ui.label(&model.status_detail);
    }

    fn show_app_schedule(ui: &mut egui::Ui, state: &DoseState) {
        egui::Grid::new("app-preview-schedule")
            .num_columns(2)
            .show(ui, |ui| {
                ui.weak("Scheduled");
                Self::show_right_aligned_time(ui, state.schedule().scheduled_at());
                ui.end_row();
                ui.weak("Record by");
                Self::show_right_aligned_time(ui, state.schedule().late_deadline());
                ui.end_row();
            });
    }

    fn show_right_aligned_time(ui: &mut egui::Ui, time: Timestamp) {
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            ui.strong(format_sim_time(time));
        });
    }

    fn show_record_action(
        ui: &mut egui::Ui,
        palette: PreviewPalette,
        model: &PreviewModel,
    ) -> bool {
        let clicked = ui
            .add_enabled(
                model.can_record,
                egui::Button::new(egui::RichText::new("Record as taken").strong())
                    .fill(palette.accent_soft)
                    .min_size(egui::vec2(ui.available_width(), 42.0)),
            )
            .clicked();
        if !model.can_record {
            let explanation = match model.availability {
                RecordingAvailability::NotYetDue => "Available when the dose is due.",
                RecordingAvailability::LateWindowElapsed => "The late-dose window has ended.",
                RecordingAvailability::AlreadyRecorded => "This dose has already been recorded.",
                RecordingAvailability::Available => "Unlock the device and bring the app forward.",
            };
            ui.weak(explanation);
        }
        clicked
    }

    fn show_activity_status(ui: &mut egui::Ui, palette: PreviewPalette, activity: ActivityState) {
        let (copy, color) = match activity {
            ActivityState::Inactive => {
                ("Activity not yet accepted", ui.visuals().weak_text_color())
            }
            ActivityState::Active { .. } => ("Activity accepted", palette.accent),
        };
        ui.label(
            egui::RichText::new(format!("●  {copy}"))
                .small()
                .color(color),
        );
    }

    fn show_state(&self, ui: &mut egui::Ui) {
        ui.label(&self.last_result);
        if let Some(state) = &self.state {
            let schedule = state.schedule();
            egui::Grid::new("state-grid")
                .num_columns(2)
                .striped(true)
                .show(ui, |ui| {
                    ui.label("Device / medication / dose IDs");
                    ui.monospace(format!(
                        "{} / {} / {}",
                        state.device_id().value(),
                        schedule.medication_id().value(),
                        schedule.dose_id().value()
                    ));
                    ui.end_row();

                    ui.label("Schedule / deadline / expiration");
                    ui.monospace(format!(
                        "{} / {} / {}",
                        schedule.scheduled_at().unix_seconds(),
                        schedule.late_deadline().unix_seconds(),
                        schedule.expires_at().unix_seconds()
                    ));
                    ui.end_row();

                    ui.label("Guidance at edited current time");
                    ui.monospace(format!("{:?}", state.guidance(self.now())));
                    ui.end_row();

                    ui.label("Recording availability");
                    ui.monospace(format!("{:?}", state.recording_availability(self.now())));
                    ui.end_row();

                    ui.label("Activity");
                    ui.monospace(format!("{:?}", state.activity()));
                    ui.end_row();

                    ui.label("Last reconciled event time");
                    ui.monospace(format!("{:?}", state.last_observed_at()));
                    ui.end_row();

                    ui.label("Snoozed until");
                    ui.monospace(format!("{:?}", state.snoozed_until()));
                    ui.end_row();

                    ui.label("Quiet / intrusive visible");
                    ui.monospace(format!(
                        "{} / {}",
                        state.presentation().quiet_visible(),
                        state.presentation().intrusive_visible()
                    ));
                    ui.end_row();

                    ui.label("Intake record");
                    ui.monospace(format!("{:?}", state.intake()));
                    ui.end_row();
                });
        }
    }

    fn show_effects(&self, ui: &mut egui::Ui) {
        if self.effect_log.is_empty() {
            ui.label("No effects requested yet.");
        } else {
            for (index, effect) in self.effect_log.iter().enumerate().rev().take(12) {
                ui.monospace(format!("{}: {effect}", index + 1));
            }
        }
    }

    fn show_inspector(&mut self, ui: &mut egui::Ui) {
        ui.heading("Simulator controls");
        ui.label("Drive explicit inputs, then inspect the portable core’s response.");
        ui.add_space(6.0);
        egui::CollapsingHeader::new("Scenario and time")
            .default_open(true)
            .show(ui, |ui| self.show_scenario_controls(ui));
        egui::CollapsingHeader::new("Events and authorization")
            .default_open(true)
            .show(ui, |ui| self.show_event_controls(ui));
        egui::CollapsingHeader::new("Portable core state")
            .default_open(false)
            .show(ui, |ui| self.show_state(ui));
        egui::CollapsingHeader::new("Requested platform effects")
            .default_open(false)
            .show(ui, |ui| self.show_effects(ui));
    }
}

impl eframe::App for Simulator {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        ui.horizontal(|ui| {
            ui.heading("Dose Goose simulator");
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                egui::widgets::global_theme_preference_buttons(ui);
                ui.weak("Theme");
            });
        });
        ui.label("A visual shell around deterministic, sans-I/O reminder behavior.");
        ui.separator();

        if ui.available_width() >= 920.0 {
            ui.horizontal_top(|ui| {
                ui.allocate_ui_with_layout(
                    egui::vec2(410.0, ui.available_height()),
                    egui::Layout::top_down(egui::Align::Center),
                    |ui| {
                        egui::ScrollArea::vertical()
                            .id_salt("preview-scroll")
                            .show(ui, |ui| self.show_phone_preview(ui));
                    },
                );
                ui.separator();
                ui.allocate_ui_with_layout(
                    ui.available_size(),
                    egui::Layout::top_down(egui::Align::Min),
                    |ui| {
                        egui::ScrollArea::vertical()
                            .id_salt("inspector-scroll")
                            .show(ui, |ui| self.show_inspector(ui));
                    },
                );
            });
        } else {
            egui::ScrollArea::vertical().show(ui, |ui| {
                self.show_phone_preview(ui);
                ui.add_space(12.0);
                ui.separator();
                self.show_inspector(ui);
            });
        }
    }
}

fn main() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1_180.0, 820.0])
            .with_min_inner_size([720.0, 640.0]),
        ..Default::default()
    };
    eframe::run_native(
        "Dose Goose simulator",
        options,
        Box::new(|_creation_context| Ok(Box::<Simulator>::default())),
    )
}

#[cfg(test)]
mod tests {
    use super::{format_duration, format_sim_time, guidance_copy};
    use dosegoose::{DoseGuidance, TimeSpan, Timestamp};

    #[test]
    fn simulation_time_wraps_with_euclidean_days() {
        assert_eq!(format_sim_time(Timestamp::from_unix_seconds(-60)), "23:59");
        assert_eq!(
            format_sim_time(Timestamp::from_unix_seconds(86_460)),
            "00:01"
        );
    }

    #[test]
    fn durations_use_compact_readable_units() {
        assert_eq!(format_duration(TimeSpan::from_seconds(42)), "42s");
        assert_eq!(format_duration(TimeSpan::from_seconds(125)), "2m 5s");
        assert_eq!(format_duration(TimeSpan::from_seconds(7_445)), "2h 4m");
    }

    #[test]
    fn guidance_copy_keeps_both_overdue_boundaries_visible() {
        let (headline, detail) = guidance_copy(DoseGuidance::Overdue {
            late_by: TimeSpan::from_seconds(65),
            remaining: TimeSpan::from_seconds(535),
        });

        assert_eq!(headline, "1m 5s overdue");
        assert_eq!(detail, "8m 55s left in the recording window");
    }
}
