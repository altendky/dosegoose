# Dose Goose for Android

This directory contains the native Kotlin and Jetpack Compose application.

The first slices provide Today, Schedule, Medications, and Settings surfaces.
Room stores medication configuration, dated daily occurrences, opaque portable
core snapshots, and requested platform effects; Preferences DataStore stores
the System, Light, or Dark appearance choice plus the overnight sleep-policy
window and its small evaluation state. First-run example medications are
seeded once. Each once-daily schedule has a platform-picked due time plus early-
and late-taking windows. Today is projected from the portable Rust core and
supports Take now and an explicitly backdated intake time within the valid
interval, with a separate confirmed override for a selected past time before
that interval.

The native shell resolves local schedule times, supplies explicit clock and
foreground/unlock facts through UniFFI, and reconciles state on startup,
foreground entry, and known time boundaries. Regular notifications begin when
the early-taking window opens, while
high-importance alarm-style notifications are reconciled from durable core
state after the early-taking window opens and activity is accepted. AlarmManager restores
early, due, late-window, snooze, and midnight evaluation
after process death or reboot. While an inactive dose is available, Google Play
services supplies low-power walking/running transitions; accepted evidence can
start the intrusive reminder. Physical activity permission is opt-in from
Settings, raw sensor data is not retained, and regular due reminders remain
available without it. After activity has been accepted, two hours of sustained
high-confidence sleep evidence inside the configurable overnight window clears
the device activity latch for later occurrences. It never silences an alarm
already in progress and intentionally does not treat daytime naps as bedtime.
Notification content is private on the lock screen and
is not bridged to companion devices.

## Build

The project requires JDK 17, Android SDK 37, NDK r30, Rust 1.98 with the Android
targets, and cargo-ndk 4.1.2.

```console
./gradlew test lint assembleDebug assembleDebugAndroidTest
```

Debug builds use the developer's normal Android debug keystore. They must not be
signed with a production key.

Open this directory as a project in Android Studio and run the `app`
configuration on an API 26 or newer emulator or device. From a command line,
install the assembled application with:

```console
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The JVM reducer and mapping tests run as part of `test`. Room, DataStore, and
Compose interaction tests are compiled by `assembleDebugAndroidTest`; run them
on a connected emulator or device with `./gradlew connectedDebugAndroidTest`.
