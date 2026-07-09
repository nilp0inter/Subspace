## MODIFIED Requirements

### Requirement: Audio output owns route release
The `PcmOutput` implementation paired with a resolved audio route SHALL retain the route-specific release behavior exposed by `releaseRoute()`. The central audio input session owner SHALL invoke `releaseRoute()` for the active session when capture, post-capture processing, failure, or cancellation reaches a terminal state. Channel controllers SHALL NOT receive the resolved route and SHALL NOT call `releaseRoute()` or `ScoRoute.release()` directly in the PTT flow. The release mode (warm 30-second retention for Work SCO, immediate release for Telecom, no-op for local fallback) SHALL remain a property of the resolved route output, not a per-channel decision.

#### Scenario: SCO output releases with warm retention
- **WHEN** a PTT cycle completes on the SCO route and the audio input session owner releases the active route
- **THEN** the SCO route's `release()` is invoked
- **AND** the SCO controller starts the 30-second warmup retention window

#### Scenario: Telecom output releases immediately
- **WHEN** a PTT cycle completes on the telecom route and the audio input session owner releases the active route
- **THEN** the SCO route is released immediately (no warmup window)
- **AND** the system awaits telecom disconnect before any post-capture playback

#### Scenario: Local fallback output release is a no-op
- **WHEN** a PTT cycle completes on the local fallback route and the audio input session owner releases the active route
- **THEN** no route resources are released (there are none)

#### Scenario: Channel controller does not touch route release
- **WHEN** a channel controller handles PTT input, terminal audio, cancellation, or max-duration on any route
- **THEN** the controller SHALL NOT call `ScoRoute.release()` or `PcmOutput.releaseRoute()`
- **AND** the audio input session owner SHALL release the active route exactly once

## ADDED Requirements

### Requirement: Work route cleanup is session-owned
The Work SCO route SHALL be cleared only by the audio input session that owns it or by explicit fail-safe service teardown. A stale release from an older session SHALL NOT clear a newer session's communication device.

#### Scenario: Stale Work release ignored
- **WHEN** an older Work session release runs after a newer audio input session has become active
- **THEN** the older release does not clear the newer session's communication device
- **AND** the newer session remains responsible for its own route release
