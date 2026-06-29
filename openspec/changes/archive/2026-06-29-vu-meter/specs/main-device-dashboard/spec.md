## ADDED Requirements

### Requirement: Dashboard renders the VU meter during capture

The main dashboard SHALL render the VU meter (see `vu-meter` capability) driven
by the capture service's `level` and `isCapturing` signals, so the operator sees
live microphone level during any capture session. The dashboard SHALL obtain
these signals from `PttForegroundService` via the existing binder, alongside the
existing `appState` collection.

#### Scenario: Dashboard shows the meter while capturing
- **WHEN** the dashboard is visible and `isCapturing` is true
- **THEN** the dashboard renders the VU meter reflecting the live `level`

#### Scenario: Dashboard omits the meter while idle
- **WHEN** the dashboard is visible and `isCapturing` is false
- **THEN** the dashboard does not render the VU meter and reserves no space for it

#### Scenario: Meter reflects any talk mode
- **WHEN** the dashboard is visible and a capture session is active on any channel (journal, STT, or a future channel)
- **THEN** the dashboard renders the VU meter driven by the unified capture signal, with no per-mode wiring
