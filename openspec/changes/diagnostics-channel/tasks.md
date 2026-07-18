## 1. Contract Baseline and External Identities

- [x] 1.1 Record the current `InstalledPackagesFacade`, coordinator publication, provider-descriptor publication, catalogue creation, `LuaAdapterRuntime.consumeNativeLogs`, `SubspaceLogger`, dashboard navigation, and shutdown callsites before changing their contracts
- [x] 1.2 Run the focused existing installed-package, Lua-provider, runtime-registry, catalogue-management, observability, and service-composition tests as the pre-change baseline
- [x] 1.3 Resolve and record the positive immutable GitHub account ID for the configured official publisher rather than deriving Official status from the `nilp0inter` login text
- [x] 1.4 Create the public `nilp0inter/diagnostics-channel` companion repository under that resolved publisher identity and record its positive immutable repository database ID for the package manifest
- [x] 1.5 Define the canonical initial distribution constants `subspace-channel.zip`, public stable releases only, REST API version `2022-11-28`, and the pinned official publisher ID in one host-owned configuration boundary

## 2. GitHub Source Domain and URL Parsing

- [x] 2.1 Define platform-neutral immutable repository-owner, resolved-repository, published-release, release-asset, publisher-tier, compatible-candidate, rate-limit, and inspection-session values without OkHttp, Compose, service, or package-store objects
- [x] 2.2 Define sealed typed outcomes covering malformed source URL, unsupported host/path, inaccessible/private/archived/disabled repository, malformed response, bounds, no stable release, no canonical asset, incompatible/invalid package, rate limit, redirect, network, timeout, cancellation, stale operation, lifecycle closure, staging, and trust refusal
- [x] 2.3 Define finite host-configured bounds for URL bytes, metadata response bytes, release candidates, redirects, exact asset bytes, inspection files, operation duration, and retained failure detail
- [x] 2.4 Implement strict parsing for exactly `https://github.com/<owner>/<repository>` and reject credentials, query, fragment, port, `.git`, empty or extra path segments, release/branch/file URLs, and non-GitHub hosts
- [x] 2.5 Add pure URL and host-domain tests covering valid coordinates, Unicode/percent encoding, traversal-like segments, mixed case host, trailing separators, unsupported schemes, and every rejected URL category

## 3. Bounded Anonymous GitHub REST Client

- [x] 3.1 Add a small transport interface and OkHttp implementation using the existing dependency, required `Accept`, `X-GitHub-Api-Version`, and bounded `User-Agent` headers, with no authorization, cookies, or persisted credentials
- [x] 3.2 Implement bounded strict decoding for `GET /repos/{owner}/{repo}` fields including repository ID, canonical coordinates, archived/disabled/visibility, and current owner ID/login/type
- [x] 3.3 Resolve renames/transfers by repository ID, reject inaccessible/non-public or archived sources for install/update, and prevent reused coordinates with another ID from mutating an installed provider
- [x] 3.4 Implement bounded decoding for `GET /repos/{owner}/{repo}/releases`, accepting only published non-draft non-prerelease releases with positive IDs and publication timestamps
- [x] 3.5 Select exactly one uploaded asset named `subspace-channel.zip` per candidate release, reject missing/duplicate canonical assets, and ignore unrelated attachments without interpreting them
- [x] 3.6 Order bounded candidates by publication timestamp and release-ID tie-break without parsing tag or `packageVersion` ordering
- [x] 3.7 Implement exact asset retrieval through `GET /repos/{owner}/{repo}/releases/assets/{asset_id}` with `application/octet-stream`, handling direct `200` and bounded HTTPS `302` responses
- [x] 3.8 Reject redirect loops, excess redirects, missing locations, protocol downgrade, unexpected metadata bodies, oversized responses, and any attempt to forward credentials
- [x] 3.9 Classify GitHub `403` rate exhaustion separately from forbidden/inaccessible source using bounded `x-ratelimit-limit`, `remaining`, and `reset` metadata and perform no automatic retry loop
- [x] 3.10 Add fake-transport tests for repository identity, rename/transfer, release filtering, asset ambiguity, malformed/bounded JSON, HTTP failures, rate limits, direct/redirected assets, cancellation, timeout, and absence of authentication

