## MODIFIED Requirements

### Requirement: Dashboard shows channels
The system SHALL render one host-owned functional card per channel instance in the authoritative catalogue order, including instances whose implementation provider is absent, incompatible, or failed to load. Each card SHALL retain the instance's stable ID and display name and SHALL present its readiness, execution status, active state, and provider-defined presentation metadata when that metadata is available. The host SHALL interpret descriptor metadata into native Android UI and SHALL NOT allow a provider, configuration payload, or script to supply or control Android views, composables, navigation, or other platform UI objects. Functional cards for available instances SHALL remain mutually exclusive activation zones and phone-side PTT zones. Dedicated configuration and catalogue-management controls SHALL remain outside the phone PTT gesture surface.

The channel panel SHALL discover creatable implementations and their labels, summaries, default configuration, configuration schema, and presentation metadata from registered provider descriptors rather than from a closed built-in-kind list. The host SHALL render native configuration forms from descriptor schemas, validate submitted values against the provider-owned schema before committing them, and address creation and configuration by implementation ID and instance ID. The panel SHALL provide access to add supported provider-backed instances, rename instances, reorder instances, and remove instances subject to catalogue invariants. An unavailable instance SHALL remain visible at its catalogue position with a host-rendered reason and an actionable recovery, reconfiguration, or removal affordance; it SHALL NOT become a script-controlled UI surface or be silently dropped. The dashboard SHALL NOT render nonfunctional mock channel cards as catalogue entries.

#### Scenario: Ordered catalogue renders
- **WHEN** the dashboard receives an ordered runtime snapshot containing available and unavailable channel instances
- **THEN** it SHALL render exactly one host-owned card per catalogue instance
- **AND** each card SHALL retain the instance's stable ID
- **AND** card order SHALL match the catalogue order

#### Scenario: Descriptor metadata drives available presentation
- **WHEN** a catalogue instance resolves to a registered provider descriptor
- **THEN** the dashboard SHALL derive the card's provider label, summary, and presentation metadata from that descriptor
- **AND** the Android host SHALL render those values through native dashboard components
- **AND** the provider SHALL NOT supply an Android view, composable, navigation destination, or executable UI callback

#### Scenario: Channel selected for activation
- **WHEN** the user taps the main surface area of any persisted functional channel card
- **THEN** the system SHALL set that card's stable instance ID as the single active channel regardless of preparation state

#### Scenario: Ready channel long-pressed for PTT
- **WHEN** the user long-presses the main surface area of a ready functional channel card
- **THEN** the system SHALL set that card's instance ID as active
- **AND** start a phone-originated PTT session for that instance
- **AND** show held-recording feedback on that card

#### Scenario: Held phone PTT shows lock direction
- **WHEN** a phone-originated PTT session is active from a functional channel card and is not locked
- **THEN** the dashboard SHALL show a lock instruction on that card
- **AND** the instruction SHALL point inward from the initial press side

#### Scenario: Locked phone PTT shows stop affordance
- **WHEN** a phone-originated PTT session from a functional channel card has been slide-locked
- **THEN** the dashboard SHALL show that the PTT session is locked on that card
- **AND** it SHALL show an explicit stop affordance

#### Scenario: Unready instance remains selectable
- **WHEN** a catalogue instance lacks valid configuration or a required live dependency
- **THEN** its card SHALL remain visible and selectable at its catalogue position with its stable ID and display name
- **AND** it SHALL display its not-ready state and host-rendered configuration or recovery access
- **AND** long-pressing its main surface SHALL set it active and attempt phone PTT admission
- **AND** non-recoverable unavailability SHALL produce host-owned problem feedback without capture
- **AND** a selected unready card SHALL use the same active color and `ACTIVE` status language as a selected ready card
- **AND** an unselected unready card SHALL use the same standby color language as other unselected cards while retaining its unavailable diagnostic

#### Scenario: Provider is absent or incompatible
- **WHEN** a catalogue instance references an implementation provider that is absent or incompatible
- **THEN** its card SHALL remain visible and selectable at its catalogue position with its stable ID and display name
- **AND** the card SHALL identify the implementation as unavailable without exposing opaque configuration contents
- **AND** the card SHALL provide host-owned recovery or removal actions
- **AND** PTT SHALL produce host-owned problem feedback rather than executing provider code or starting capture

#### Scenario: Provider fails to load
- **WHEN** a registered provider fails while its descriptor or runtime is loaded
- **THEN** the affected instance card SHALL remain visible at its catalogue position
- **AND** the host SHALL present an actionable unavailable state without rendering provider-supplied UI
- **AND** unaffected instance cards SHALL remain usable

#### Scenario: Channel configured but inactive
- **WHEN** a ready instance is not the active channel
- **THEN** its card SHALL display a visually inactive Ready or Standby state

#### Scenario: Channel card opens descriptor-driven configuration
- **WHEN** the user activates the dedicated configuration control on an instance whose provider descriptor is available
- **THEN** the system SHALL open a host-rendered native configuration form for that instance ID using the descriptor's configuration schema
- **AND** it SHALL preserve the active selection
- **AND** it SHALL NOT start, lock, stop, or release phone PTT
- **AND** it SHALL NOT execute provider-controlled Android UI

#### Scenario: Invalid configuration submission
- **WHEN** the user submits values that fail the provider descriptor's configuration schema
- **THEN** the host SHALL reject the submission without committing catalogue changes
- **AND** the native form SHALL identify the invalid fields or schema-level error

#### Scenario: Add channel instance
- **WHEN** the user chooses a registered creatable provider descriptor and submits valid initial configuration through the host-rendered form
- **THEN** the dashboard SHALL request creation of a new catalogue instance using that descriptor's stable implementation ID and validated configuration
- **AND** the new card SHALL appear at the resulting catalogue position

#### Scenario: Every registered creatable provider remains reachable
- **WHEN** the catalogue-management form is rendered at the minimum supported phone width
- **THEN** the creation control for every registered creatable production provider descriptor SHALL remain visible and actionable
- **AND** the presence or absence of an existing instance for that implementation SHALL NOT hide or disable creation

#### Scenario: Reorder channel instance
- **WHEN** the user moves an instance through the catalogue-management surface
- **THEN** the dashboard SHALL render the committed order
- **AND** the active instance SHALL remain active
- **AND** availability SHALL NOT change the instance's stable ID or catalogue position

#### Scenario: Remove channel instance
- **WHEN** the user confirms removal of a removable instance
- **THEN** the dashboard SHALL remove its card after the catalogue mutation commits
- **AND** it SHALL render any active-selection repair from the same committed snapshot

#### Scenario: Final channel cannot be removed
- **WHEN** the catalogue contains one instance
- **THEN** the dashboard SHALL prevent or reject removal of that final instance

#### Scenario: No mock catalogue entries
- **WHEN** the dashboard renders the channel panel
- **THEN** every channel card SHALL correspond to a persisted catalogue instance
- **AND** the dashboard SHALL NOT render the Command Uplink preview as a channel card
