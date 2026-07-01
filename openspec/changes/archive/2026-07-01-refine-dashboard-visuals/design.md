## Context

The dashboard currently renders a vertically scrolling Compose column with a
`TerminalHeader`, a large `ConnectionIndicator` card, the VU meter, the input
mode selector, and channel cards. The title can be obscured by Android's status
bar because the app does not currently apply a safe drawing or status-bar inset
to the root dashboard content. The RSM connection state is represented as a
large standalone dashboard card even though it is semantically the availability
state of Work/RSM input mode.

The visual identity says the phone screen is a hardware-first field monitor. The
operator needs stable glance targets: active mode, RSM/car/phone availability,
capture level, and channels should not move when capture starts or device state
changes.

## Goals / Non-Goals

**Goals:**

- Make the dashboard header safe from Android status-bar overlap.
- Convert the input mode selector into three fixed-height icon tiles.
- Represent RSM/Bluetooth readiness as an indicator inside the RSM tile.
- Route unavailable-RSM tap and any RSM-tile long-press to the existing RSM setup
  flow.
- Keep dashboard vertical geometry stable: primary sections remain mounted and
  reserve their normal height in idle, unavailable, and active states.
- Align iconography with `VISUAL_IDENTITY.md`: line-art, rounded, state-driven,
  high contrast, and glanceable from arm's length.

**Non-Goals:**

- Do not change Bluetooth, SPP, SCO, pairing, scanner, or monitor logic.
- Do not add a new Android Auto UI template or change car-mode behavior.
- Do not change input mode transition rules or audio route resolution.
- Do not introduce persisted settings, user preferences, or onboarding screens.
- Do not add new channels or change channel-card PTT semantics.
- Do not hide, collapse, or conditionally remove primary dashboard sections.
- Do not use emoji as icons.

## Decisions

### Apply safe-area padding at the dashboard content boundary

The dashboard content should apply system-bar/safe-drawing top padding before the
`TerminalHeader`, rather than adding arbitrary top padding to the title itself.
This keeps the layout correct across devices with different status-bar heights,
display cutouts, and font/scaling settings.

Alternatives considered:
- Hard-code additional top padding. Rejected because it is device-dependent and
  can still fail on cutouts or larger status bars.
- Move the header lower with a spacer. Rejected because it encodes the same
  arbitrary offset and does not express the actual system inset constraint.

### Mode selector becomes the primary connection context

`InputModeSelector` should render three fixed-height tiles: RSM/Work,
On-the-road, and On-a-pinch. Each tile shows a large icon, a short label, and a
compact status indicator. The standalone `ConnectionIndicator` should no longer
occupy the top of the dashboard because it duplicates Work/RSM readiness and
pushes more important monitor content downward.

Alternatives considered:
- Keep the connection card and add icon tiles below it. Rejected because it keeps
  the top-heavy layout and makes the RSM state appear as a separate subsystem
  instead of an input mode condition.
- Replace mode labels with icons only. Rejected because labels are useful for
  accessibility, testing semantics, and first-time comprehension.

### RSM tile owns setup access

The RSM tile has mode-dependent tap behavior:
- If Work mode is available, tap selects `InputMode.Work`.
- If Work mode is unavailable, tap opens the existing setup flow.
- Long-press always opens the existing setup flow.

The setup flow should remain the existing connection/monitor drill-down screens;
this change only changes how the dashboard reaches them.

Alternatives considered:
- Disable the unavailable RSM tile. Rejected because it hides the recovery path.
- Add a separate setup button next to the tile. Rejected because it adds another
  primary touch target and weakens the fixed three-mode control model.

### Stable layout is a hard dashboard invariant

The VU meter remains always mounted. Idle state renders a dim track and standby
label. Capture state changes segment fill, peak marker, and label intensity, not
layout presence. Mode availability changes tile color, icon intensity, and status
text, not tile visibility or height.

Alternatives considered:
- Keep the previous capture-only meter requirement. Rejected because it shifts
  channel-card positions during the live loop.
- Animate section enter/exit. Rejected for primary dashboard sections because the
  requirement is stable relative height, not just smooth movement.

### Use local vector icon assets for mode tiles

Use app-owned vector drawables or Compose vector paths for headset, steering
wheel, and phone icons so the icon family has consistent line weight and rounded
geometry. Material icons may remain for secondary controls such as settings, but
the mode tiles should not depend on emoji or mismatched built-in glyphs.

Alternatives considered:
- Use emoji. Rejected by the visual identity and accessibility/taste constraints.
- Use only bundled Material icons. Rejected because a steering-wheel glyph may not
  be available in the current dependency set and mixing families risks a sloppy
  mode selector.

## Risks / Trade-offs

- Hidden long-press affordance → Mitigation: unavailable RSM tile also opens setup
  on normal tap, and the tile status text can communicate setup readiness.
- Mode tile tap behavior differs by availability → Mitigation: available means
  select, unavailable means fix/setup; both actions are anchored to the RSM
  context and should be test-pinned.
- Removing the large connection card reduces diagnostic detail on the dashboard →
  Mitigation: detailed state remains in the existing setup/monitor screens; the
  dashboard tile shows only glanceable readiness.
- Fixed layout may use more idle vertical space → Mitigation: this is intentional
  for stable touch targets and status scanning; idle state should be visually dim,
  not absent.
- Custom vector icons can look inconsistent if hand-drawn quickly → Mitigation:
  define them as a small matched family with shared viewport, stroke width,
  rounded caps/joins, and visual size.

## Migration Plan

1. Update dashboard route/action wiring so the RSM tile can open the existing
   connection/monitor drill-down path.
2. Apply safe-area padding to the dashboard content boundary and verify the title
   no longer overlaps Android status content.
3. Replace the standalone connection card and text segments with fixed-height
   icon mode tiles.
4. Keep the VU meter mounted in both idle and capture states and update stale
   tests/spec expectations away from hidden-idle behavior.
5. Verify the app with JVM tests, debug assemble, and physical/device visual QA
   where available.

Rollback is local to the dashboard: restore the previous `ConnectionIndicator`,
text `ModeSegment` controls, and previous route callback wiring. No persisted
data migration is required.

## Open Questions

- None. The exploration resolved the main interaction question: unavailable RSM
  tap opens setup; long-press always opens setup.
