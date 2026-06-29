## ADDED Requirements

### Requirement: VU meter reflects live microphone level

The VU meter SHALL render the capture service's live `level` signal (normalized
RMS, 0..1) as a filled level indicator that rises and falls with microphone
input while a capture session is active.

#### Scenario: Level rises with input
- **WHEN** a capture session is active and the `level` signal increases
- **THEN** the meter's filled portion increases correspondingly

#### Scenario: Level falls when input stops
- **WHEN** a capture session is active and the `level` signal decreases toward 0
- **THEN** the meter's filled portion decreases toward 0

### Requirement: Meter visible only while capturing

The VU meter SHALL be visible while `isCapturing` is true and SHALL NOT be
rendered (and SHALL reserve no layout space) while no capture session is active.

#### Scenario: Meter appears when capture starts
- **WHEN** `isCapturing` transitions from false to true
- **THEN** the meter transitions into view

#### Scenario: Meter disappears when capture ends
- **WHEN** `isCapturing` transitions from true to false
- **THEN** the meter transitions out of view
- **AND** no meter element or reserved space remains in the layout

### Requirement: VU ballistics smooth raw level

The meter SHALL apply ballistics to the raw per-chunk `level` signal: a fast
attack (rise tracking input within ~30ms), a slower release (decay toward input
over ~200ms), and a peak-hold marker that holds the recent maximum for ~800ms
then decays.

#### Scenario: Fast attack tracks rising input
- **WHEN** the `level` signal jumps upward while capturing
- **THEN** the meter rises to track it within the attack window

#### Scenario: Slower release smooths falling input
- **WHEN** the `level` signal drops while capturing
- **THEN** the meter decays toward the new level over the release window rather than dropping instantly

#### Scenario: Peak-hold marker retains the recent maximum
- **WHEN** the `level` signal peaks and then falls while capturing
- **THEN** a peak-hold marker remains at the recent maximum for the hold window before descending

### Requirement: Perceptual level mapping

The meter SHALL apply a perceptual curve (square-root of RMS, or equivalent) to
the raw `level` before display, so quiet input is visible and loud input does not
instantly peg the meter. The amber clip zone SHALL be defined against the
displayed (mapped) value.

#### Scenario: Quiet input is visible
- **WHEN** the raw `level` is low (e.g. 0.05) while capturing
- **THEN** the meter fills noticeably above zero after the perceptual mapping

#### Scenario: Loud input fills toward the clip zone without instantly pegging
- **WHEN** the raw `level` is high (e.g. 0.9) while capturing
- **THEN** the meter fills toward the top of the scale, reaching the clip zone

### Requirement: Three-zone level feedback

The meter SHALL divide the scale into three zones and render each segment in its
zone color, so the operator can read at a glance whether input is too low,
healthy, or too high:

- **Low zone** (bottom of the scale): a dim/muted treatment (secondary text color
  at low opacity) signaling input is too low.
- **Good zone** (middle of the scale): the transmit color (`SubspaceCyan` in
  Night Ops, `CommandGold` in Daylight Starfleet) signaling a healthy level.
- **Clip zone** (top of the scale): `AlertAmber` signaling input is too high /
  clipping.

The zone boundaries SHALL be defined in the displayed (post-perceptual-map)
domain.

#### Scenario: Too-low input renders in the low zone
- **WHEN** the displayed level stays in the bottom (low) zone while capturing
- **THEN** the filled segments render in the dim/muted low-zone treatment

#### Scenario: Healthy input renders in the good zone
- **WHEN** the displayed level is in the middle (good) zone while capturing
- **THEN** the filled segments reaching that zone render in the transmit color

#### Scenario: Loud input renders in the clip zone
- **WHEN** the displayed level enters the top (clip) zone while capturing
- **THEN** the filled segments in that zone render in `AlertAmber`

### Requirement: Field-terminal styling

The meter SHALL be styled as field-terminal equipment (segmented, geometric,
glanceable) consistent with `VISUAL_IDENTITY.md`, not as a media-application
equalizer. It SHALL NOT use an off-palette green/yellow/red traffic-light
gradient; zone colors SHALL be limited to the Subspace palette.

#### Scenario: Segmented bar aesthetic
- **WHEN** the meter is rendered
- **THEN** it appears as discrete segments using only the documented Subspace palette colors, not a continuous off-palette traffic-light gradient
