## ADDED Requirements

### Requirement: Runtime injects the reserved keyboard-output module
The runtime SHALL reserve and inject `subspace.keyboard_output` beside the existing host modules for every Lua package. The module SHALL have exactly the functions defined by `lua-keyboard-output-api`; requiring it SHALL grant no capability. Package source SHALL NOT shadow the module, and any callable invocation during entry-module or lazy-module evaluation SHALL trigger the existing effect-call-during-load failure before operation admission.

#### Scenario: Package requires keyboard output
- **WHEN** loaded callback code calls `require("subspace.keyboard_output")`
- **THEN** the runtime SHALL return the host-injected module table without consulting package source
- **AND** the module SHALL not acquire or prepare a capability merely because it was required

#### Scenario: Source shadows keyboard-output module
- **WHEN** a package source map contains `subspace.keyboard_output`
- **THEN** source-map validation SHALL reject the complete image before state creation

#### Scenario: Module function is called during load
- **WHEN** entry or lazy-module evaluation invokes `send_text` or `send_key`
- **THEN** the effect-call-during-load guard SHALL fail the complete module evaluation
- **AND** no text payload, host request, queue entry, capability acquisition, preparation, or physical output SHALL be created

### Requirement: Keyboard-output operations use managed execution ownership
After loading, `subspace.keyboard_output` SHALL authorize calls only from the current host-managed input owner, SOS owner, or managed-task owner. A valid call SHALL create one typed opaque host-operation request, suspend the owner, release the serialized Lua slot, and resume at most once with the normalized terminal result while that owner and generation remain live. Selection changes SHALL NOT invalidate the owner. Ineligible contexts SHALL receive `E_INVALID_CONTEXT` before suspension or effect.

#### Scenario: Managed task yields for keyboard output
- **WHEN** a live runtime-managed task invokes a valid declared keyboard-output operation
- **THEN** the runtime SHALL suspend that task without retaining a native execution thread
- **AND** other ready actor work MAY run before the operation terminally resumes the owner

#### Scenario: Channel is deselected while task is suspended
- **WHEN** another channel becomes selected while a managed task awaits keyboard output
- **THEN** the original task and operation SHALL remain generation-authorized
- **AND** completion SHALL not enter or mutate the selected channel's actor

#### Scenario: Generation closes while owner is suspended
- **WHEN** the owning generation closes or is replaced before terminal completion
- **THEN** the runtime SHALL revoke the request and discard the suspended execution without re-entering predecessor Lua
- **AND** later host completion SHALL be stale
