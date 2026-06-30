## MODIFIED Requirements

### Requirement: Channel-card long-press starts PTT
The system SHALL allow the user to start a PTT session by long-pressing the main surface of any functional channel card. A phone-originated PTT session that remains held and unlocked SHALL end on finger release or cancellation. A phone-originated PTT session that has been slide-locked SHALL remain active after finger release and SHALL end only through an explicit stop action, maximum-duration cutoff, or system-forced termination such as app focus loss.

#### Scenario: Long-press starts PTT for selected channel
- **WHEN** the user long-presses the main surface of a functional channel card
- **THEN** the system SHALL set that channel as the active channel
- **AND** the system SHALL start a PTT session for that channel

#### Scenario: Unlocked long-press release ends PTT
- **WHEN** a phone-originated PTT session is active from a channel-card long-press
- **AND** the session has not been slide-locked
- **AND** the user releases or cancels the press
- **THEN** the system SHALL end the PTT session

#### Scenario: Locked long-press release does not end PTT
- **WHEN** a phone-originated PTT session is active from a channel-card long-press
- **AND** the session has been slide-locked
- **AND** the user releases the press
- **THEN** the system SHALL keep the PTT session active

## ADDED Requirements

### Requirement: Phone PTT supports inward slide-to-lock
The system SHALL allow a held phone-originated PTT session to become locked by sliding horizontally inward from the initial press side of the channel card. Locking SHALL prevent finger release from ending the PTT session.

#### Scenario: Left-side press locks by sliding right
- **WHEN** the user long-presses a functional channel card with the initial press on the left side of the card's PTT surface
- **AND** the phone-originated PTT session is active
- **AND** the user slides right past the lock threshold before releasing
- **THEN** the system SHALL lock the PTT session

#### Scenario: Right-side press locks by sliding left
- **WHEN** the user long-presses a functional channel card with the initial press on the right side of the card's PTT surface
- **AND** the phone-originated PTT session is active
- **AND** the user slides left past the lock threshold before releasing
- **THEN** the system SHALL lock the PTT session

#### Scenario: Incidental movement does not cancel active phone PTT
- **WHEN** a phone-originated PTT session is active from a channel-card long-press
- **AND** the user moves the finger without crossing the inward lock threshold
- **THEN** the system SHALL keep the PTT session active
- **AND** the system SHALL NOT treat the movement as a release

#### Scenario: Slide-lock before ready beep completes
- **WHEN** a phone-originated PTT session is armed and the ready beep has not completed
- **AND** the user slides inward past the lock threshold before releasing
- **THEN** the system SHALL remember the locked state
- **AND** recording SHALL start after the ready beep completes if the PTT session remains valid

#### Scenario: Explicit stop ends locked phone PTT
- **WHEN** a phone-originated PTT session has been slide-locked
- **AND** the user activates the visible stop affordance
- **THEN** the system SHALL end the PTT session

#### Scenario: Maximum duration ends locked phone PTT
- **WHEN** a phone-originated PTT session has been slide-locked
- **AND** the capture reaches the maximum capture duration
- **THEN** the system SHALL stop recording according to the same max-duration behavior used by held phone-originated PTT sessions

#### Scenario: App focus loss ends locked phone PTT
- **WHEN** a phone-originated PTT session has been slide-locked
- **AND** the app loses foreground interaction or the gesture is system-cancelled
- **THEN** the system SHALL end the PTT session
