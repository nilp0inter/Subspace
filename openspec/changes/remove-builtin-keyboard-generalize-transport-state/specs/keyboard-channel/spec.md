## REMOVED Requirements

### Requirement: Keyboard channel identity and configuration

**Reason:** The Kotlin `builtin:keyboard` provider and its provider-owned configuration are retired after the external Lua package proves the generic host contracts.

**Migration:** Preserve existing catalogue definitions through the missing-provider path. Users explicitly install the external package and create independently configured instances; the host performs no automatic conversion.

### Requirement: Keyboard channel readiness

**Reason:** Readiness belongs to the external package and the generic host capability rather than a built-in Keyboard runtime.

**Migration:** External packages continue to evaluate readiness through the unchanged generic preparation and `keyboard.output` availability contracts.

### Requirement: Keyboard channel PTT lifecycle

**Reason:** The built-in Kotlin capture, transcription, and delivery lifecycle is removed.

**Migration:** The external Keyboard package owns sequencing over the unchanged generic input, transcription, and keyboard-output APIs.

### Requirement: HID safety discipline

**Reason:** The built-in channel requirement is retired; equivalent host-owned output safety is already specified by the generic channel host capability.

**Migration:** Retain host-owned compilation, serialization, acknowledgement, timeout, cancellation, force-release, disarm, and cleanup behavior under `channel-host-capabilities`.

### Requirement: Text rendering failure handling

**Reason:** The built-in channel requirement is retired; generic keyboard-output typed outcomes define rendering rejection independently of channel identity.

**Migration:** External clients receive the unchanged typed rejection from `keyboard.output` without automatic replay.

### Requirement: Keyboard channel status reporting

**Reason:** Built-in instance-specific status transitions disappear with the built-in runtime.

**Migration:** External instances report their status through the generic Lua runtime and catalogue projection contracts; host transport state remains implementation-neutral and content-free.

### Requirement: Sleepwalker BLE bridge connection management

**Reason:** The built-in channel requirement is retired; Sleepwalker connection ownership is a host output-facility concern.

**Migration:** Keep the host-owned shared Sleepwalker transport, preparation, serialization, and cleanup implementation available through the generic keyboard-output capability.

### Requirement: Keyboard channel dispatch integration

**Reason:** There is no built-in Keyboard runtime to resolve or dispatch after provider removal.

**Migration:** Generic PTT dispatch continues to address installed external runtime instances by stable catalogue instance ID and committed generation lease.

### Requirement: Keyboard PTT recovers a disconnected Sleepwalker bridge

**Reason:** The built-in recovery path is removed with the built-in runtime.

**Migration:** Readiness-declared external packages continue to request the unchanged bounded generic capability preparation before input acceptance.

### Requirement: Keyboard text delivery has typed non-replay outcomes

**Reason:** The built-in channel requirement is retired; the generic keyboard-output host capability owns the same typed terminal outcome contract.

**Migration:** External clients continue to receive exactly one `Delivered`, `Rejected`, `Failed`, or `Indeterminate` outcome and remain responsible for policy after ambiguous completion.
