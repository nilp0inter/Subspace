## MODIFIED Requirements

### Requirement: Keyboard channel identity and configuration
The system SHALL provide a built-in Keyboard channel implementation identified by a stable implementation identifier. Each persisted Keyboard channel instance SHALL have its own stable instance ID, human-readable name, and independently configurable logical host profile drawn from the supported Sleepwalker host profiles. The provider-owned versioned configuration payload SHALL preserve the selected profile across app restarts and SHALL default an instance with no persisted profile to `LINUX_US`. No singleton Keyboard instance ID SHALL be used for configuration lookup, routing, runtime ownership, or status association.

#### Scenario: Keyboard instances loaded from persisted configuration
- **WHEN** the catalogue loads two persisted Keyboard channel instances with different stable instance IDs and host profiles
- **THEN** both instances SHALL be restored in catalogue order
- **AND** each instance SHALL retain its own persisted host profile
- **AND** neither instance SHALL obtain configuration by looking up a singleton Keyboard ID or the first Keyboard instance

#### Scenario: Keyboard instance has no persisted profile
- **WHEN** a Keyboard channel instance is created or migrated without a persisted host profile
- **THEN** that instance SHALL use `LINUX_US`
- **AND** the default SHALL be represented in that instance's provider-owned configuration

#### Scenario: Host profile changed at runtime
- **WHEN** the user selects a new host profile for one Keyboard channel instance
- **THEN** the selection SHALL be persisted immediately for that instance only
- **AND** the next PTT cycle committed to that instance SHALL submit text using the new logical profile without requiring a service restart

### Requirement: Keyboard channel readiness
A Keyboard channel instance SHALL be ready if and only if its configuration contains a valid logical host profile and the host text-output capability reports semantic delivery availability for that profile. STT model readiness SHALL NOT factor into Keyboard channel readiness; it remains a separate runtime monitor state. The channel SHALL NOT inspect Sleepwalker discovery, BLE, GATT, HID, or connection-state objects to determine readiness.

#### Scenario: Text output is available for configured profile
- **WHEN** a Keyboard channel instance has a valid host profile
- **AND** the host text-output capability reports delivery available for that profile
- **THEN** that Keyboard channel instance SHALL be ready

#### Scenario: Text output is unavailable
- **WHEN** the host text-output capability reports delivery unavailable for a Keyboard instance's configured profile
- **THEN** that Keyboard channel instance SHALL be not ready
- **AND** the dashboard and Android Auto browse tree SHALL show the instance as Standby
- **AND** the channel SHALL NOT require a BLE or GATT state object to reach that result

#### Scenario: Disconnected PTT prepares text output before the ready beep
- **WHEN** PTT is pressed for a Keyboard instance whose text-output capability is recoverable but not currently connected
- **THEN** the host SHALL run bounded text-output preparation before accepting channel input
- **AND** the Keyboard runtime SHALL NOT accept input until the current Sleepwalker connection is proven ready for delivery
- **AND** the host SHALL NOT play the ready beep or start capture before that proof

#### Scenario: Keyboard text-output preparation does not establish readiness
- **WHEN** bounded preparation fails, times out, is cancelled, or reports a stale success after the connection is lost
- **THEN** the Keyboard runtime SHALL refuse input
- **AND** the host SHALL play problem feedback when its resolved route permits
- **AND** it SHALL NOT play the ready beep, start capture, transcribe audio, or submit text

### Requirement: Keyboard channel PTT lifecycle
A Keyboard channel instance SHALL follow the capture → transcribe → semantic text-delivery lifecycle on a PTT press-and-release cycle. The host audio-input subsystem SHALL own route acquisition, capture, and route release and SHALL provide terminal PCM through the channel input contract. After successful transcription, the Keyboard runtime SHALL submit the transcript and that instance's configured logical host profile to the host text-output capability. The runtime SHALL NOT compile HID operations, write transport frames, or control the Sleepwalker connection.

#### Scenario: Successful capture, transcription, and text delivery
- **WHEN** a ready Keyboard channel instance is the committed target of a completed PTT capture
- **THEN** the system SHALL capture audio through the host-owned resolved audio route
- **AND** the Keyboard runtime SHALL transcribe terminal PCM through the STT capability
- **AND** the Keyboard runtime SHALL request semantic text delivery with the transcript and that instance's configured host profile
- **AND** the host text-output capability SHALL perform any required keymap compilation and transport
- **AND** the host audio-input subsystem SHALL release the audio route exactly once after terminal handling
- **AND** a delivered outcome SHALL surface `Done` with the delivered text for that instance

