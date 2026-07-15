## Context

On-the-road PTT starts by asking `CarTelecomStarter` to choose a car from `BluetoothHeadset.connectedDevices`. The current selector keeps devices reporting `BluetoothProfile.STATE_CONNECTED`, excludes the exact target RSM when known, and succeeds only if one candidate remains. Persisted field logs show repeated `CAR_HFP_PRIME_SKIP reason=no-unambiguous-car-hfp-device` results with two or three raw profile devices, so Android Auto Play returned immediately to Pause before Telecom placement. The current diagnostic combines zero-candidate and multiple-candidate outcomes and does not provide a durable semantic car identity.

The downstream path is intentionally strict and remains useful: one concrete `BluetoothDevice` is primed, cleaned up, reserved for the outgoing Telecom connection, and required as the exact active Bluetooth call route before capture. Display names are diagnostic only. The foreground service already owns the `BluetoothHeadset` profile proxy and publishes process state to Compose through `AppState`; `MainActivity` owns dashboard navigation; simple application-owned settings use private `SharedPreferences`.

The application targets Android API 31+, already requests `BLUETOOTH_CONNECT`, and cannot use hidden Android APIs. Bluetooth addresses are sensitive diagnostic data and must not be logged.

## Goals / Non-Goals

**Goals:**

- Let the user open car configuration by long-pressing the dashboard CAR tile whether or not On-the-road mode is currently available.
- Let the user select exactly one currently connected, eligible HFP device as the car.
- Persist that exact identity across application and service restarts.
- Resolve every new On-the-road Telecom attempt against the configured identity and the live HFP profile state.
- Preserve exact-device priming, cleanup, reservation, and active-route validation after resolution.
- Expose actionable configuration states without exposing Bluetooth objects directly to Compose or logging hardware addresses.

**Non-Goals:**

- Supporting an allowlist or automatic choice among multiple configured cars.
- Configuring a disconnected, merely bonded device.
- Inferring the car from display name, alias, Bluetooth class, UUIDs, Android Auto metadata, list order, or a sole remaining endpoint.
- Associating Android Auto projections with Bluetooth devices through hidden or undocumented APIs.
- Selecting the On-the-road media-playback output device; this change affects the HFP/Telecom capture path only.
- Changing On-the-road mode availability, Android Auto auto-transition, RSM setup, HFP prime mechanics, Telecom route ownership, or active capture when configuration changes.
- Adding permissions or external dependencies.

## Decisions

### 1. Persist one canonical Classic Bluetooth address and a display-only label

Add an application-scoped car configuration store backed by private `SharedPreferences`. The stored record contains:

- the canonical address used as the identity key; and
- the last observed device name used only to render useful offline status.

Both values are updated through one preferences editor operation. The store exposes an observable immutable configuration snapshot. A newly installed or upgraded application starts unconfigured; it does not migrate the previously inferred endpoint into trusted configuration.

A `BluetoothDevice` object and `AudioDeviceInfo.id` cannot be persisted. Device names, aliases, classes, and UUIDs are mutable or non-unique and therefore cannot authorize Telecom routing. The address is the stable application-local identifier for the bonded Classic HFP endpoint. The stored label may become stale without affecting identity resolution.

Alternatives considered:

- **Persist a device name:** rejected because names are mutable, duplicated in the observed environment, and unavailable in some permission/error states.
- **Persist a serialized `BluetoothDevice`:** rejected because it is a runtime framework handle rather than durable application data.
- **Persist a trusted-car set:** rejected because the requested model has one car and an allowlist would require a new policy when two trusted cars are connected.
- **Use DataStore or a JSON catalogue:** rejected because a private two-field preference record matches existing local persistence patterns and avoids a new dependency or needless schema.

### 2. Resolve configuration against a live HEADSET-profile device

Introduce a pure configured-car resolver used by both configuration-state projection and `CarTelecomStarter`. Its inputs are the stored identity, the live `BluetoothHeadset.connectedDevices` snapshot, the exact target RSM when known, and a connection-state predicate. Its typed result distinguishes at least:

- unconfigured;
- configured device absent from the live profile list;
- configured device present but not `STATE_CONNECTED`;
- configured identity conflicts with the target RSM;
- resolved exact live device.

The resolver compares canonical addresses but returns the matching `BluetoothDevice` from the current HEADSET profile snapshot. On success, the existing HFP prime and Telecom path receives that live object. On every failure, the attempt stops before `startVoiceRecognition`, expected-device reservation, or `placeCall`; there is no unique-candidate or first-device fallback.

The foreground operation snapshots the resolved live device once. Replacing the saved configuration while priming or while Telecom owns a connection affects only later operations; it does not redirect or invalidate the current owner. Existing terminal cleanup remains bound to the object captured by that operation.

Alternatives considered:

- **Call `BluetoothAdapter.getRemoteDevice(savedAddress)` and use the resulting wrapper directly:** rejected because membership and connection in the current HFP profile must be proven first.
- **Retain unique-candidate inference as a fallback:** rejected because it preserves the ambiguity the change is intended to remove and silently routes capture to untrusted headsets or watches.
- **Use reference identity only:** rejected across restarts; the durable address selects the live framework object, after which existing exact-device checks remain authoritative.

### 3. Keep configuration discovery and mutation in the foreground-service Bluetooth boundary

Add a service-owned car configuration controller composed with:

- the configuration store;
- the existing HEADSET proxy provider;
- the existing target-RSM provider; and
- the existing service coroutine scope/state publication boundary.

The controller projects UI-safe state containing the configured label/status and connected candidate rows. Candidate rows contain an opaque in-process selection ID, a display label, and selected state; Compose does not receive `BluetoothDevice` instances. The selection ID may encode the canonical address internally but is never rendered or logged.

