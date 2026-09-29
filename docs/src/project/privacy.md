# Privacy

Dose Goose is local-first and does not require a project account or
project-operated server.

- Medication data and reminder state are authoritative on the device.
- Android will omit the `INTERNET` permission.
- Android stores the initial medication configuration in app-private Room and
  DataStore files and excludes those files from operating-system backup.
- Android notifications use private lock-screen visibility with generic public
  content so a locked screen need not expose medication names.
- Android requests the Physical activity permission only when the user chooses
  to enable live activity evidence from Settings. Google Play services performs
  walking/running transition and sleep classification; Dose Goose retains no
  raw sensor samples or classification history. It stores a device-level
  accepted-activity latch, its projection into actionable dose state, and only
  the timestamps needed to evaluate the configured two-hour overnight sleep
  policy.
- If Physical activity permission or compatible Google Play services are
  unavailable, regular due reminders continue without sensor-based escalation.
- The applications will not contain direct cloud-provider clients.
- Optional cloud-backed backup will use a location selected through the
  operating system's document or file-provider interface.
- Cloud backup data will be encrypted before it leaves the application.
- A portable, versioned, human-readable export will be available separately.
- Clearing local data, deleting a cloud backup, and disconnecting a backup
  destination will be distinct explicit operations.

iOS does not provide an Android-style manifest permission that prohibits all
network access. The iOS application will therefore make no direct network
requests, while selected file providers perform any cloud transport.

The initial local database relies on operating-system application sandboxing
and device storage protection; it is not custom-encrypted. The threat model and
key management for any stronger at-rest protection remain open design work.
