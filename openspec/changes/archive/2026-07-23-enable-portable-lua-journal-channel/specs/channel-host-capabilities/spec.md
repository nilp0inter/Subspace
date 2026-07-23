## MODIFIED Requirements

### Requirement: Persistent script state is exposed only through declared mounted storage
The host SHALL NOT provide ambient filesystem access, unrestricted paths, a persistent key-value/database service, package-writable source tree, package installer/verifier capability, or platform storage object. It SHALL provide only the language-neutral mounted-storage capability explicitly declared by a package and bound by a user-selected instance resource. The capability SHALL remain usable through generic host-domain requests without requiring Journal semantics and SHALL enforce mount authority, generation revocation, path confinement, bounds, and typed outcomes. Built-in Kotlin runtimes SHALL remain usable without constructing Lua or acquiring a package mount.

#### Scenario: Mounted storage platform is initialized
- **WHEN** the host initializes generic package storage
- **THEN** only declared and instance-bound mounts SHALL authorize persistent package writes
- **AND** no runtime SHALL gain ambient host or package-source filesystem access

#### Scenario: Built-in runtime operates normally
- **WHEN** a built-in Kotlin runtime uses existing capabilities
- **THEN** it SHALL function without a Lua actor, mount binding, or filesystem module

### Requirement: Public runtime I/O is restricted to capability-mounted files in this change
This change SHALL expose the capability-gated `subspace.fs` and `subspace.audio` file operations defined by their public specs while retaining existing semantic audio modules. It SHALL NOT expose HTTP, sockets, general networking, arbitrary event-loop integration, unrestricted Lua `io`/`os`, a generic key-value/database API, package source mutation, installer, verifier, or binary-string file API. Filesystem operations SHALL require declared `storage.files` eligibility and a live bound mount; audio-file operations SHALL additionally require `audio.files`. Existing built-in capabilities SHALL remain usable without Lua.

#### Scenario: Revised runtime modules are initialized
- **WHEN** a revised Lua provider starts with declared file capabilities and a live mount
- **THEN** only the bounded documented storage/audio-file operations SHALL be available
- **AND** no ambient networking, raw platform filesystem, or package-management operation SHALL appear

#### Scenario: Built-in runtime does not require public modules
- **WHEN** `builtin:journal` or another built-in runtime operates
- **THEN** it SHALL retain its current host capability path without constructing a Lua state
- **AND** the generic file APIs SHALL not replace or intercept its behavior

## ADDED Requirements

### Requirement: Mounted-storage capability uses language-neutral requests
The host SHALL expose generic operations for mount lookup/status, directory creation, stat, paginated listing, bounded UTF-8 read/write, and nonrecursive removal using host-domain mount identity, logical relative paths, bounded options, and typed outcomes. Contracts SHALL not contain Android URI/path/document/provider classes, Kotlin coroutine/UI types, iOS URL/bookmark types, file descriptors, or Journal values. Every operation SHALL be associated with instance and generation authorization and a revocable lease/binding.

#### Scenario: Lua adapter requests directory listing
- **WHEN** the language adapter submits a valid bounded listing request
- **THEN** the capability SHALL return a portable page or typed failure
- **AND** adapter code SHALL receive no platform provider object

### Requirement: Audio-file capability composes recording and mounted-storage authority
The host SHALL expose generic Recording describe/open/export operations using opaque Recording handles, mount authority, logical paths, exact format tokens, and typed outcomes. It SHALL own WAV decoding/writing, OGG/Vorbis encoding, app-private staging, provider streaming, quotas, cancellation, and cleanup. The capability SHALL not contain Journal entry, metadata, output-mode, Markdown, recovery, or path-layout logic.

#### Scenario: Package exports OGG to mount
- **WHEN** an authorized adapter requests OGG/Vorbis export of a live Recording
- **THEN** the capability SHALL encode and publish through the mounted-storage boundary or return one typed failure
- **AND** it SHALL not infer why the package requested the file

### Requirement: Public file capabilities remain portable to future language and platform adapters
Mounted-storage and audio-file ports SHALL use portable host-domain values, opaque handles, explicit lifecycle, and normalized outcomes. Android implementation SHALL use persisted SAF authority behind the port. A future non-Android adapter SHALL be able to implement the same contracts without Android classes or changes to package source. Platform-specific grant acquisition/release and document coordination SHALL remain adapter-owned.

#### Scenario: Future platform implements tree backend
- **WHEN** another platform maps a user-selected document tree to the host mount contract
- **THEN** existing Lua packages SHALL continue using the same mount IDs, relative paths, operations, results, and errors
