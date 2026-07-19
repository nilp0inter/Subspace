## Context

The completed package stack already owns the hard execution boundary:

```text
exact package bytes
    → strict static package validation
    → immutable digest-addressed content
    → atomic active/rollback index
    → package-specific installed provider
    → catalogue reconciliation
    → one Lua actor per runtime generation
```

`InstalledPackagesCoordinator` starts in `PttForegroundService`, publishes installed providers, and exposes `InstalledPackagesFacade.installOrUpdate`, `rollback`, and `remove`. No production caller invokes those mutations. The existing generic catalogue management UI already creates an instance from any published provider descriptor, so package management should stop at provider installation and reuse that path.

Lua Runtime v1 already accepts structured `subspace.log` records, enriches them with instance/generation/timestamp/level, and keeps them in `LuaAdapterRuntime.logSnapshot`. Those records are not forwarded to `SubspaceLogger`, so they do not reach Logcat, rotating persistence, the reactive `StateFlow`, or `LogAnalysisScreen`.

This change is the first externally published demonstration of the existing v1 contracts. It deliberately does not expand the Lua API. The Diagnostics Channel is a separate GitHub repository and provider package, not a built-in, bundled asset, mutable source loader, or app-repository package.

The GitHub REST API supports the required anonymous public-data flow: repository resolution through `GET /repos/{owner}/{repo}`, release enumeration through `GET /repos/{owner}/{repo}/releases`, asset enumeration through `GET /repos/{owner}/{repo}/releases/{release_id}/assets`, and binary download through `GET /repos/{owner}/{repo}/releases/assets/{asset_id}` with `Accept: application/octet-stream`. The asset endpoint may stream `200` or redirect with `302`. Anonymous requests are IP-associated and currently limited by GitHub to 60 requests per hour, so rate exhaustion is a normal typed state rather than an exceptional retry loop.

## Goals / Non-Goals

**Goals:**

- Let a user install a public package-format-v1 provider from a canonical GitHub repository URL.
- Resolve and preserve immutable repository, current owner, release, and asset identities before package validation.
- Present only bounded, statically inspected compatible stable release assets.
- Distinguish Official from Community — Unreviewed by a pinned immutable publisher identity and require the appropriate trust acknowledgement.
- Reuse the existing installed-package transactions for install, update, rollback, removal, recovery, provider publication, and runtime replacement.
- Add one minimal package-management route without duplicating catalogue instance management.
- Project accepted Lua structured logs into existing bounded host observability without changing `subspace.log` arguments or return values.
- Publish two exact stable releases of an external official Diagnostics Channel and prove install, runtime behavior, update, rollback, removal, reinstall, and restart on the supported device.
- Keep old-generation diagnostics attributed to the old generation and suppress every late effect that did not cross the live publication boundary.

**Non-Goals:**

- No Lua API v2, compatibility ranges, optional module negotiation, package-format revision, configuration, credentials, host capabilities, filesystem, HTTP, JSON, native modules, or official Kotlin-channel migration.
- No website catalogue, topic discovery, repository search, ranking, review workflow, QR scanning, Android App Links, version-specific links, automatic update, background refresh, or prerelease installation.
- No private repositories, stored GitHub token, OAuth, GitHub App, cookies, or authenticated rate-limit expansion.
- No branch, tag archive, source archive, workflow artifact, arbitrary URL, local file, loose Lua, or bytecode installation.
- No package signature, Sigstore, attestation, transparency log, publisher-audit claim, or claim that SHA-256 authenticates a publisher.
- No diagnostics-specific provider registration, package validator, runtime adapter, catalogue mutation, log screen, or package-store path.
- No automatic Diagnostics Channel instance creation or active-channel selection.
- No guarantee of execution after the existing foreground service or Android process stops.

## Decisions

### D1. Complete the unpublished v1 path; do not introduce another API version

The Diagnostics Channel uses only the existing `subspace-lua-v1` surface:

```text
startup
handle_lifecycle
handle_readiness
handle_input
handle_sos
subspace.runtime.spawn
subspace.runtime.sleep
subspace.log
```

The package has empty schema-version-1 configuration and no capabilities. It does not create a reason to change runtime compatibility, callback shapes, module injection, or package-format v1. Any later pre-publication correction to v1 will be a direct clean cutover, not a v1/v2 compatibility layer created by this change.

