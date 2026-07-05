## MODIFIED Requirements

### Requirement: On-the-road PTT cycle uses call-per-cycle with mandatory route switch
The system SHALL implement each on-the-road PTT capture as a complete Telecom self-call lifecycle: place call, acquire the car call-audio route, capture, release on hang-up, and perform a mandatory route switch that drops SCO and ends the call before allowing the next cycle.

#### Scenario: Complete PTT cycle
- **WHEN** the user presses play/pause on the steering wheel while in `OnTheRoad` mode and the active channel is ready
- **THEN** the system SHALL place a self-managed Telecom call
- **AND** wait for Bluetooth call audio route to become active for the car PTT connection
- **AND** play a ready beep through call audio
- **AND** start microphone capture
- **WHEN** the user presses hang-up
- **THEN** the system SHALL stop capture
- **AND** trigger the route switch (release SCO, end call, switch to A2DP)
- **AND** the car SHALL return to normal media mode with media controls available

#### Scenario: Route switch without response audio
- **WHEN** a PTT capture ends on the on-the-road path and no response audio is available to play
- **THEN** the system SHALL still trigger the route switch to drop SCO and end the call
- **AND** no audible response SHALL be played
- **AND** the car SHALL return to normal media mode

#### Scenario: RSM connection does not change car cycle route
- **WHEN** the RSM is bonded, SPP connected, and available as a SCO endpoint
- **AND** the user starts an `OnTheRoad` PTT cycle from the car
- **THEN** the system SHALL still use the Telecom car call-audio route for capture
- **AND** the system SHALL NOT route the car capture through the RSM SCO endpoint

### Requirement: Response audio plays via A2DP after SCO drop
The system SHALL play any post-capture response audio through the car's media audio path after the Telecom call and SCO link have been released.

#### Scenario: Response playback after capture
- **WHEN** a PTT capture has ended and response audio is available
- **AND** the route switch has completed (SCO dropped, call ended, audio mode returned to normal)
- **THEN** the system SHALL request media audio focus
- **AND** play the response through the media output path (`USAGE_MEDIA`)
- **AND** abandon media audio focus after playback completes

#### Scenario: No response audio
- **WHEN** a PTT capture has ended and no response audio is available
- **THEN** the system SHALL complete the route switch without playing any audio
- **AND** media controls SHALL be available for the next PTT cycle

#### Scenario: Response playback is not redirected to RSM
- **WHEN** an on-the-road PTT capture has ended and response audio is available
- **AND** the RSM is also connected and available as a Work endpoint
- **THEN** response playback SHALL use the car media output path after the call route switch
- **AND** response playback SHALL NOT target the RSM SCO output
