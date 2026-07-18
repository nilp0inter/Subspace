## Purpose

Defines the structure, validation constraints, manifest metadata, and integrity
checks for Lua channel packages in ZIP format.

## Requirements

### Requirement: Lua package format v1 is a strict source-only archive
A Lua channel package SHALL be one ZIP artifact containing exactly one root `manifest.json` regular file and one or more regular Lua source files below `lua/`. Every accepted source path SHALL have the form `lua/<segment>(/<segment>)*.lua`, where each segment matches `[a-z][a-z0-9_]*`; the host SHALL derive the module name by removing `lua/` and `.lua` and replacing `/` with `.`. The archive SHALL NOT contain bytecode, native libraries, assets, executable files, symlinks, hard-link metadata, encrypted entries, unsupported compression methods, non-regular entries, or unrecognized regular files. Package-format v1 SHALL accept only stored and deflated regular entries. Because portable ZIP regular-entry metadata does not encode a filesystem inode or link count, byte-identical regular entries SHALL be validated independently and SHALL NOT be classified as filesystem hard links without explicit link metadata. Validation SHALL reject the complete artifact rather than ignore an unexpected entry.

#### Scenario: Valid package maps files to modules
- **WHEN** an artifact contains `manifest.json`, `lua/main.lua`, and `lua/client/http.lua` with valid v1 content
- **THEN** the host SHALL derive the modules `main` and `client.http`
- **AND** it SHALL construct the immutable source map only after every archive entry validates

#### Scenario: Archive contains an unexpected file
- **WHEN** a package contains `assets/icon.png`, `README.md`, a native library, or another regular file outside the v1 manifest and Lua-source layout
- **THEN** the host SHALL reject the complete package with a typed unexpected-entry result
- **AND** it SHALL NOT ignore, extract, store as active, register, or execute the unexpected content

#### Scenario: Archive contains an unsupported entry type or compression feature
- **WHEN** a package contains a symbolic link, explicit hard-link metadata, another non-regular entry, an encrypted entry, an unsupported compression method, or metadata that cannot be accounted for safely
- **THEN** the host SHALL reject the complete package before activation
- **AND** it SHALL NOT follow the link, decrypt the entry, or attempt a permissive fallback

### Requirement: Package manifest declares one bounded provider revision
`manifest.json` SHALL be a UTF-8 JSON object with `manifestVersion` exactly `1`, a positive decimal-string `repositoryId`, a nonblank bounded `packageVersion`, a canonical `entryModule`, bounded nonblank `presentation.label` and `presentation.summary`, and exact `runtime.luaVersion` and `runtime.apiVersion` strings. Every field SHALL have the declared JSON type. V1 validation SHALL reject missing fields, duplicate JSON keys, unknown fields, blank values, invalid repository IDs, invalid entry-module names, and values exceeding host-configured finite bounds. A future manifest shape SHALL use another manifest version; the v1 host SHALL NOT infer forward compatibility from ignored metadata.

#### Scenario: Manifest declares all required v1 fields
- **WHEN** a manifest contains exactly the required v1 fields with valid types and bounded values
- **THEN** the host SHALL retain the declared package version, entry module, presentation, and runtime requirements in the validated package revision

#### Scenario: Manifest contains an unknown field
- **WHEN** a v1 manifest contains a field not defined by package-format v1
- **THEN** the host SHALL reject the complete package with a typed manifest result
- **AND** it SHALL NOT silently ignore the field or reinterpret it as configuration, capability, provenance, or compatibility metadata

#### Scenario: Manifest version is unsupported
- **WHEN** `manifestVersion` is absent or not exactly `1`
- **THEN** the host SHALL return a typed unsupported-format result
- **AND** it SHALL NOT attempt to parse the manifest as v1 or execute its source

### Requirement: Provider identity is bound to host-resolved durable repository identity
Every package-validation request SHALL include a host-resolved durable GitHub repository identity and exact release-asset source record. The manifest `repositoryId` SHALL match the resolved repository database ID exactly. The host SHALL derive the stable channel implementation ID as `github-repository:<repositoryId>` from the resolved identity. Mutable `owner/repository` coordinates, labels, summaries, package filenames, release tags, asset names, package versions, and artifact digests SHALL NOT establish or change provider identity. The `builtin:` and `internal:` namespaces and every host-reserved namespace SHALL remain unavailable to installed packages.

#### Scenario: Manifest identity matches resolved repository
- **WHEN** the manifest repository ID equals the host-resolved repository database ID
- **THEN** the host SHALL derive the installed provider ID from that durable repository ID
- **AND** repository coordinates SHALL remain presentation and link metadata only