**Alternative considered:** add configuration or a new output module so the first plugin is more useful. Rejected because it resumes horizontal API work instead of proving installation and external execution.

### D2. Accept only canonical repository URLs and resolve identity through GitHub

The input parser accepts exactly an HTTPS `github.com/<owner>/<repository>` repository URL after URI parsing and canonical normalization. It rejects credentials, fragments, query strings, ports, extra path segments, `.git` ambiguity, release pages, branch paths, files, and non-GitHub hosts. Owner and repository segments are sent only as encoded REST path parameters.

A small host-domain `GitHubPackageSourceClient` uses the existing OkHttp dependency with:

```text
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2022-11-28
User-Agent: Subspace/<app-version>
```

It decodes only bounded required fields:

```text
repository: id, full_name, archived, disabled, visibility,
            owner.id, owner.login, owner.type
release:    id, tag_name, name, draft, prerelease, published_at
asset:      id, name, state, content_type, size, browser_download_url
```

Repository database ID remains the provider identity authority. `full_name` and login are observed coordinates only. A rename or transfer that preserves repository ID preserves provider identity; coordinate reuse with another ID cannot update the old provider.

No GitHub SDK or authenticated client is introduced. Network and JSON failures map into a sealed source-domain outcome rather than raw exceptions or HTTP response objects.

**Alternative considered:** trust `owner/repository` or the package manifest. Rejected because names are mutable and self-asserted manifest values do not prove repository identity.

### D3. Use one canonical stable release asset and no version-string ordering

The initial distribution convention is:

```text
published GitHub release
    draft      = false
    prerelease = false

release asset
    name       = subspace-channel.zip
    state      = uploaded
    size       within host package bounds
```

A release with zero or more than one canonical asset is ineligible. Other attachments are ignored rather than interpreted. Repository source archives and tags without GitHub releases are never candidates.

The client requests a bounded page of releases and orders candidates by non-null `published_at` descending, then positive release ID descending. It does not parse or compare tag names or manifest `packageVersion`. Candidate assets are inspected under a bounded concurrency of one to control memory, disk, request rate, and failure ordering. Compatibility failures exclude that release. Malformed, identity-mismatched, or hostile artifacts are shown as typed ineligible outcomes and never installed.

**Alternative considered:** accept any `.zip` asset or choose by content type. Rejected because multiple attachments create ambiguous and spoofable selection behavior.

**Alternative considered:** use GitHub's `/releases/latest` endpoint. Rejected because the newest published release may not contain a compatible package while an older stable release does.

### D4. Inspect and install the same exact staged bytes

Static inspection needs the manifest and source map, but redownloading after trust confirmation creates a time-of-check/time-of-use gap because a release asset can be replaced remotely. The source client therefore owns a bounded app-private inspection directory:

```text
GitHub metadata
    → exact asset download to private inspection staging
    → streaming size bound
    → PackageValidator.validate(staged bytes, resolved source record)
    → compatible candidate {metadata, digest, staged-file token}
    → user selection and trust confirmation
    → reopen the same private staged file
    → InstalledPackagesFacade.installOrUpdate
    → existing repository rehashes and revalidates
    → delete inspection file
```

The staged-file token is host-owned, generation-bound to the current inspection session, and never persisted in UI state, `ChannelDefinition`, package metadata, or Lua. Cancellation, leaving the route, a newer inspection, service shutdown, install completion, or failure closes the stream and deletes uncommitted inspection files. Startup performs bounded orphan inspection cleanup. The installed-package repository remains the only authority that can commit package content or index state.

**Alternative considered:** validate one download and install a second. Rejected because asset ID/name alone does not guarantee that remote bytes remained unchanged.

**Alternative considered:** move inspection files directly into the installed content store. Rejected because it bypasses the repository transaction and duplicates content/index ownership.

### D5. Follow only bounded HTTPS GitHub asset redirects

Repository/release JSON uses `api.github.com`. Exact asset bytes use `GET /repos/{owner}/{repo}/releases/assets/{asset_id}` with `Accept: application/octet-stream`. The client handles either direct `200` bytes or a bounded `302` chain. Every hop must remain HTTPS; no authorization header exists to forward; loops, missing locations, protocol downgrade, excess redirects, and responses above the configured byte limit fail closed. The client does not download from a plugin-supplied URL or arbitrary `browser_download_url` value when the asset API identity is available.

