## ADDED Requirements

### Requirement: Ready beep commits selected-channel delivery
The audio input subsystem SHALL treat the ready beep as a mandatory commit signal. After the ready beep completes for a PTT, audio captured from that PTT SHALL be delivered to the channel selected at PTT start through the channel input contract. The ready beep SHALL NOT be played for a PTT that has not been accepted by the selected channel.

#### Scenario: Ready beep follows channel commitment
- **WHEN** a PTT source requests capture
- **AND** the selected channel accepts the input request
- **AND** route and capture preflight succeed for the selected input mode
- **THEN** the audio input subsystem SHALL play the ready beep exactly once before accepting user speech for channel delivery
- **AND** audio captured after the ready beep SHALL reach the committed channel target

#### Scenario: Channel cannot accept input
- **WHEN** a PTT source requests capture
- **AND** the selected channel is unavailable, unconfigured, uninitialized, or otherwise refuses the input request before commitment
- **THEN** the audio input subsystem SHALL NOT play the ready beep
- **AND** SHALL NOT report a committed channel input session
- **AND** SHALL provide problem feedback when possible

#### Scenario: Setup fails after route readiness but before commitment
- **WHEN** route readiness succeeds
- **AND** capture preflight, ready beep playback, or selected-channel commitment fails before the ready beep contract is satisfied
- **THEN** the audio input subsystem SHALL release session-owned route resources exactly once
- **AND** SHALL NOT deliver a channel-visible started event
- **AND** SHALL provide problem feedback when possible

### Requirement: Problem beep marks uncommitted PTT
The audio input subsystem SHALL treat the problem beep as user-visible feedback that a user-visible PTT attempt will not reach the selected channel. Problem beep SHALL NOT imply that the audio route is unusable; it only means the selected channel will not process this PTT audio.

#### Scenario: Pre-commit failure produces problem feedback
- **WHEN** a user-visible PTT attempt fails before ready beep due to route validation, Telecom timeout, capture preflight, channel refusal, stale session, wrong source, or cancellation
- **THEN** the audio input subsystem SHALL play the problem beep when a safe feedback route is available
- **AND** SHALL NOT play the ready beep
- **AND** SHALL leave no active committed channel input behind

#### Scenario: Problem beep is best effort
- **WHEN** the input subsystem cannot safely play the problem beep on the failed route
- **THEN** the system SHALL still fail closed and clean up the route/session state
- **AND** SHALL NOT reinterpret the failure as a ready state

### Requirement: Session target remains immutable after commitment
The audio input subsystem SHALL bind a committed input session to the channel target accepted before ready beep. Subsequent active-channel or debug-mode changes SHALL NOT redirect start, release, cancellation, failure, terminal PCM, or playback-completion events for that active session.

#### Scenario: Channel selection changes during active PTT
- **WHEN** a PTT session has committed to a channel target
- **AND** the user changes the active channel before release
- **THEN** terminal input events for that session SHALL still be delivered to the committed target
- **AND** route cleanup SHALL remain owned by the audio input subsystem
