## 1. Coalesced Sleepwalker Connection

- [x] 1.1 Add a typed, suspendable ensure-connected operation to `SleepwalkerBleConnection` that returns immediately for `Connected`, starts from `Disconnected`, and joins `Scanning` or `Connecting` without launching a duplicate BLE attempt.
- [x] 1.2 Bound the shared connection operation with a named timeout, propagate scan/GATT failure reasons, and return the BLE state to `Disconnected` after stale-attempt cleanup.
- [x] 1.3 Log connection recovery start/join/result/timeout events through `SubspaceRoute` without Bluetooth addresses or protocol payloads.

## 2. Suspendable Keyboard Preparation

- [x] 2.1 Make `ChannelRuntime.prepareInput` and `ChannelRouter.prepareInput` suspendable while preserving immediate behavior and registry lease accounting for existing non-Keyboard runtimes.
- [x] 2.2 Inject the service-owned ensure-connected operation through `KeyboardRuntimeFactory` and make `KeyboardRuntime.prepareInput` await it only when the enabled/configured runtime is not already bridge-connected.
- [x] 2.3 Map connection success to the existing accepted immutable Keyboard target and map failure or timeout to refusal without starting capture or playing the ready beep.

## 3. Recoverable PTT Dispatch and Session Semantics

- [x] 3.1 Update PTT dispatch decisions so only an enabled/configured Keyboard instance with an unavailable Sleepwalker live dependency bypasses the immediate not-ready error decision; preserve all non-Keyboard behavior.
- [x] 3.2 Keep the centralized audio session pending and exclusive during Keyboard recovery, then continue normal route, capture, and ready-beep setup only if the same session remains active with PTT held.
- [x] 3.3 Ensure release, cancellation, repeated press, setup failure, and timeout races cannot revive a terminal session, duplicate recovery, emit a ready beep, or release the route more than once.
- [x] 3.4 Preserve route-correct problem-beep behavior when Keyboard recovery fails or times out and leave passive Keyboard readiness false until the bridge actually reaches `Connected`.