## 4. Static Candidate Inspection and Exact-Byte Ownership

- [x] 4.1 Add an app-private bounded inspection-staging owner separate from the immutable installed content store, with opaque generation-bound staged-file tokens and startup orphan cleanup
- [x] 4.2 Stream candidate assets into private staging while enforcing metadata and actual-byte bounds without retaining partial overflow content or blocking the Android main thread
- [x] 4.3 Construct the exact `PackageSourceRecord` from resolved repository, release, and asset identities and invoke the existing `PackageValidator` without executing Lua
- [x] 4.4 Inspect bounded stable candidates sequentially, retain only compatible exact staged candidates needed by the active selection session, and expose typed ineligible reasons for invalid siblings
- [x] 4.5 Preserve the validator's repository-ID binding and exact Lua/package-format-v1 compatibility checks instead of adding a GitHub-specific package parser or fallback
- [x] 4.6 Reopen the selected staged bytes after trust confirmation and pass them to `InstalledPackagesFacade.installOrUpdate`, allowing the installed repository to rehash and revalidate before commit
- [x] 4.7 Delete inspection files after cancellation, stale-session replacement, route exit, install success/failure, coordinator shutdown, and bounded startup recovery while never deleting committed installed content
- [x] 4.8 Add inspection tests for newest-incompatible/older-compatible selection, hostile archives, identity mismatch, same-asset time-of-check/time-of-use reuse, cancellation races, stale sessions, cleanup, and unchanged installed state on every pre-commit failure

## 5. Package-Management Coordinator and Service Composition

- [x] 5.1 Extend the installed-package host facade with immutable management summaries derived from committed active/rollback records and published provider availability without exposing content paths, source bytes, or store clients
- [x] 5.2 Implement one service-owned package-management coordinator with the specified resolving, loading, inspecting, selection, trust, installing/updating, refreshing, rollback, removal, ready, failed, and closed states
- [x] 5.3 Serialize source and mutation operations, assign monotonic operation generations, suppress stale completions, and prevent UI-local work from executing after service replacement or shutdown
- [x] 5.4 Implement publisher-tier evaluation using exact resolved owner-ID equality with the pinned official publisher ID and fail closed to Community — Unreviewed
- [x] 5.5 Build immutable confirmation models containing canonical repository, tier, package label/summary, release tag/time, asset name/size, and inspected digest without claiming signature, review, audit, or endorsement
- [x] 5.6 Require explicit current Community trusted-code acknowledgement for every install/update and explicit confirmation for Official install/update, rollback, and removal
- [x] 5.7 Delegate install/update, rollback, and removal exclusively to `InstalledPackagesFacade`; preserve commit-before-publication recovery and the existing provider/runtime reconciliation path
- [x] 5.8 Compose coordinator startup and bounded shutdown in `PttForegroundService` without delaying built-in registration, service foregrounding, or installed-store recovery

## 6. Host-Rendered Package Management UI

- [x] 6.1 Add `DashboardRoute.PackageManagement` and one package-management entry action to the existing dashboard management surface without adding package controls to channel cards
- [x] 6.2 Render canonical repository URL input, resolving/loading/inspection progress, compatible stable release selection, typed source failures, and manual retry from immutable coordinator state
- [x] 6.3 Render Official provenance and Community — Unreviewed warnings exactly from host trust state, including explicit acknowledgement controls that reset when repository, release, asset, digest, or operation generation changes
- [x] 6.4 Render installed package cards with canonical repository, active package version, release tag, trust tier, availability/failure, rollback availability, and explicit refresh/update/rollback/remove actions
- [x] 6.5 Disable conflicting or lifecycle-invalid actions during active operations and never retain a delayed UI mutation across route, service, or coordinator generation changes
- [x] 6.6 After successful install, expose the descriptor through the existing catalogue provider picker and require the user to create/name the instance through the existing generic channel-creation screen
- [x] 6.7 Explain on removal that channel definitions remain preserved and unavailable, and confirm that package management never creates, selects, renames, reorders, configures, or deletes channel instances

