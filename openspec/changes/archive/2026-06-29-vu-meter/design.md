## Context

The `capture-service` change (prerequisite) exposes two new signals from
`PttForegroundService`:

- `level: StateFlow<Float>` — normalized RMS in 0..1, updated per chunk in the
  single capture read loop.
- `isCapturing: StateFlow<Boolean>` — true while any capture session is active.

Today nothing on screen reflects capture in progress. `VISUAL_IDENTITY.md` §6/§7
call for a transmit-color glow and a capture-confirming visual; none exists. This
change is purely a consumer of the two signals above — it adds no audio-path
code.

Palette tokens already exist in `ui/theme/Color.kt`:

- Transmit: `SubspaceCyan` (`#00E5FF`, Night Ops) / `CommandGold` (`#FFC107`,
  Daylight Starfleet).
- Alert/warning: `AlertAmber` (`#FFB300`).
- Type: Chakra Petch (UI/markings) / Inter (body), already bundled.

`MainActivity` already binds `PttForegroundService` and collects `appState` via
`collectAsStateWithLifecycle()`; adding two more `StateFlow`s follows the same
path. The dashboard (`MainDashboardScreen`) is the home/main surface and the
natural render site.

## Goals / Non-Goals

**Goals:**

- A `VuMeter` Composable that renders live microphone level from `level`, visible
  only while `isCapturing`.
- VU ballistics applied locally (fast rise, slower fall, peak-hold) so the meter
  reads cleanly instead of flickering on raw per-chunk RMS.
- On-palette styling per `VISUAL_IDENTITY.md`: transmit color body, amber clip
  zone, Chakra Petch markings, field-terminal (not media-equalizer) aesthetic.
- Wire `level`/`isCapturing` from the service through `MainActivity` into
  `MainDashboardScreen`.

**Non-Goals:**

- Playback-level metering (amber, inbound responses) — deferred; §6 mentions it
  but this change answers "am I being heard," not "is audio playing."
- Rendering the meter on the Monitor screen (echo testing). Echo is a debug tool;
  the dashboard meter reflects the unified capture signal already. Can be added
  later by placing the same Composable there.
- Transmit-glow full-screen border flash (§7) — separate future work; this change
  ships the meter only.
- Changing the capture service. It is a read-only consumer.

## Decisions

### D1. Segmented horizontal bar (not needle, not waveform)

A horizontal segmented bar (LED-style segments). The body fills in the transmit
color; the top ~12% is a clip zone in `AlertAmber`.

**Why over a VU needle:** a needle needs physics + a drawn arc; a segmented bar
is pure Compose layout, glanceable from arm's length (per §6 "glanceability
first"), and matches the field-terminal/Chakra Petch aesthetic rather than a
glossy media equalizer.

**Why over a scrolling waveform:** a waveform needs a ring buffer + continuous
canvas redraw and is harder to read for "how loud am I." A meter answers the
level question directly. A waveform remains a possible future §6 surface.

### D2. VU ballistics computed in the Composable

The capture service emits raw per-chunk RMS (no smoothing). The `VuMeter` applies
ballistics locally:

- **Attack** (rise): fast — track the input up within ~30ms.
- **Release** (fall): slower — decay toward the input over ~200ms.
- **Peak-hold**: a marker holds the recent maximum for ~800ms then decays.

**Why local, not in the service:** the service's contract is "raw level per
chunk"; ballistics are a presentation concern (different consumers may want
different smoothing). Keeping it local means the service stays a pure signal and
the meter is independently tunable/testable.

Implemented with Compose animation primitives (`Animatable`/`animate*AsState`
driven by the collected `level`, plus a manual peak-hold timestamp). A
`derivedStateOf` gates recomposition so only meaningful level deltas churn the
UI.

### D3. Perceptual level mapping (linear RMS → display)

Raw RMS 0..1 is linear; quiet speech reads near 0 and loud speech pegs the top.
The meter applies a perceptual curve before display — `display = sqrt(rms)` (or
equivalently a half-range dB mapping) — so quiet input is visible and loud input
does not instantly clip. The amber clip zone is defined against the *displayed*
value (e.g. top 12%).

**Why not pure dB:** dB requires a reference and goes to −∞ at silence, which is
fiddly for a normalized 0..1 source. `sqrt` gives a comparable perceptual spread
with no reference calibration, and is trivially invertible for testing. The zone
thresholds in D5 are defined in this displayed (mapped) domain.

### D4. Visible only while capturing; smooth transition

The meter is present in the dashboard layout iff `isCapturing` is true. It
fades/scales in on capture start and out on capture end (short ~120ms animation)
so it does not pop. When idle there is no meter element and no reserved space —
the dashboard layout is unchanged from today.

