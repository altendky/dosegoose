# Dose Goose

Dose Goose is a local-first medication reminder designed to become persistent
only after the user is meaningfully active. A scheduled dose may be presented
quietly while the user sleeps; intrusive reminders begin after sustained
phone-carried activity indicates that the user is moving around.

The project is at an early architecture and implementation stage.

## Repository layout

- `src/` — portable sans-I/O Rust core
- `apps/desktop/` — egui simulator and development harness
- `apps/android/` — native Kotlin/Compose application
- `apps/ios/` — native Swift/SwiftUI application
- `crates/dosegoose-uniffi/` — mobile binding adapter
- `docs/src/project/` — requirements, architecture, privacy, and decisions

The core receives observations and dependencies as values and returns updated
state plus requested effects. Platform shells perform all I/O.

## Development

```console
cargo test --workspace
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo run -p dosegoose-simulator
```

The complete local validation entry point is `mise run check`.

## License

Licensed under either of
[Apache License, Version 2.0](LICENSE-APACHE) or
[MIT License](LICENSE-MIT), at your option.