## 7. Bounded Lua Plugin Log Projection

- [x] 7.1 Define a provider-neutral `PluginLogSink` with finite queue/message bounds, non-blocking `tryPublish`, idempotent close, bounded loss accounting, and no Lua, actor, platform, or package-store object leakage
- [x] 7.2 Implement deterministic canonical serialization for normalized Lua maps with stable key ordering and exact host fields `instance_id`, `generation`, and `payload`, rejecting whole records that exceed sink bounds
- [x] 7.3 Implement the service-owned sink worker mapping Lua levels to host levels, fixed tag `LuaChannel`, accepted host timestamp, null throwable, and existing `SubspaceLogger` filtering/persistence/stream behavior
- [x] 7.4 Inject the sink through package materialization, `LuaChannelImplementationProvider`, and `LuaAdapterRuntime` without changing the Lua v1 module or callback contracts
- [x] 7.5 Modify `consumeNativeLogs` so each valid actor-accepted record enters the local bounded snapshot and receives exactly one live-generation `tryPublish` attempt without polling or replaying `logSnapshot`
- [x] 7.6 Preserve the publication boundary: sink-accepted records may finish with predecessor attribution after close, while close-before-publication, invalid, actor-rate-dropped, stale, or foreign records never enter host observability
- [x] 7.7 Make sink saturation, serialization failure, persistence failure, and shutdown return immediately, retain only bounded host-owned loss detail, and never re-enter Lua or change the plugin's completed return value
- [x] 7.8 Close and drain the plugin sink in service shutdown ordering without accepting late provider records or blocking runtime-registry teardown

## 8. Functional Smoke Path

- [x] 8.1 Smoke canonical repository resolution through a deterministic fake transport into a compatible inspected package candidate without Lua execution
- [x] 8.2 Smoke trust confirmation and exact staged-byte handoff through the real installed-package transaction into provider publication with no automatically created channel definition
- [x] 8.3 Smoke generic instance creation from the installed descriptor through actor startup, ready publication, periodic unselected heartbeat, metadata-only PTT input, and SOS
- [x] 8.4 Smoke accepted Diagnostics Channel records through the bounded plugin sink into Logcat-facing `SubspaceLogger`, rotating persistence, reactive entries, and existing Log Analysis filtering
- [x] 8.5 Smoke update, explicit rollback, removal, reinstall, and service restart with distinguishable release markers, predecessor close-before-successor-ready ordering, preserved definitions, and fresh volatile state

## 9. Focused Automated Verification

- [x] 9.1 Delegate high-signal source-client and URL contract tests covering identity, trust, bounds, release/asset selection, rate limits, redirects, cancellation, and no-auth behavior to the Tester agent
- [x] 9.2 Delegate inspection and package-management tests covering exact-byte reuse, transaction preservation, stale operation suppression, confirmation invalidation, lifecycle closure, and commit-before-publication recovery to the Tester agent
- [x] 9.3 Delegate plugin-log tests covering exact-once mapping, deterministic serialization, fixed attribution, filtering, invalid/rate-dropped/stale suppression, saturation, persistence, restart loading, and clear-while-live behavior to the Tester agent
- [x] 9.4 Delegate Diagnostics Channel contract fixtures covering independent instances, readiness, startup admission failure, unselected heartbeats, input redaction, SOS, replacement, restart, and no special provider path to the Tester agent
- [x] 9.5 Run only the focused JVM and instrumentation test classes added or modified by this change and repair regressions caused by the new source, UI, lifecycle, and log boundaries
- [x] 9.6 Re-run the affected existing installed-package, Lua provider/actor, runtime registry, catalogue UI, observability, service lifecycle, and shutdown tests