#### Scenario: Transcription and acknowledged delivery exceed a cleanup-effect interval
- **WHEN** a committed Keyboard release requires longer than an individual cleanup-effect timeout to transcribe and complete acknowledged Sleepwalker delivery
- **AND** both operations remain within the dedicated bounded input-release deadline
- **THEN** the host SHALL allow the Keyboard terminal callback to reach its typed delivery outcome
- **AND** it SHALL NOT cancel transcription or text delivery merely because an individual cleanup-effect interval elapsed
- **AND** route and committed-target lease release SHALL follow the delivery outcome

#### Scenario: Empty audio capture
- **WHEN** a Keyboard channel instance receives terminal capture with no audio
- **THEN** the system SHALL surface `EmptyAudio` for that instance
- **AND** the runtime SHALL NOT attempt transcription or text delivery
- **AND** the host audio-input subsystem SHALL release the audio route exactly once

#### Scenario: STT model not ready
- **WHEN** a Keyboard channel instance receives terminal capture
- **AND** the STT model has not finished loading
- **THEN** the system SHALL surface `Error` with reason `"STT model not ready"` for that instance
- **AND** the runtime SHALL NOT request text delivery
- **AND** the host audio-input subsystem SHALL release the audio route exactly once

#### Scenario: Transcription failure
- **WHEN** transcription for a Keyboard channel instance returns a failure
- **THEN** the system SHALL surface `Error` with the failure reason for that instance
- **AND** the runtime SHALL NOT request text delivery
- **AND** the host audio-input subsystem SHALL release the audio route exactly once

### Requirement: HID safety discipline
The host text-output capability SHALL own the complete safety discipline for Sleepwalker-backed delivery, including keymap compilation, arm, correlation acknowledgement, disarm, force-release, cancellation, timeout, shutdown, and transport cleanup. For each accepted delivery operation, the host SHALL perform terminal safety cleanup exactly once on success, rejection, failure, cancellation, timeout, disconnect, or service shutdown. A Keyboard runtime SHALL only submit semantic text and a logical host profile and SHALL NOT issue `arm`, `disarm`, `kill`, HID, BLE, or GATT operations.

#### Scenario: Normal text delivery
- **WHEN** the host text-output capability accepts a Keyboard semantic delivery request
- **THEN** the host SHALL arm the Sleepwalker output before sending compiled key operations
- **AND** the host SHALL await the required correlation acknowledgement for the final operation
- **AND** the host SHALL disarm the output exactly once after successful delivery
- **AND** the Keyboard runtime SHALL observe only the typed semantic delivery outcome

#### Scenario: Failure after output is armed
- **WHEN** delivery fails, is cancelled, times out, disconnects, or is interrupted by shutdown after the host has armed the output
- **THEN** the host SHALL invoke force-release and disarm cleanup exactly once for that delivery operation when transport cleanup remains possible
- **AND** a cleanup exception SHALL NOT cause the host to repeat an already attempted cleanup effect
- **AND** the host SHALL publish exactly one typed terminal delivery outcome
- **AND** the Keyboard runtime SHALL NOT perform compensating hardware cleanup

#### Scenario: Failure before output is armed
- **WHEN** profile validation or keymap compilation fails before the host arms the output
- **THEN** the host SHALL return one rejected delivery outcome
- **AND** the host SHALL NOT arm the output or send key operations
- **AND** no Keyboard runtime SHALL access the keymap compiler or transport

### Requirement: Text rendering failure handling
The host text-output capability SHALL validate and compile text for the requested logical host profile before beginning physical delivery. When the transcript cannot be represented, it SHALL return a typed rejection identifying the unrepresentable character and profile and proving that no delivery effect began. The Keyboard runtime SHALL surface that reason as `Error` and SHALL NOT retry the rejected request automatically.

#### Scenario: Unrepresentable glyph in transcript
- **WHEN** a Keyboard runtime submits a transcript containing a character that the requested host profile cannot type
- **THEN** the host text-output capability SHALL reject the request with the unrepresentable character and profile
- **AND** the rejection SHALL state that delivery did not begin
- **AND** the host SHALL NOT arm Sleepwalker or send key operations
- **AND** the Keyboard runtime SHALL surface `Error` and SHALL NOT automatically replay the request
- **AND** the host audio-input subsystem SHALL release the audio route exactly once

### Requirement: Keyboard channel status reporting
The system SHALL surface Keyboard status per stable channel instance in monitor state. Each instance's status SHALL progress through `Idle`, `Recording`, `Transcribing`, `Typing`, `Done` with delivered text, or `Error` with a reason, and SHALL be updated on every lifecycle transition. `Typing` SHALL represent an in-progress semantic text-delivery request and SHALL NOT expose low-level transport state.