HTTP `403` is classified using response and rate-limit headers so exhausted anonymous quota is distinguishable from inaccessible/forbidden source. `x-ratelimit-limit`, `x-ratelimit-remaining`, and `x-ratelimit-reset` are parsed only into bounded host-domain metadata. There is no automatic retry loop. A user may retry manually after the displayed reset time.

**Alternative considered:** store a personal access token to obtain a larger quota. Rejected because credential storage, account identity, revocation, and private-repository access are unrelated to proving the public v1 path.

### D6. Trust tier is a host policy over immutable owner identity

The host configuration contains one pinned positive GitHub publisher account ID for the project owner that publishes official packages. `Official` requires an exact equality match with the resolved repository owner's numeric ID. Owner login, repository name, manifest presentation, topics, asset name, and digest cannot confer Official status. If the pin is absent, malformed, or does not match, the repository is Community — Unreviewed; the host never fails open to Official.

Source-verified production identity: GitHub account `nilp0inter`, immutable database ID `1224006`.

The confirmation model includes:

```text
canonical repository
publisher tier
package label and summary
release tag and publication time
asset name and size
artifact digest after inspection
```

Community confirmation includes the vision's trusted-code warning and requires an explicit current acknowledgement for every install or update. Official confirmation describes provenance but not review, audit, signing, or defect freedom. Rollback uses already retained bytes and requires mutation confirmation, not another network trust resolution. Removal explains preserved unavailable channel definitions.

The official Diagnostics Channel repository is `nilp0inter/diagnostics-channel`, created under pinned publisher ID `1224006`. Its source-verified durable repository database ID is `1305223892`; this exact value is written into its manifest before the first package release.

First stable publication provenance: tag `v1.0.0`; release ID
`356201284`; published `2026-07-18T21:53:22Z`; canonical uploaded asset
ID `481829091`; name `subspace-channel.zip`; size `3386` bytes; digest
`sha256:a1609ba59e3bac16dbcdf03532f9774848aaf18ec46137e6bda7cecc012c6b87`.
The release contains exactly one uploaded asset.

Second stable publication provenance: build argument `--version 1.1.0`; tag
`v1.1.0`; release ID `356201719`; published `2026-07-18T21:55:50Z`;
canonical uploaded asset ID `481830727`; name `subspace-channel.zip`; size
`3386` bytes; digest
`sha256:87652e947664ffd49c6086b18733861cbf3060bda4c7952e957f94f3ed73fab7`.
An anonymous GitHub API verification resolved owner ID `1224006`, repository
ID `1305223892`, and exactly one uploaded canonical asset on each stable,
non-draft, non-prerelease release.

**Alternative considered:** classify by login or organization text. Rejected because presentation names are mutable and can be confused or reused.

### D7. Package management is a host-rendered state machine, separate from catalogue management

A new `DashboardRoute.PackageManagement` is reachable from the existing management surface. Compose renders immutable host-domain state and sends intent methods; it owns no OkHttp call, stream, package file, repository transaction, or delayed mutation.

Logical states are:

```text
Idle
  → ResolvingRepository
  → LoadingReleases
  → InspectingCandidate(n/total)
  → AwaitingSelection
  → AwaitingTrust
  → Installing | Updating
  → Ready

any non-terminal state
  → Failed(typed source/package/lifecycle result)
  → Idle or explicit retry

Ready
  → Refreshing | RollingBack | Removing
```

One service-owned coordinator serializes source inspections and delegates committed mutations to `InstalledPackagesFacade`. A monotonic operation generation suppresses stale network/UI results. Package summaries combine committed installed-index metadata, installed-provider availability, and coordinator operation state; they do not expose content paths or mutable repository objects.

After installation, the user enters the existing catalogue management panel to create a channel instance. Package management neither creates nor selects instances. Removal delegates to the existing repository, which preserves definitions.

**Alternative considered:** add installation controls to every provider/channel card. Rejected because packages exist independently of instances and one package can back multiple instances.

