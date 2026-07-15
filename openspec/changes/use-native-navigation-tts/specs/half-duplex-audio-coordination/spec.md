## MODIFIED Requirements

### Requirement: Host audio is process-wide half-duplex
The host SHALL admit at most one capture, channel-content playback, or system-navigation playback operation at a time across Work, On-the-road, and On-a-pinch. Capture reservation, capture route acquisition, recording, capture finalization, playback reservation, playback route acquisition, playback, and their route-release cleanup SHALL be mutually exclusive across operations, and no channel runtime SHALL participate in that arbitration. Canceling an owning playback caller SHALL be terminal and teardown-ordered: the host SHALL stop that exact `ActivePcmPlayback`, publish terminal completion only after its `AudioTrack` has been stopped and released, release the acquired playback route, and clear only the matching coordinator owner before the canceling caller's join completes and before a replacement operation is admitted.

#### Scenario: Response becomes ready during capture
- **WHEN** a channel response becomes playable while any PTT capture is reserved, acquiring a route, recording, finalizing, or releasing its route
- **THEN** the host SHALL leave the response pending and unheard
- **AND** it SHALL NOT acquire any playback route until capture terminal cleanup publishes that audio admission is free

#### Scenario: Capture admission wins before playback
- **WHEN** a PTT operation has obtained a capture reservation but has not yet opened its recorder or acquired its route
- **AND** a pending response becomes eligible
- **THEN** playback admission SHALL remain blocked until the reserved capture operation completes its terminal cleanup

#### Scenario: Playback route cleanup blocks capture
- **WHEN** response audio has stopped but its playback route or focus cleanup has not completed
- **THEN** the host SHALL continue treating audio as playback-owned
- **AND** it SHALL NOT admit a capture operation until cleanup completes

#### Scenario: Canceling an owning playback stops that exact playback
- **WHEN** the sole owning caller cancels its playback operation
- **THEN** the host SHALL stop that exact `ActivePcmPlayback`
- **AND** it SHALL NOT stop, skip, or release any other `ActivePcmPlayback` or any capture reservation or route

#### Scenario: Terminal completion is published after AudioTrack teardown
- **WHEN** an owning playback is canceled or naturally completes
- **THEN** the host SHALL publish terminal completion only after the owning `AudioTrack` has been stopped and released
- **AND** the coordinator SHALL NOT admit a replacement operation until that terminal completion is published

#### Scenario: Route cleanup and matching-owner clear complete before join
- **WHEN** a canceling caller joins its owning playback job
- **THEN** the host SHALL release the acquired playback route and clear only the matching coordinator owner before the join returns
- **AND** a replacement operation SHALL NOT acquire audio admission until that clear is visible

## ADDED Requirements

### Requirement: Navigation cancellation is scoped to its own prior playback
Navigation SHALL cancel only its own tracked prior navigation playback job when that job is the one that owns audio admission. When capture or an unrelated playback operation owns admission, no navigation-owned playback exists to cancel and a new navigation request SHALL respect `Busy` or remain pending until audio admission is free. Navigation cancellation SHALL NOT stop, skip, or preempt any unrelated playback operation, capture reservation, capture route, or capture finalization.

#### Scenario: Navigation cancels its own prior announcement
- **WHEN** navigation starts a new announcement while its prior announcement playback is the one that owns audio admission and is still active or completing
- **THEN** the host SHALL cancel only the prior navigation playback job
- **AND** the new announcement SHALL wait for that cancellation's teardown to complete before acquiring audio admission

#### Scenario: Navigation respects Busy while capture or unrelated playback owns admission
- **WHEN** navigation issues a request while capture or an unrelated channel-content playback owns audio admission
- **THEN** no navigation-owned playback exists to cancel
- **AND** the navigation request SHALL receive `Busy` or remain pending until audio admission is free
- **AND** the host SHALL NOT stop, skip, or release the owning capture or unrelated playback operation

#### Scenario: Navigation cancellation does not preempt capture
- **WHEN** navigation cancels its prior playback during that playback's teardown while a capture operation is pending admission
- **THEN** the host SHALL NOT interrupt, stop, or release the pending capture operation
- **AND** the capture operation SHALL acquire admission only after the cancellation teardown completes and the coordinator owner is cleared

### Requirement: PTT and SOS control remain authoritative during playback cancellation
The existing PTT rejection, rejection-tone debounce, rejection-release consumption, RSM SOS skip, SOS conversation preservation, and SOS race resolution semantics SHALL remain authoritative. Navigation cancellation SHALL NOT bypass, weaken, or reorder those control paths. While any playback cancellation teardown has not yet cleared the coordinator owner, the host SHALL continue treating audio as playback-owned and SHALL NOT admit capture.

#### Scenario: Capture admission is blocked during cancellation teardown
- **WHEN** a PTT press arrives while any playback cancellation is performing teardown but has not yet released the coordinator owner
- **THEN** the host SHALL continue treating audio as playback-owned
- **AND** it SHALL NOT admit capture until the cancellation teardown completes and the coordinator owner is cleared

#### Scenario: Navigation cancellation does not bypass PTT or SOS control
- **WHEN** navigation cancels its prior playback during the teardown of that playback
- **THEN** the existing PTT rejection, rejection-release consumption, and SOS skip paths SHALL remain unchanged
- **AND** the navigation cancellation SHALL NOT introduce a second route-release owner or terminal-cleanup path that bypasses those controls