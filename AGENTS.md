# Agent context

Start with [the project documentation](docs/src/project/index.md).

## Architecture boundary

The root `dosegoose` package is sans-I/O and free of global mutable state.
It may use libraries, but it must not access filesystems, networks, clocks,
randomness, sensors, operating-system APIs, environment variables, or hidden
process-global state. Pass those inputs explicitly and represent requested work
as inspectable effects.

Keep UniFFI and ABI concerns in `crates/dosegoose-uniffi`. Keep Android, iOS,
and desktop integrations in their respective `apps/` directories.
