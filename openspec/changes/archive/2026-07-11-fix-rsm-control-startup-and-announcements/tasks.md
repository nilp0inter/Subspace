## 1. RSM Control Navigation and Feedback

- [x] 1.1 Define and apply the pure RSM event-to-catalogue-offset mapping with Volume Up as `-1`, Volume Down as `+1`, and non-volume events unmapped
- [x] 1.2 Detect saturating top/bottom traversal from the committed active ID and preserve the active channel at either boundary
- [x] 1.3 Route boundary error beeps through the serialized `SystemAnnouncer` SCO lifecycle with prior-playback cancellation and exact-once release
- [x] 1.4 Add focused JVM tests for inverted RSM offsets, non-volume exclusion, one error beep, and exact-once SCO release

## 2. Initial Serial Auto-Connection

- [x] 2.1 Add a prerequisite-gated immediate initial-connection decision to `ReconnectPolicy`
- [x] 2.2 Establish monitoring intent during device-link service startup and schedule the first eligible SPP attempt without prior-session state
- [x] 2.3 Route manual Connect serial through the shared scheduler while preserving explicit-disconnect cancellation and delayed unexpected-loss retries
- [x] 2.4 Add a focused JVM test proving a fresh monitoring policy schedules and begins an immediate eligible attempt without a prior session

## 3. Four-Step Announcement Rendering

- [x] 3.1 Define one announcement-only four-step render constant and apply it to synthesis requests and persistent render settings
- [x] 3.2 Verify prior higher-step announcement cache identities miss and regenerate through the existing reconciliation path
- [x] 3.3 Add a focused JVM test asserting system-announcement precomputation sends `totalSteps = 4`

## 4. Integrated Verification

- [x] 4.1 Run the focused RSM direction, boundary playback, initial reconnect policy, and announcement parameter JVM tests through the repository devshell
- [x] 4.2 Build and install the debug APK on the physical Android device without clearing the existing catalogue
- [x] 4.3 Verify on the physical device that cold app start auto-attaches bonded RSM serial, RSM Volume Up/Down follow visual order, top/bottom attempts produce the headset error beep, explicit disconnect suppresses same-lifetime reconnect, unexpected loss still retries, and announcement bootstrap regenerates four-step PCM