### D8. Plugin logs use a dedicated bounded sink and cross a single publication boundary

`LuaAdapterRuntime` receives a provider-neutral `PluginLogSink` owned by service composition. `consumeNativeLogs` remains the only native-record decoder. For each valid, non-rate-dropped native record it:

1. validates the exact level/payload shape;
2. enriches the record with host instance, generation, and timestamp;
3. appends to the existing bounded runtime-local snapshot;
4. performs one non-blocking `tryPublish` to the plugin sink while the generation is live.

The sink has its own finite queue and message bound. Its worker canonicalizes the normalized map with deterministic key ordering and forwards this shape to `SubspaceLogger`:

```text
tag       = LuaChannel
level     = mapped host level
timestamp = accepted host timestamp
message   = canonical bounded JSON {
              "instance_id": ...,
              "generation": ...,
              "payload": ...
            }
throwable = null
```

Once `tryPublish` succeeds, the record may finish persistence after generation close but retains predecessor attribution. If close wins before `tryPublish`, the record is suppressed. Sink saturation returns immediately, increments bounded host loss accounting, and never re-enters Lua or changes the result already returned by `subspace.log`. Polling or repeatedly forwarding `logSnapshot` is prohibited because it would duplicate earlier records.

Existing global/per-tag filtering applies under the fixed `LuaChannel` tag. The plugin cannot choose another tag or host metadata. Payload normalization remains the security boundary; no source, archive, configuration, credentials, audio, transcript, hardware identity, native handle, or arbitrary throwable is added.

**Alternative considered:** call `SubspaceLogger.log` directly for every native record. Rejected because its persistence channel is not the plugin admission boundary and direct forwarding would not provide isolated bounded loss behavior.

### D9. Diagnostics Channel is a separate official package with no app special case

The companion repository is `nilp0inter/diagnostics-channel` under the pinned official publisher identity. It contains package source and a deterministic release packaging action that emits exactly `subspace-channel.zip`. The app repository does not vendor or bundle the package. Test fixtures may reproduce the same v1 callback behavior but are not provider identity or publication authority.

The package implements:

```text
startup
    log {event="startup", release_marker=<constant>}
    spawn heartbeat coroutine

handle_lifecycle ready
    log {event="ready", release_marker=<constant>}

handle_readiness
    return {ready=true}

heartbeat coroutine
    sequence += 1
    log {event="heartbeat", sequence=n, release_marker=<constant>}
    sleep fixed package-owned interval
    repeat

handle_input capture
    log {event="input", duration_ms, sample_rate_hz, channel_count,
         release_marker=<constant>}
    return {ok=true}

handle_sos
    log {event="sos", release_marker=<constant>}
```

It omits session ID, audio, transcript, device identity, and credentials. The heartbeat interval is a package implementation constant chosen to be observable without approaching the host rate limit; it is not user configuration or a public Subspace runtime guarantee. Sequence is volatile and restarts with every generation.

Two stable releases carry different bounded release markers. Update and rollback evidence asserts semantic record ordering and generation attribution, not exact implementation calls.

**Alternative considered:** bundle a debug-only package in the APK. Rejected because it would prove a special local source path rather than the external package system and would need later removal.

### D10. Verification is layered around observable contracts

Verification has five layers:

1. **Pure source-domain tests:** URL grammar, bounded JSON, identity/rename/transfer, release filtering/order, canonical asset selection, rate-limit classification, redirect policy, and typed failures using a fake transport.
2. **Inspection/transaction tests:** bounded streaming, exact staged-byte reuse, cleanup, cancellation, stale operation suppression, trust confirmation, install/update/rollback/remove delegation, and commit-before-publication recovery.
3. **Log projection tests:** exact-once publication, level mapping, canonical payload, fixed tag, filtering, invalid/rate-dropped/stale suppression, bounded saturation, no Lua blocking, persistence, and restart display.
4. **Package/runtime tests:** strict published artifact validation, independent instances, readiness, unselected heartbeats, metadata-only input, SOS, replacement ordering, fresh restart, and absence of special provider paths.
5. **Physical-device acceptance:** install the first published release from its repository URL, create/select an instance, observe filtered records, update to the second release, roll back, remove, reinstall, restart, and rerun the established RSM/SCO/background/disconnect regression flow.