#### Scenario: Status transitions during a successful cycle
- **WHEN** the user presses PTT, speaks, releases, and semantic text delivery succeeds for a Keyboard instance
- **THEN** that instance's status SHALL transition `Idle` → `Recording` → `Transcribing` → `Typing` → `Done`
- **AND** another Keyboard instance's status SHALL remain independently associated with its own runtime

#### Scenario: Status reset on cancel
- **WHEN** active PTT is cancelled or its committed Keyboard runtime is deactivated
- **THEN** that Keyboard instance's status SHALL reset to `Idle` after its terminal event
- **AND** a late text-output completion SHALL NOT replace the terminal status

#### Scenario: Semantic delivery fails
- **WHEN** the host text-output capability returns a rejected, failed, or indeterminate outcome
- **THEN** the committed Keyboard instance SHALL transition from `Typing` to `Error`
- **AND** the error reason SHALL identify the typed outcome without exposing BLE, GATT, or HID objects

### Requirement: Sleepwalker BLE bridge connection management
The host text-output capability SHALL exclusively own Sleepwalker discovery, scanning, connection, reconnection, BLE/GATT configuration, notification subscription, MTU negotiation, HID/keymap transport, write pacing, connection state, and connection cleanup. It SHALL share and serialize hardware access across Keyboard channel instances according to host policy. Keyboard runtimes SHALL receive only semantic preparation, availability, and text-delivery operations and typed outcomes; they SHALL NOT create, retain, observe, reconnect, or close a Sleepwalker transport.

#### Scenario: Host prepares Sleepwalker on demand
- **WHEN** the host text-output capability receives a preparation request for a supported logical profile while Sleepwalker is unavailable
- **THEN** the host SHALL apply its discovery, connection, and reconnection policy
- **AND** the host SHALL perform all required BLE/GATT setup before reporting semantic delivery available
- **AND** the requesting Keyboard runtime SHALL observe only preparation pending, available, or a typed failure

#### Scenario: Multiple Keyboard instances require output
- **WHEN** two Keyboard channel instances require the Sleepwalker-backed text-output capability
- **THEN** the host SHALL share and serialize access to the host-owned connection
- **AND** it SHALL NOT start duplicate scans or GATT connections solely because the requests came from different instances
- **AND** neither runtime SHALL own or close the shared connection

#### Scenario: Sleepwalker disconnects during delivery
- **WHEN** the host observes that Sleepwalker disconnects during an accepted delivery request
- **THEN** the host SHALL terminate that request with one typed failed or indeterminate outcome according to whether partial delivery can be excluded
- **AND** the host SHALL perform operation and connection cleanup exactly once
- **AND** any reconnect decision SHALL remain host-owned
- **AND** the Keyboard runtime SHALL NOT reconnect or replay the text automatically

#### Scenario: Host service shuts down
- **WHEN** the service shuts down with connection or delivery work active
- **THEN** the host text-output capability SHALL cancel or terminate active operations
- **AND** SHALL perform terminal operation cleanup and connection cleanup exactly once
- **AND** SHALL close host-owned BLE/GATT resources
- **AND** no Keyboard runtime SHALL receive a late effect after its terminal outcome

### Requirement: Keyboard channel dispatch integration
PTT dispatch SHALL resolve the active catalogue instance to its registered Keyboard runtime and SHALL deliver generic press, release, and cancel input events under the same mutual-exclusion, commitment, and readiness rules as other channel runtimes. Dispatch SHALL use the stable instance ID and committed runtime lease; it SHALL NOT branch on a singleton Keyboard ID, call a `KeyboardPttController`, or look up a first Keyboard configuration.

#### Scenario: PTT pressed on active Keyboard instance
- **WHEN** a ready Keyboard channel instance is active and a PTT press is dispatched
- **THEN** the system SHALL resolve and commit that exact instance's runtime
- **AND** SHALL deliver the generic channel input lifecycle to that runtime
- **AND** SHALL use that instance's profile for later semantic text delivery

#### Scenario: Active channel changes during Keyboard PTT
- **WHEN** a PTT session is committed to one Keyboard instance
- **AND** the user activates another channel before release
- **THEN** terminal input SHALL remain bound to the originally committed runtime lease
- **AND** dispatch SHALL NOT redirect terminal text or status by implementation ID or current active selection
- **AND** the lease SHALL be released exactly once after terminal handling

#### Scenario: Keyboard instance deactivated without an active lease
- **WHEN** the user switches away from an idle Keyboard channel instance
- **THEN** the runtime registry SHALL reconcile that instance under the generic runtime lifecycle
- **AND** the system SHALL NOT call a Keyboard-specific controller cleanup path
- **AND** the host text-output capability SHALL retain or close shared hardware only according to host policy