Candidate discovery uses the live HEADSET profile list, rechecks `getConnectionState(device) == STATE_CONNECTED`, and excludes a device whose exact identity matches the known target RSM. Duplicate or missing names remain separate candidates because identity, not label, distinguishes them. Candidate order is deterministic for stable rendering but has no routing meaning.

Selecting a row re-resolves that ID against a fresh profile snapshot before storage. If the row disappeared, disconnected, became the known RSM, or Bluetooth inspection failed, storage remains unchanged and the state exposes a recoverable failure. Successful selection replaces the previous record atomically and refreshes both UI state and future runtime resolution.

The controller refreshes when the HEADSET proxy connects or disconnects, when existing Bluetooth readiness refresh runs, when the configuration view is entered or retried, and after a selection. This reuses service ownership and avoids a second profile proxy or lifecycle in the Activity.

Alternatives considered:

- **Acquire a separate profile proxy in the configuration screen:** rejected because profile ownership and cleanup already belong to `PttForegroundService` and a second asynchronous proxy would duplicate state.
- **Put `BluetoothDevice` in `AppState`:** rejected because UI state should be immutable, serializable in shape, and free of framework ownership handles.
- **List all bonded devices:** rejected because the user requested connected devices and bonding alone does not prove current HFP eligibility.

### 4. Add an explicit CAR-tile navigation action and dedicated screen

Extend dashboard mode-tile action mapping with an `OpenCarSetup` action. `ModeSegment` enables long-click for both Work and On-the-road tiles:

- Work continues to open the existing RSM setup/monitor route.
- On-the-road opens a new car configuration route.
- The long-press does not invoke the normal click action or change `InputMode`.
- Phone retains no long-press setup action.

`MainActivity` adds the route and maps host-owned UI intents to service configuration refresh/selection operations. The dedicated Compose screen renders:

- current configured-car status: unconfigured, connected, or configured but unavailable;
- currently connected eligible HFP candidates, including selected state;
- deterministic empty, permission-unavailable, and profile-unavailable guidance;
- a retry/refresh action and access to Android Bluetooth settings when connection changes are required; and
- normal back navigation to the dashboard.

Selecting a candidate persists it only after service-side revalidation. The view remains open and reflects the committed selection, making accidental taps observable and allowing replacement by selecting another connected candidate.

A dedicated screen is preferred over extending `ConnectionScreen`, because that screen is specifically the `B02PTT-FF01` RSM SPP/HFP setup and monitor flow. Mixing car identity into it would conflate two independent devices and readiness models.

### 5. Keep mode availability separate from car configuration readiness

`InputModeAvailability.onTheRoad` continues to describe Android Auto/media-client availability. Car configuration state is shown in the configuration view and enforced at PTT route acquisition. This avoids changing auto-transition behavior or making the CAR tile inaccessible when configuration is precisely what the user needs to repair.

A configured but disconnected car may remain recorded. Runtime acquisition fails closed until that exact identity reappears as connected HFP. The store is not automatically cleared by transient disconnect, profile-proxy loss, Bluetooth disablement, or application restart.

Alternative considered:

- **Gate On-the-road availability on configured-car HFP readiness:** rejected for this change because it would alter Android Auto mode-selection and fallback semantics beyond the requested identity correction.

### 6. Preserve diagnostic value without exposing identities

Replace the combined ambiguous-selection diagnostic with semantic configured-resolution outcomes. Logs may include configuration presence, profile-list cardinality, target-RSM-known state, resolution category, and lifecycle result. They must not include full or partial Bluetooth addresses. Device names remain optional diagnostics and cannot affect routing; where unnecessary, log only semantic role such as `configured-car`.

## Risks / Trade-offs

- **[Risk] Existing users lose automatic car selection after upgrade.** → Start unconfigured and make the CAR-tile long-press path explicit; do not silently trust a previously inferred endpoint.
- **[Risk] A saved address becomes stale after unpairing, head-unit replacement, or address change.** → Preserve the record as unavailable and allow replacement from the live connected list.
- **[Risk] `connectedDevices` can contain stale or OEM-specific entries.** → Recheck `BluetoothProfile.STATE_CONNECTED` both when projecting candidates and when committing a selection or starting PTT.
- **[Risk] The target RSM is temporarily unknown during configuration.** → Allow only live HFP selection, then fail closed at runtime if the configured identity is later proven to be the target RSM; refresh the candidate list when RSM identity becomes known.
- **[Risk] Duplicate device labels confuse the user.** → Render each identity as a distinct row with stable selection state and available non-authoritative descriptive metadata; never collapse rows by name.
- **[Risk] Configuration changes race with an active prime or Telecom call.** → Snapshot the resolved live device per operation; mutations apply only to future operations.
- **[Risk] Permission or profile-proxy loss makes the view appear empty.** → Represent those conditions separately from a legitimate zero-candidate state and retain the persisted configuration.
- **[Trade-off] Connected-only setup requires configuring while the car HFP endpoint is connected.** → This supplies the strongest available public-API proof that the selected identity participates in the required profile.

## Migration Plan

1. Add the empty-by-default preference record and UI-safe configuration state; no legacy endpoint is auto-selected.
2. Add the CAR-tile navigation and configuration view so users can establish the required identity.
3. Cut `CarTelecomStarter` over from unique-candidate inference to configured identity resolution in the same release.
4. Preserve existing HFP prime, Telecom reservation, and capture-route contracts after resolution.
5. On rollback, older builds ignore the additional private preferences and resume their previous unique-candidate behavior; no destructive data migration is required.

## Open Questions

None. The proposal intentionally chooses one connected HFP car, persistent exact identity, no fallback, no multi-car allowlist, and no change to On-the-road mode availability.
