## Why

The installed Lua package substrate now validates, stores, registers, and executes exact package revisions, but no production action can install one and accepted `subspace.log` records remain private to the runtime. The first externally published Lua v1 channel should close that user-visible path before any further runtime, configuration, or compatibility infrastructure is added.

## What Changes

- Add a minimal repository-driven installation flow for public GitHub repositories: resolve durable repository identity, enumerate published stable releases, select an exact compatible package asset, display publisher trust, download it, and invoke the existing atomic installed-package repository.
- Add an installed-package management surface for installation state, active release metadata, update, explicit rollback, removal, and actionable typed failures without exposing package-store paths or executing source during inspection.
- Publish accepted Lua structured logs through the existing bounded persistent observability pipeline and Log Analysis view while preserving per-instance attribution, rate limiting, redaction, and payload bounds.
- Define and publish the first external Lua Runtime v1 Diagnostics Channel package. It uses only the existing v1 lifecycle, readiness, timer, SOS, and structured-log contracts and has empty configuration and no host capabilities.
- Demonstrate the complete production path on the supported device: repository URL to exact release installation, provider discovery, instance creation, background heartbeat, SOS diagnostic, revision update, explicit rollback, removal, and restart recovery.
- Keep the current Lua Runtime v1 and package-format v1 contracts. No API v2, compatibility-range system, new Lua module, or configuration expansion is introduced.

## Capabilities

### New Capabilities

- `github-package-installation`: Repository URL resolution, compatible stable-release and canonical asset selection, trust presentation, bounded download, installed-package management, and failure behavior for public GitHub Lua channel packages.
- `diagnostics-channel`: Behavior, package identity, lifecycle, diagnostics, publication, and end-to-end acceptance contract for the first external Lua v1 Diagnostics Channel.

### Modified Capabilities

- `lua-channel-provider`: Accepted `subspace.log` records become host-observable through the existing logging boundary without changing the Lua call shape or success/error semantics.
- `observability-logs`: Plugin-originated structured records are persisted, streamed, filtered, attributed, and redacted alongside core logs under bounded host policy.

## Impact

- Android UI and service composition: repository input, release selection, trust warning, installed-package state/actions, and calls to `InstalledPackagesFacade`.
- GitHub integration: public repository/release/asset metadata and bounded exact-asset download; no mutable branch execution and no authenticated credential storage.
- Lua runtime observability: `LuaAdapterRuntime` log draining into `SubspaceLogger` with semantic plugin attribution and bounded serialization.
- External package publication: one official Diagnostics Channel repository and source-only v1 release artifact using the existing strict `manifest.json` and `lua/*.lua` format.
- Verification: focused GitHub-client, selection, trust, UI-state, log-projection, package-lifecycle, JVM, and physical-device tests plus exact published-artifact provenance.
- No channel configuration, credentials, HTTP/filesystem Lua APIs, marketplace/topic discovery, QR scanning, automatic updates, package signing/attestation, official Kotlin-channel migration, SDK, permission, audio, PTT routing, or release-signing change is intended.