### Requirement: Keyboard PTT recovers a disconnected Sleepwalker bridge
When an enabled and configured Keyboard channel instance is selected for PTT while semantic text output is unavailable but recoverable, the runtime SHALL request one bounded preparation operation from the host text-output capability before accepting or refusing input. The host SHALL own discovery, connection, joining, timeout, cancellation, stale-attempt cleanup, and reconnect policy. The runtime SHALL accept input only after the capability reports semantic delivery available, and the audio-input subsystem SHALL NOT start capture or play the ready beep while preparation is pending.

#### Scenario: Host preparation succeeds during PTT
- **WHEN** the user presses PTT with an enabled and configured Keyboard instance selected
- **AND** the host text-output capability is recoverably unavailable
- **AND** the bounded host preparation reaches semantic availability
- **THEN** the Keyboard runtime SHALL accept the input request
- **AND** the system SHALL continue through normal route preflight, ready beep, capture, transcription, and semantic text delivery

#### Scenario: Existing host preparation is joined
- **WHEN** a Keyboard PTT requests preparation while compatible host text-output preparation is already pending
- **THEN** the host SHALL join or serialize the request according to host policy
- **AND** SHALL NOT start a duplicate scan or GATT connection
- **AND** the Keyboard runtime SHALL observe only the shared semantic preparation result

#### Scenario: Host preparation fails
- **WHEN** bounded host text-output preparation terminates without reaching semantic availability
- **THEN** the host SHALL return a typed preparation failure
- **AND** the Keyboard runtime SHALL refuse the input request with that semantic reason
- **AND** the system SHALL NOT start capture or play the ready beep

#### Scenario: Host preparation times out
- **WHEN** host text-output preparation does not reach semantic availability within its bounded timeout
- **THEN** the host SHALL terminate and clean up the stale attempt exactly once
- **AND** the Keyboard runtime SHALL refuse the input request with a timeout reason
- **AND** the system SHALL NOT start capture or play the ready beep

#### Scenario: PTT ends while host preparation is pending
- **WHEN** PTT is released or cancelled before host text-output preparation completes
- **THEN** that PTT session SHALL remain terminal and uncommitted
- **AND** a later preparation result SHALL NOT start capture or play the ready beep for that PTT
- **AND** the runtime SHALL cancel only its semantic preparation request
- **AND** shared connection retention or teardown SHALL remain host-owned

#### Scenario: Text output is already available
- **WHEN** the user presses PTT with an enabled and configured Keyboard instance selected
- **AND** the host text-output capability already reports semantic delivery available for its profile
- **THEN** preparation SHALL complete without starting another hardware connection attempt
- **AND** the normal Keyboard PTT lifecycle SHALL proceed unchanged

## ADDED Requirements

### Requirement: Keyboard text delivery has typed non-replay outcomes
Each semantic Keyboard text-delivery request SHALL have a host-assigned operation identity and exactly one typed terminal outcome: `Delivered` when full delivery is confirmed, `Rejected` when delivery is proven not to have begun, `Failed` when delivery is proven to have begun but no text was delivered, or `Indeterminate` when cancellation, timeout, disconnect, acknowledgement loss, transport failure, or shutdown makes partial delivery ambiguous. A Keyboard runtime SHALL NOT automatically replay a request after `Delivered`, `Failed`, or `Indeterminate`. Any later user-initiated PTT cycle SHALL create a new request rather than retry the ambiguous operation.

#### Scenario: Full delivery is acknowledged
- **WHEN** the host confirms that the entire requested text reached the output
- **THEN** the host SHALL return `Delivered` exactly once for that operation identity
- **AND** the Keyboard runtime SHALL surface `Done` with the delivered text
- **AND** no automatic replay SHALL occur

#### Scenario: Delivery is rejected before effects begin
- **WHEN** validation, profile selection, or keymap compilation proves that delivery cannot begin
- **THEN** the host SHALL return `Rejected` with a semantic reason and proof that no delivery effect began
- **AND** the Keyboard runtime SHALL surface `Error`
- **AND** the original operation SHALL NOT be automatically replayed

#### Scenario: Failure proves no text was delivered
- **WHEN** the host began the delivery operation but can prove that no text reached the output
- **THEN** the host SHALL return `Failed` exactly once with a semantic reason
- **AND** the Keyboard runtime SHALL surface `Error`
- **AND** the runtime SHALL NOT automatically replay the original operation

#### Scenario: Partial delivery is ambiguous
- **WHEN** cancellation, timeout, disconnect, lost acknowledgement, transport failure, or shutdown prevents the host from proving how much text reached the output
- **THEN** the host SHALL return `Indeterminate` exactly once with an ambiguity reason
- **AND** the Keyboard runtime SHALL surface `Error` indicating uncertain delivery
- **AND** neither the runtime nor the host SHALL automatically replay any part of the original text
- **AND** host-owned terminal safety cleanup SHALL still be attempted exactly once
