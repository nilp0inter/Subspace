## ADDED Requirements

### Requirement: Lua keyboard output adapts the existing host-owned semantic facility
The host SHALL expose public `keyboard.output` through an instance- and generation-scoped semantic keyboard-output capability. The adapter SHALL accept only bounded text or supported semantic keys plus a logical profile ID and SHALL own current-profile validation, keymap compilation, shared admission, Sleepwalker discovery and preparation, BLE/GATT/HID transport, acknowledgement, cancellation, timeout, serialization, force release, disarm, and cleanup. Lua runtimes SHALL NOT receive or control any underlying profile object, keymap, connection, transport, hardware address, frame, acknowledgement token, or Android object.

#### Scenario: Lua submits semantic keyboard text
- **WHEN** an authorized Lua owner submits text and a logical profile through `keyboard.output`
- **THEN** the host adapter SHALL revalidate the profile and perform compilation and transport internally
- **AND** Lua SHALL observe only a normalized typed terminal result

#### Scenario: Stable profile ID normalizes imported metadata casing
- **WHEN** a public lowercase profile ID selects a keymap whose imported platform or layout metadata retains different casing
- **THEN** the host SHALL resolve the canonical current database profile by its stable key before compilation
- **AND** it SHALL NOT reject the profile because object equality preserves the imported casing

#### Scenario: Built-in and Lua instances share transport
- **WHEN** built-in and Lua runtimes require keyboard output concurrently
- **THEN** the host SHALL serialize them through one shared transport policy
- **AND** neither runtime SHALL start a duplicate connection, own transport lifetime, or close a sibling's resource

### Requirement: Host capability preparation is generic and readiness directed
The host SHALL maintain a bounded registry mapping public capability identifiers to optional host-owned preparers. A readiness projection MAY request preparation only for a capability declared by its package and registered as preparable. Preparation SHALL be bound to the input attempt, instance, and current generation and SHALL own joining, serialization, deadline, cancellation, cleanup, and stale-completion handling. Capability declaration alone SHALL NOT invoke preparation, and Lua SHALL NOT receive a preparation callable or transport object.

#### Scenario: Readiness requests registered keyboard preparation
- **WHEN** a live runtime's cached readiness requests declared recoverable `keyboard.output`
- **THEN** the host SHALL invoke its registered bounded preparer once or join compatible in-flight preparation
- **AND** it SHALL not expose Sleepwalker scan, GATT, or connection state to Lua

#### Scenario: Readiness requests undeclared or non-preparable capability
- **WHEN** readiness names a capability that is undeclared or lacks a registered preparer
- **THEN** the host SHALL invalidate that readiness result before preparation
- **AND** it SHALL perform no capability or platform effect

#### Scenario: Input attempt ends during preparation
- **WHEN** release, cancellation, replacement, or shutdown terminates the input attempt while preparation is pending
- **THEN** that attempt SHALL remain terminal and uncommitted
- **AND** a later preparation completion SHALL not accept input, play a ready beep, or start capture
- **AND** shared connection retention or cleanup SHALL remain host-owned

### Requirement: Keyboard-output authority is generation scoped and selection independent
A declared keyboard-output lease and every operation derived from it SHALL be authorized by the live channel instance, runtime generation, and execution owner rather than current active selection. Deselecting an instance SHALL NOT revoke admitted or later managed-task output from its live generation. Disablement, configuration or revision replacement, removal, runtime failure/close, and service shutdown SHALL revoke the affected generation monotonically and SHALL not affect siblings.

#### Scenario: Live task outputs while unselected
- **WHEN** a live generation-owned task requests keyboard output while another channel is selected
- **THEN** the host SHALL evaluate the operation under the original generation's capability and policy
- **AND** it SHALL not redirect attribution, profile, outcome, or cleanup to the selected instance

#### Scenario: Generation is revoked with queued operations
- **WHEN** a generation is replaced or removed while its output is queued
- **THEN** the host SHALL reject queued not-yet-effective operations and revoke their leases idempotently
- **AND** it SHALL preserve unrelated instances and the shared adapter