**Why not a permanently-present dimmed meter:** an idle meter at zero is noise;
the dashboard should stay clean when not capturing (§6 "Idle: dimmed UI").

### D5. Three palette-conformant level zones (low / good / clip)

The operator must read three states at a glance — too low, healthy, too high —
not just "clipping." The scale is divided into three zones, and each segment
keeps its zone color so the top of the fill indicates the current zone:

- **Low zone** (displayed 0 – ~20%): `NightTextSecondary` / `DayTextSecondary`
  at low opacity (dim/muted). A fill that stays in this zone reads as a weak
  signal — "speak up / move closer."
- **Good zone** (displayed ~20 – ~88%): transmit color (`SubspaceCyan` dark /
  `CommandGold` light). A fill reaching here reads as a healthy level.
- **Clip zone** (displayed ~88 – 100%): `AlertAmber`. A fill reaching here reads
  as too loud / clipping — "back off."

Thresholds (`LOW_THRESHOLD ≈ 0.20`, `CLIP_THRESHOLD ≈ 0.88`) are defined in the
*displayed* (post-perceptual-map) domain as named constants, so they are tunable
on device. Track (unfilled segments) and any markings use the secondary text
color; labels use Chakra Petch.

**Why three palette zones (not a single body color, and not a traffic-light
gradient):** a single body color + amber clip (the earlier draft) only signals
"too high" — it leaves the operator unable to tell adequate input from
too-quiet input. A green/yellow/red traffic-light gradient would solve it but is
off-palette and reads as a media equalizer. Re-skinning the zones to the Subspace
palette (dim → cyan/gold → amber) gives explicit too-low / good / too-high
feedback while staying on-palette and field-terminal — the same
dim→saturated→warning pattern radio signal bars already use.

**Alternative considered:** keep a single transmit body color and add visible
`MIN`/`MAX` threshold tick marks on the scale (a real-VU-meter look). Rejected as
primary: reading position-against-a-mark is slower than reading color. Retained
as an optional overlay if on-device testing shows the zones alone are ambiguous.

### D6. Placement on the dashboard, fed from MainActivity

`MainActivity` collects `level` and `isCapturing` from the bound service (same
pattern as `appState`) and passes them to `MainDashboardScreen`. The meter
renders in the dashboard's status/connection strip so it sits beside the active
channel and connection indicator — the operational state cluster.

Because `isCapturing`/`level` come from the unified capture service, the same
dashboard meter animates during journal, STT, or any future channel capture
without per-mode wiring.

## Risks / Trade-offs

- **[Risk] Ballistics hide real signal drops.** Slow release could mask a real
  dropout. → Mitigation: release is ~200ms (short enough to show a stop in
  talking within a beat); peak-hold is a separate marker, the live bar still
  tracks input.
- **[Risk] Recomposition churn from a high-frequency level flow.** → Mitigation:
  `StateFlow` conflates; `derivedStateOf` rounds to a display step (e.g. ~5%); a
  segmented bar with ~20 segments naturally quantizes recomposition.
- **[Risk] Perceptual mapping miscalibrates (too sensitive / not sensitive
  enough) on real hardware.** → Mitigation: the `sqrt` curve and clip threshold
  are constants tuned in one place; flagged for on-device adjustment in the
  manual acceptance step.
- **[Trade-off] Meter is dashboard-only, so it is not visible during echo (Monitor
  screen).** Accepted: echo is a debug path; the production talk paths surface on
  the dashboard. Reusing the Composable on Monitor is a trivial future addition.

## Migration Plan

1. Add `VuMeter` Composable + a JVM/Compose ballistics test (scripted level
   sequence) — no wiring yet.
2. Collect `level`/`isCapturing` in `MainActivity` from the service (gated on the
   `capture-service` change having exposed them) and thread to
   `MainDashboardScreen`.
3. Place the meter in the dashboard status strip; wire visibility to
   `isCapturing`.
4. Add a dashboard test asserting meter-shown-while-capturing / absent-otherwise.
5. On-device tuning of the perceptual curve + clip threshold + ballistics against
   real microphone input.

Rollback: the Composable is additive; removing the two flow parameters and the
meter call site reverts the change with no audio-path effect.

## Open Questions

- **Exact ballistics constants** (attack/release/peak-hold windows): proposed
  30ms / 200ms / 800ms defaults; refine on device for a "radio" feel.
- **Segment count and orientation:** ~20 horizontal segments proposed; a vertical
  variant is possible if the dashboard layout later wants a taller meter.
- **Whether to mirror the meter onto the Monitor screen** for echo testing —
  deferred; the dashboard satisfies the "any mode" requirement via the unified
  signal.
