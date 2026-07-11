## MODIFIED Requirements

### Requirement: Audio input terminal ownership is atomic
The audio input subsystem SHALL assign exactly one terminal owner to each active audio input session before launching suspendable completion, cancellation, setup-failure, timeout, or shutdown work. A later terminal signal for the same session SHALL NOT replace the claimed terminal owner or repeat any terminal effect. For every claimed session, the subsystem SHALL invoke each applicable terminal effect exactly once: notify the committed target of its terminal event, release the session-owned route, release the committed-target lease, clear the matching active-session state, and publish terminal completion. The subsystem SHALL attempt the remaining effects in deterministic order even when target notification, route release, lease release, or another cleanup effect throws or is cancelled; it SHALL normalize such failures into the single published terminal outcome. Active-session clearance SHALL occur only after session-owned route and target-lease release have each been attempted, and terminal completion SHALL be published only after every applicable cleanup effect has been attempted. The terminal sequence SHALL run to completion despite caller cancellation, timeout, or service teardown, and late callbacks SHALL have no effect after completion is published.

#### Scenario: Normal release precedes connection-ended callback
- **WHEN** an active On-the-road capture receives a normal release signal
- **AND** a connection-ended callback arrives before normal completion has finished
- **THEN** the normal release SHALL remain the session's terminal owner
- **AND** the selected channel SHALL receive exactly one terminal recording event
- **AND** the later callback SHALL NOT convert the session into cancellation
- **AND** the route and committed-target lease SHALL each be released exactly once
- **AND** active-session clearance and terminal completion publication SHALL each occur exactly once

#### Scenario: Forced cancellation wins before terminal recording exists
- **WHEN** an active or pending session is force-cancelled before a terminal recording can be delivered
- **THEN** cancellation SHALL become the session's terminal owner
- **AND** the selected committed target SHALL receive exactly one cancellation event when a committed target exists
- **AND** no target notification SHALL occur when no target was committed
- **AND** later release callbacks SHALL NOT emit terminal recording delivery
- **AND** the session route and any committed-target lease SHALL each be released exactly once
- **AND** active-session clearance and terminal completion publication SHALL each occur exactly once

#### Scenario: Target terminal callback throws
- **WHEN** the committed target throws while receiving its one terminal recording, cancellation, or failure event
- **THEN** the subsystem SHALL NOT invoke that target terminal event again
- **AND** the subsystem SHALL still attempt route release and committed-target lease release exactly once
- **AND** the subsystem SHALL still clear the matching active session exactly once
- **AND** the subsystem SHALL publish exactly one terminal completion containing the normalized target failure

#### Scenario: Route or lease release throws
- **WHEN** session-owned route release or committed-target lease release throws during terminal cleanup
- **THEN** the failing release operation SHALL NOT be invoked a second time for that session
- **AND** every remaining applicable cleanup effect SHALL still be attempted exactly once
- **AND** the matching active session SHALL be cleared exactly once after both release operations have been attempted
- **AND** exactly one terminal completion SHALL publish the normalized cleanup failure

#### Scenario: Normal terminal processing exceeds cleanup-effect duration
- **WHEN** a committed target's normal release performs bounded transcription, synthesis, or acknowledged hardware delivery for longer than an individual cleanup-effect timeout
- **THEN** the audio-input subsystem SHALL await that terminal callback under its dedicated bounded processing deadline
- **AND** it SHALL NOT release the session route or committed-target lease before the callback reaches its terminal result
- **AND** after that result it SHALL continue playback and cleanup in the normal deterministic order

#### Scenario: Terminal worker is cancelled or times out
- **WHEN** caller cancellation or a timeout interrupts a claimed session while terminal notification or cleanup is in progress
- **THEN** the claimed terminal owner SHALL remain unchanged
- **AND** the subsystem SHALL finish attempting every remaining applicable terminal effect exactly once
- **AND** cancellation or timeout SHALL NOT leave the route, committed-target lease, or active-session reservation owned by the terminal session
- **AND** exactly one terminal completion SHALL be published

#### Scenario: Service teardown races ordinary completion
- **WHEN** service teardown begins while an ordinary release, setup failure, preparation timeout, or forced cancellation is terminating the same session
- **THEN** only the first signal SHALL claim terminal ownership
- **AND** teardown SHALL join or await that terminal sequence rather than start a second cleanup sequence
- **AND** target notification, route release, committed-target lease release, active-session clearance, and terminal completion publication SHALL each occur exactly once when applicable
- **AND** no callback arriving after terminal completion SHALL revive the session or produce a late effect
