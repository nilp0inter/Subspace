## Why

CI is broken on every open PR because `app/build.gradle.kts` overrides the debug `storeFile` to `rootProject.file(".android/debug.keystore")` — a gitignored path that AGP does not auto-create. The four currently-open PRs (#6–#9) fail at `:app:validateSigningDebug` with "Keystore file not found." Beyond unblocking CI, the project has no release pipeline: there is no `release` build type, no release signing config, and no workflow that produces a distributable APK. The first tagged release is blocked on both fronts.

## What Changes

- Add a "Provision debug keystore" step to `.github/workflows/ci.yml` that decodes the `ANDROID_DEBUG_KEYSTORE_BASE64` secret to `.android/debug.keystore` before any gradle task runs. No change to `app/build.gradle.kts` debug signing config.
- Add a `release` signing config to `app/build.gradle.kts` that reads keystore path, keystore password, and key password from environment variables. No fallback to debug signing — missing env produces an unsigned APK by design.
- Add a `release` build type to `app/build.gradle.kts` with `isMinifyEnabled = false`, `isShrinkResources = false`, referencing the release signing config.
- Add `.github/workflows/release.yml`, tag-triggered (`v*`), that: imports the release tag signer's GPG public key, verifies the tag signature (fails the build if unsigned or bad signature), provisions both debug and release keystores from secrets, runs `gradle assembleRelease`, verifies the APK with `apksigner`, and publishes the APK to GitHub Releases via the `gh` CLI.
- Add `.github/release-signing-pubkey.asc`, the ASCII-armored public key of the GPG key authorized to sign release tags. Committed (public, not secret).
- Add `RELEASE_SIGNING.md` documenting keystore location, backup requirements, loss-incident procedure, rotation procedure, GPG tag-signing setup, and the operator checklist for cutting a release.
- Update `README.md` Continuous Integration section with a Releases subsection pointing at `RELEASE_SIGNING.md`.
- Add `release.keystore` and `*.keystore` to `.gitignore` as a defensive guard against accidental local commits.

## Capabilities

### New Capabilities
- `ci-keystore-provisioning`: CI workflows provision Android signing keystores from GitHub Actions secrets before invoking gradle, so builds that require signing succeed on ephemeral runners without keystores committed to git.
- `release-signing-pipeline`: A tag-triggered release workflow verifies the release tag's GPG signature, builds a release-signed APK via environment-driven signing config, verifies the APK signature, and publishes the artifact to GitHub Releases.

### Modified Capabilities
<!-- None. No existing spec-level requirements change. -->

## Impact

- **Code:** `app/build.gradle.kts` (add release signing config + release build type), `.github/workflows/ci.yml` (add provisioning step), `.github/workflows/release.yml` (new), `.github/release-signing-pubkey.asc` (new), `RELEASE_SIGNING.md` (new), `README.md` (Releases subsection), `.gitignore` (defensive keystore patterns).
- **Secrets (operator-provisioned, out of code scope):** `ANDROID_DEBUG_KEYSTORE_BASE64`, `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_KEYSTORE_PASSWORD`, `ANDROID_RELEASE_KEY_PASSWORD`.
- **Dependencies:** No new third-party actions. Release workflow uses `actions/checkout`, `DeterminateSystems/determinate-nix-action`, `DeterminateSystems/flakehub-cache-action`, `actions/upload-artifact`, and the `gh` CLI — all already in use or pre-installed on `ubuntu-24.04`. Pinned SHAs are maintained by Renovate as usual.
- **Systems:** GitHub Actions runners (`ubuntu-24.04`), GitHub Releases. No external services, no Play Console, no F-Droid submission in this change.
- **PRs unblocked:** #6, #7, #8, #9 on next CI run after `ci-keystore-provisioning` lands on `main`. PR #10 (in-progress) likewise.
- **Operator prerequisites before first release:** generate debug keystore (if absent locally), generate release keystore (RSA-4096, 25-year validity, alias `subspace-release`), upload 4 secrets via `gh secret set`, export GPG public key to `.github/release-signing-pubkey.asc`, configure local `git config tag.gpgSign true`.
- **Out of scope:** F-Droid metadata file and `fdroiddata` PR (Path C accepted, deferred). R8 / ProGuard / resource shrinking. Play Store / Play App Signing. Sigstore / cosign attestation. Detached GPG signatures on APK artifacts. Self-hosted runner with Yubikey PKCS11. Runtime model download refactor.