#### Scenario: Manifest self-asserts another repository
- **WHEN** the manifest repository ID differs from the host-resolved expected repository identity
- **THEN** the host SHALL reject the complete package with a typed identity-mismatch result
- **AND** it SHALL NOT register the asserted provider or rewrite the manifest identity

#### Scenario: Repository coordinates change
- **WHEN** the same durable repository identity is later resolved under different owner or repository coordinates
- **THEN** the provider implementation ID SHALL remain unchanged
- **AND** existing channel instances SHALL continue to reference the same provider identity

### Requirement: Module names and paths are canonical and collision free
The package validator SHALL normalize and validate every archive name before reading it as package content. Absolute paths, `.` or `..` segments, empty or repeated segments, backslashes, trailing separators on regular files, percent-encoded separators, NUL bytes, noncanonical Unicode, uppercase module aliases, duplicate names, and case-folding collisions SHALL reject the complete artifact. `entryModule` SHALL match the canonical module grammar used by Lua Runtime v1 and SHALL resolve to exactly one included source file. The validator SHALL NOT extract package entries through their archive-supplied paths.

#### Scenario: Entry path attempts traversal
- **WHEN** an artifact contains `../main.lua`, `lua/../../main.lua`, an absolute path, or a backslash-based traversal spelling
- **THEN** the host SHALL reject the complete package before storing or reading that entry as source
- **AND** no file SHALL be created outside private staging

#### Scenario: Entries collide after canonical comparison
- **WHEN** two entries are duplicate names or differ only by a prohibited case or normalization variant
- **THEN** the host SHALL reject the complete package with a typed collision result
- **AND** it SHALL NOT choose one entry by ZIP order

#### Scenario: Entry module is missing
- **WHEN** the manifest declares `entryModule` as `main` but the artifact does not contain exactly one canonical `lua/main.lua`
- **THEN** validation SHALL fail before program-image construction
- **AND** no fallback entry module SHALL be selected

### Requirement: Package validation is bounded, static, and fail closed
Before an artifact can enter the immutable content store or installed-provider index, the host SHALL enforce finite host-configured bounds on exact compressed artifact bytes, archive-entry count, manifest bytes, path bytes, per-module uncompressed source bytes, total uncompressed source bytes, and compression expansion. It SHALL decode manifest and source as strict UTF-8, reject Lua binary-chunk signatures, and pass the derived entry module, source map, and exact Lua/API requirements through the existing `ImmutableProgramImage` validation. Package validation SHALL NOT execute Lua, create a Lua state or actor, call a plugin callback, acquire a channel capability, mutate the provider registry, mutate the channel catalogue, or perform network access.

#### Scenario: Package exceeds an archive bound
- **WHEN** compressed bytes, entry count, one expanded source, total expanded source, path length, or expansion ratio exceeds its configured bound
- **THEN** validation SHALL terminate with a typed bounds result
- **AND** it SHALL NOT retain a partial source map, active content, or growing overflow buffer

#### Scenario: Source is invalid UTF-8 or bytecode
- **WHEN** a Lua entry contains invalid UTF-8 or begins with a Lua binary-chunk signature
- **THEN** the host SHALL reject the complete artifact before state creation
- **AND** it SHALL NOT coerce text, load bytecode, or continue with other modules

#### Scenario: Validation receives adversarial but syntactically valid Lua
- **WHEN** package source contains code that would loop, allocate, log, spawn, sleep, or request another host effect if executed
- **THEN** static package validation SHALL complete without executing that source
- **AND** no Lua state, actor, timer, log, callback, or host effect SHALL be observed

### Requirement: Exact artifact digest provides integrity but not publisher authentication
The host SHALL compute SHA-256 over the exact artifact bytes while staging and SHALL use the lowercase digest as immutable content identity. A stored revision SHALL retain the exact digest and exact host-resolved repository, release, and asset source record. Materialization after process start SHALL revalidate the stored bytes against the committed digest before constructing a provider. Digest validation SHALL detect corruption or substitution relative to the installed index, but the host SHALL NOT describe digest-only content as publisher-signed, publisher-verified, reviewed, endorsed, or authenticated.

#### Scenario: Exact artifact is staged successfully
- **WHEN** the complete staged artifact passes all validation
- **THEN** the host SHALL record the SHA-256 of those exact bytes as the revision fingerprint
- **AND** any identical artifact SHALL produce the same fingerprint

#### Scenario: Stored content no longer matches its digest
- **WHEN** committed content bytes differ from the digest referenced by the installed index
- **THEN** the host SHALL refuse to materialize or execute that revision
- **AND** it SHALL report typed corruption without silently choosing another revision

#### Scenario: Digest is presented in diagnostics
- **WHEN** the host reports package integrity or revision information
- **THEN** it MAY report a bounded digest or digest prefix as content identity
- **AND** it SHALL NOT claim that SHA-256 alone authenticates the package publisher
