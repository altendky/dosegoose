# Contributing

Thank you for contributing to Dose Goose.

Run `mise run check` before submitting a change. Keep the root core sans-I/O:
pass environmental observations explicitly and return requested effects as
data. Platform-specific work belongs in the appropriate application or adapter.

Unless explicitly stated otherwise, contributions are submitted under the
project's `MIT OR Apache-2.0` license.

The complete check includes a portable-core coverage gate of at least 95% for
functions, lines, and LLVM source regions. Run it independently with
`mise run coverage`.
