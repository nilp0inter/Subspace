## Why

The operator has no visual confirmation that the microphone is hearing them while
they talk, and no way to judge their input level. Every talk path (echo, STT,
journal, future channels) captures audio, but nothing on screen reflects capture
in progress or how loud the input is. `VISUAL_IDENTITY.md` §6/§7 already call for
a transmit-color glow and a capture-confirming visual, but none exists today.

The `capture-service` change (this change's prerequisite) introduces a unified
`isCapturing` signal and a live `level` signal (RMS, 0..1) computed in the single
capture read loop, exposed from `PttForegroundService`. That makes a VU meter
trivially possible for the first time: one signal source, one render site, no
per-mode wiring. This change is the UI half of that work — it turns the level
signal into a glanceable, on-palette meter.

## What Changes

- Add a **VU meter** Composable that renders a live audio level indicator driven
  by the capture service's `level` signal. The meter is visible only while
  `isCapturing` is true (i.e. during any talk mode), and hidden otherwise.
- The meter applies **VU ballistics** locally (fast rise, slower decay, peak-hold)
  so it reads like a real meter instead of a flickering bar; the capture service
  emits raw per-chunk levels and the meter smooths them.
- The meter is zoned into three palette-conformant bands so the operator can read
  too-low / healthy / too-high at a glance: a dim **low zone**, a transmit-color
  **good zone** (Subspace Cyan `#00E5FF` in Night Ops, Command Gold `#FFC107` in
  Daylight Starfleet), and an `AlertAmber` **clip zone**. It uses the Chakra
  Petch typeface for any markings and is styled as field-terminal equipment, not
  a media-app equalizer (no off-palette green/yellow/red gradient).
- Render the meter on the **main dashboard** (the home/main surface), so it
  reflects the unified capture signal during any production talk path. Because
  capture is unified, the same meter animates whether the user is talking into the
  journal channel, STT, or any future channel.
- Collect the `level` and `isCapturing` flows from `PttForegroundService` in
  `MainActivity` (alongside the existing `appState` collection) and pass them to
  the dashboard.
- This change renders the **microphone (transmit) level** only — it answers "am I
  being heard and how loud." A playback-level indicator (amber, for inbound
  channel responses) is explicitly deferred; it is called out in
  `VISUAL_IDENTITY.md` §6 but is out of scope here.

## Capabilities

### New Capabilities

- `vu-meter`: A live audio-level meter Composable that consumes the capture
  service's `level`/`isCapturing` signals, applies VU ballistics, renders in the
  transmit palette per `VISUAL_IDENTITY.md`, and is visible only during capture.

### Modified Capabilities

- `main-device-dashboard`: The dashboard now renders the VU meter so the operator
  sees live microphone level during any capture session. (Adds a requirement to
  the existing dashboard spec; no existing dashboard requirement changes.)

## Impact

- **UI** (`app/src/main/java/dev/nilp0inter/subspace/ui/`): new `VuMeter`
  Composable; `MainDashboardScreen` gains the meter and the two new flow
  parameters (`level`, `isCapturing`).
- **`MainActivity`**: collects `level: StateFlow<Float>` and
  `isCapturing: StateFlow<Boolean>` from the bound `PttForegroundService`
  alongside `appState`, and passes them to `MainDashboardScreen`.
- **Theme** (`ui/theme/`): the meter consumes the existing palette tokens
  (Subspace Cyan / Command Gold); no new palette entries required.
- **Depends on** `capture-service` (task 7.1 exposes the two signals). This
  change cannot ship before that.
- **No audio-path changes** — the meter is purely a consumer of signals the
  capture service already provides.
- **Tests**: a JVM/Compose test for `VuMeter` ballistics against a scripted level
  sequence (rise/decay/peak-hold, hidden-when-idle); a dashboard test asserting
  the meter is shown while `isCapturing` and absent otherwise.