Tests use transport fakes and immutable exact assets; they do not make ordinary JVM suites depend on live GitHub. One physical-device acceptance run uses the actual public GitHub endpoints and exact published assets.

Physical package-lifecycle verification exposed an existing Lua v1 policy defect: the production starting policy configured zero timer-completion slack, so a requested sleep timer and its operation deadline were scheduled for the same instant and could nondeterministically return `E_TIMEOUT`. The implementation uses the already-specified host-owned bounded-slack contract with a 100 ms starting-evidence margin. This is an internal policy correction, not a new Lua API or public timing guarantee.

## Risks / Trade-offs

- **[Risk] Anonymous GitHub quota is only 60 requests per originating IP per hour.** → Bound release inspection, avoid automatic refresh, surface reset metadata, and require manual retry; do not add credentials in this change.
- **[Risk] Inspecting multiple release assets consumes bandwidth and quota.** → Require one canonical asset, cap candidate count, inspect sequentially, reject oversized assets from metadata before download, and retain only bounded current inspection files.
- **[Risk] A release asset is replaced after inspection.** → Install from the exact private staged bytes already inspected and revalidate them transactionally; never redownload after confirmation.
- **[Risk] Redirect-host policy becomes stale if GitHub changes its asset CDN.** → Start from the GitHub asset API, require HTTPS, send no credentials, bound redirects, and treat policy failure as actionable rather than allowing arbitrary hosts.
- **[Risk] Official status is wrong after publisher account migration.** → Pin immutable numeric owner identity, fail closed to Community, and change the pin only through an explicit application release and trust-policy review.
- **[Risk] A malicious package floods persistent logs.** → Retain actor rate limits, add a bounded non-blocking plugin sink, retain host level filtering and rotating storage, and drop excess projections without Lua backpressure.
- **[Risk] Persistent diagnostic payloads expose user data supplied by a plugin.** → Normalize and bound payloads, use a conspicuously diagnostic package with metadata-only fields, prohibit host enrichment with sensitive data, and describe installed plugins as trusted code rather than a sandbox.
- **[Risk] The external repository and app change are released out of order.** → The package uses the already-implemented v1 contract; publish exact package releases before final live-device acceptance, while app release remains independently deployable with no package installed.
- **[Trade-off] The first visible plugin diagnoses the platform rather than delivering end-user channel value.** → Accepted. It proves publication, installation, lifecycle, observability, update, rollback, removal, and recovery before another API expansion.
- **[Trade-off] Only public stable GitHub releases and one asset name work initially.** → Accepted. This makes identity, trust, and artifact selection deterministic without introducing marketplace, credentials, prereleases, or signing.

## Migration Plan

1. Add source-domain identities, typed outcomes, strict repository URL parsing, bounded GitHub JSON decoding, transport abstraction, and fake-transport coverage without production UI.
2. Add bounded candidate inspection staging and exact-byte handoff into the existing installed-package facade; verify cancellation, cleanup, and transaction preservation.
3. Add the service-owned package-management coordinator, immutable UI state, navigation route, trust confirmation, and installed revision actions.
4. Add the bounded plugin log sink and wire package-specific Lua providers to unified observability without changing Lua v1.
5. Create `nilp0inter/diagnostics-channel`, resolve and pin its repository identity in its manifest, publish two stable exact `subspace-channel.zip` assets with distinct release markers, and retain their provenance.
6. Run focused automated verification and the complete live GitHub/device flow, then build debug and release-equivalent APKs and validate that no package asset, token, fixture provider, or special diagnostics runtime ships in the APK.

Rollback removes the GitHub source client, package-management route, and plugin log sink wiring while leaving the installed-package store and catalogue untouched. Already installed package definitions naturally remain preserved and unavailable if the rolled-back build no longer publishes installed providers. The external repository and exact releases remain historical publication records; rollback does not rewrite or silently delete them.

## Open Questions

None. The canonical asset name, anonymous public-repository scope, stable-release policy, pinned-owner trust rule, staged-byte ownership, observability mapping, package behavior, and external repository coordinate are fixed above. Host-configured numeric bounds and the pinned positive GitHub owner/repository IDs are implementation values established from source-verified live identities and recorded in test/release evidence, not public compatibility promises.
