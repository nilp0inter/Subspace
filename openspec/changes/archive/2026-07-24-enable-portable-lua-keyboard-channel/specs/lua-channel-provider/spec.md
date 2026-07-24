## ADDED Requirements

### Requirement: Lua provider compiles dynamic choices and keyboard-output eligibility generically
The installed-package materializer SHALL compile every validated `dynamic-choice` UI declaration into generic host-choice field metadata retaining only its public source identifier and scalar dependency metadata. It SHALL compile public capability `keyboard.output` into the existing semantic host keyboard-output eligibility required by the provider descriptor. Compilation SHALL be deterministic, bounded, and side-effect free and SHALL NOT execute Lua, resolve current choices, construct a runtime, open a transport, prepare a capability, or branch on package identity or channel name.

#### Scenario: Keyboard-output package is materialized
- **WHEN** a validated package declares dependent `keyboard-output-platforms`, `keyboard-output-layouts`, and `keyboard-output-profiles` choices plus capability `keyboard.output`
- **THEN** the provider SHALL expose all three generic dynamic fields with their scalar dependencies and semantic capability eligibility
- **AND** materialization SHALL create no actor, Lua state, profile object, keymap, connection, or output operation

#### Scenario: Unrelated package uses the same public contracts
- **WHEN** another repository-derived package declares the same dynamic source or capability
- **THEN** the materializer SHALL compile it through the identical generic path
- **AND** it SHALL NOT require an official owner, Keyboard label, known repository ID, or built-in implementation ID

### Requirement: Lua runtime descriptor exposes recoverable preparation without package special cases
A Lua provider descriptor SHALL indicate recoverable input preparation whenever its validated package can return readiness-declared preparation for at least one declared host-preparable public capability. The runtime SHALL map requested public IDs through a generic preparer registry at input time. Provider construction SHALL NOT eagerly prepare capabilities, and a capability declaration alone SHALL NOT force preparation when Lua readiness did not request it.

#### Scenario: Package declares preparable keyboard output
- **WHEN** a package declares `keyboard.output`
- **THEN** its materialized runtime SHALL be eligible to request generic recoverable preparation for that capability
- **AND** provider inspection and registration SHALL perform no preparation

#### Scenario: Readiness does not request declared capability
- **WHEN** a package declares a preparable capability but its cached readiness result omits that capability from `prepare`
- **THEN** input preparation SHALL NOT invoke that capability's preparer solely because it was declared