## 10. Official Diagnostics Channel Publication

- [x] 10.1 Add the package source in `nilp0inter/diagnostics-channel` using only Lua v1 modules and callbacks, empty configuration, no capabilities, metadata-only input logging, SOS logging, and one generation-owned heartbeat loop
- [x] 10.2 Add deterministic package assembly that emits exactly `manifest.json` and canonical `lua/*.lua` entries in `subspace-channel.zip` with no native, bytecode, asset, branch-source, or unexpected files
- [x] 10.3 Bind the manifest to the resolved diagnostics repository database ID and exact `Lua 5.4` / `subspace-lua-v1` requirements; validate the artifact with the app's strict package validator before publication
- [x] 10.4 Publish the first stable release with a bounded release marker, canonical asset name, immutable recorded digest, release ID, asset ID, and package provenance
- [x] 10.5 Publish a second compatible stable release with a distinct exact artifact and release marker so update and rollback behavior is externally observable
- [x] 10.6 Confirm both public releases resolve anonymously under the pinned Official owner identity and contain exactly one canonical uploaded package asset

## 11. Physical-Device and Release-Equivalent Evidence

- [x] 11.1 On the supported physical device, enter the real public diagnostics repository URL, resolve its Official identity, inspect compatible releases, confirm trust, and install the first exact release through the production UI
- [x] 11.2 Create and select a Diagnostics Channel instance through generic catalogue management; verify ready/startup records, unselected heartbeat continuation, metadata-only PTT completion, SOS, and `LuaChannel` Log Analysis filtering
- [x] 11.3 Update explicitly to the second release and record predecessor close before successor readiness, successor release marker/generation, and absence of late predecessor diagnostics
- [x] 11.4 Roll back without downloading, remove while preserving the definition unavailable, reinstall the same durable provider, and restart with committed package recovery and fresh Lua state; the physical lifecycle test also exposed and defended the existing Lua v1 positive timer-slack contract so requested sleeps cannot race an equal-time deadline
- [x] 11.5 Exercise malformed/incompatible candidate, anonymous rate-limit, network cancellation, and Community — Unreviewed confirmation paths without changing the committed valid provider
- [x] 11.6 Re-run the established physical RSM, SCO, Telecom, background-operation, and disconnect-notification acceptance flow to confirm package management and plugin observability do not alter existing behavior
- [x] 11.7 Build debug and release-equivalent APKs and verify the single Lua JNI library and generic installer are present while diagnostics package bytes/source, fixture providers, GitHub credentials, loose loaders, and diagnostics-specific runtime branches are absent
- [x] 11.8 Run repository flake validation and record app build provenance, GitHub repository/release/asset IDs, exact artifact digests, device/API/ABI, transition ordering, and per-case outcomes without promoting observed timing or bound values to public guarantees

## 12. Final Contract Reconciliation

- [x] 12.1 Remove any temporary direct-store mutation, second package parser, duplicate HTTP path, bundled diagnostics artifact, special provider registration, unbounded plugin-log queue, or UI-owned operation scaffolding used before the final smoke path
- [x] 12.2 Review every affected source and external package callsite for final repository identity, trust, exact-byte ownership, lifecycle generation, and log-attribution contracts with no fallback by coordinate, label, tag, or display name
- [x] 12.3 Reconcile proposal, design, delta specs, tasks, published package metadata, and implementation evidence with any implementation-discovered contract changes
- [x] 12.4 Confirm every acceptance scenario is evidenced, all required app and companion-repository changes are committed in their owning repositories, strict OpenSpec validation passes, and the change is ready for archive
