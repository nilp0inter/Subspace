## Context

`app/build.gradle.kts` overrides AGP's default debug `storeFile` to `rootProject.file(".android/debug.keystore")`. AGP only auto-creates a debug keystore at its default `~/.android/debug.keystore` location; the override disables auto-creation at the custom path. `.android/` is gitignored (`.gitignore` line 6), so the keystore is local-only state. The build therefore requires a file that exists only on developer machines that manually created it. CI runners are ephemeral and have no such file. Result: `:app:validateSigningDebug` fails on every PR build.

There is currently no release build type, no release signing config, and no release workflow. The first distributable APK cannot be produced by the repository as it stands.

Stakeholders: the project maintainer (sole operator). Channels in scope for the first release: GitHub Releases only. F-Droid enrollment deferred but accepted in principle under Path C (`Binaries` anti-feature for ONNX model blobs).

Constraints:
- Repository is a Nix flake worktree; all tooling comes from `nix develop`. No global installs.
- Existing CI workflow uses pinned-SHA GitHub Actions maintained by Renovate.
- `permissions: id-token: write` already configured (FlakeHub cache); reusable for future OIDC-based signing.
- `.gitignore` already excludes `.android/`; `local.properties` and `build/` are also ignored.
- Operator is a heavy GPG/Yubikey user; tag signing is expected to use a Yubikey-backed key.

## Goals / Non-Goals

**Goals:**
- Unblock the four currently-failing PRs (#6–#9) and the in-progress PR #10 via CI-only changes; no PR author action required.
- Establish a release signing configuration in `app/build.gradle.kts` that is environment-driven, fails safe (unsigned APK) when the operator has not provisioned secrets, and never silently falls back to debug signing.
- Provide a tag-triggered release workflow that produces a signed, `apksigner`-verified APK and publishes it to GitHub Releases.
- Cryptographically bind the release trigger to a GPG-signed tag verified by CI before any build runs.
- Make future F-Droid enrollment additive (no refactor) by ensuring the build outputs are suitable for `Binaries:` field reference.

**Non-Goals:**
- F-Droid metadata file (`dev.nilp0inter.subspace.yml`) or `fdroiddata` PR. Deferred to a separate change.
- R8 / ProGuard / resource shrinking for release builds.
- Google Play Store / Play App Signing enrollment.
- IzzyOnDroid / Aurora / Amazon store submission.
- Migration of HuggingFace ONNX model downloads to runtime on-device fetch (Path A). The current build-time downloads stay; the resulting APK is not byte-reproducible across machines, which is the accepted cost of Path C.
- Sigstore / cosign keyless attestation.
- Detached GPG signatures (`.asc`) on release APK artifacts.
- Self-hosted runner with Yubikey PKCS11 for HSM-backed APK signing.
- Automated `versionCode` / `versionName` derivation from git history.
- Changes to native build tasks (`buildOggNative`, `buildParakeetNative`, `buildSupertonicNative`) or HF asset download tasks.

## Decisions

### Decision 1: CI provisions keystores from base64-encoded GitHub Actions secrets

**Choice:** Decode base64 secret to the expected filesystem path before invoking gradle.

**Rationale:** AGP's auto-create behavior only triggers at `~/.android/debug.keystore`. By overriding `storeFile`, the build script opted out of auto-create; CI cannot rely on it. The base64-secret pattern is the industry-standard approach for Android CI signing (Signal, Firefox, Nextcloud, the AndroidX corpus). It keeps keystores out of git (gitignored at `.android/`, never committed), gives every CI run an identical signature (enabling `adb install -r` upgrades across builds), and requires no new dependencies.

**Alternatives considered:**
- *Remove the debug `storeFile` override entirely; let AGP default to `~/.android/debug.keystore`.* Rejected. Each ephemeral CI runner would generate a different debug signature, breaking in-place upgrades of CI-produced debug APKs. Also changes observable behavior on developer machines (signature reset for everyone).
- *Commit `.android/debug.keystore` to the repo.* Rejected. Debug keystores are local developer state; committing pollutes git history with binary blobs and forces re-review on every rotation.
- *Generate a fresh keystore in CI via `keytool` on each run.* Rejected. Defeats the reproducible-signature property that makes CI artifacts interchangeable across runs.

### Decision 2: Release signing config reads from environment variables, with no debug fallback

**Choice:** `signingConfigs.create("release") { storeFile = System.getenv("ANDROID_RELEASE_KEYSTORE_PATH")?.let(::file); ... }`. When the env is unset, `storeFile` is null and `gradle assembleRelease` produces `app-release-unsigned.apk`.

**Rationale:** A release build that silently falls back to debug signing is a security and operational hazard — users would receive debug-signed APKs masquerading as releases, and signature mismatches between channels would be invisible until install time. Failing safe (producing an unsigned APK that subsequent `apksigner verify` rejects) makes misconfiguration loud. Local `gradle assembleRelease` without env produces an unsigned APK, which is the correct behavior for a developer who has not provisioned release secrets locally.

**Alternatives considered:**
- *Fall back to debug signing when release env is missing.* Rejected (see Rationale).
- *Fail the gradle configuration phase if env is missing.* Rejected. Breaks local iteration on release-specific code (e.g., testing ProGuard rules in a future change) without secrets.
- *Read secrets from a `local.properties` or `.env` file.* Rejected. Both are gitignored and would duplicate the GitHub-secret mechanism with a parallel local mechanism. Environment variables are universal.

### Decision 3: Tag-triggered release workflow, not push-to-main

**Choice:** `on: push: tags: ['v*']` plus `workflow_dispatch`.

**Rationale:** Releases are explicit events. Triggering on every `main` push would produce spurious "releases," exhaust GitHub Release IDs, and require filtering logic to distinguish real releases from CI noise. Tag triggering matches industry convention (`softprops/action-gh-release` examples, GitHub's own release-creation flow). `workflow_dispatch` is included for re-running a release without re-pushing the tag.

**Alternatives considered:**
- *Push to `main` with a version check.* Rejected. Conflates "code merged" with "code shipped."
- *Manual dispatch only.* Rejected. Loses the audit trail of "which tag produced which release."

### Decision 4: GPG-signed tags verified by CI before any build step

**Choice:** Release tags MUST be GPG-signed. CI imports the signer's public key from `.github/release-signing-pubkey.asc` and runs `git tag -v "$TAG"`; the workflow fails if the signature is missing or invalid.

**Rationale:** The tag is the trust root of the entire release. Anyone with push access to `main` could otherwise push a tag and trigger a release. Tag signature binds the release authorization to the holder of the GPG private key (the operator's Yubikey). This is the single highest-ROI integration of the operator's existing GPG/Yubikey workflow: ~10 lines of YAML + one committed public key file, and the release trigger becomes independently verifiable.

**Alternatives considered:**
- *No tag verification; trust whoever has push access.* Rejected. Insufficient for any release that users might install.
- *Detached GPG signature on the APK artifact.* Rejected for now. Redundant with the APK v2/v3 signature, ignored by F-Droid, marginal value unless users actively verify. Can be added later as a non-breaking change.
- *Self-hosted runner with Yubikey PKCS11 for the APK signing step.* Rejected. Overkill for the current threat model (single-developer app, no Play Store, no F-Droid yet). Adds always-on machine, PCSC/OpenSC setup, network-isolation surface. Documented in `RELEASE_SIGNING.md` as a future option.

### Decision 5: RSA-4096, 25-year validity, alias `subspace-release` for the release key

**Choice:** RSA-4096, validity 9125 days (~25 years), alias `subspace-release`.

**Rationale:** Play Store requires ≥25-year validity; matching that future-proofs against later Play enrollment. RSA-4096 is the contemporary standard for long-lived keys (RSA-2048 is acceptable but increasingly frowned on for decades-long horizons). Distinct alias from `androiddebugkey` to prevent accidental cross-use.

**Alternatives considered:**
- *EC keys (P-256).* Rejected for now. Smaller and faster, but Android v3 signature scheme support for EC keys is uneven across the API range Subspace targets (minSdk 31 is fine, but any future lowering would be problematic). RSA-4096 is universally supported.
- *Validity 10000 days (~27 years).* Equivalent in practice; 9125 is the conventional value matching Play Store documentation language.

### Decision 6: `gh release create` over `softprops/action-gh-release` for publishing

**Choice:** Use the `gh` CLI directly in a `run:` step.

**Rationale:** `gh` is pre-installed on `ubuntu-24.04`, uses `GITHUB_TOKEN` automatically, and removes one third-party Action from the supply chain. The maintainer already uses pinned-SHA Actions and Renovate; adding `softprops/action-gh-release@<sha>` is one more thing to audit and rotate. `gh release create "$TAG" --generate-notes path/to/apk` is a one-liner with identical behavior for this use case.

**Alternatives considered:**
- *`softprops/action-gh-release`.* Rejected for supply-chain hygiene reasons above. Would be the right choice if we needed its advanced features (category grouping, conditional publishing, etc.), which we do not.

### Decision 7: R8 / minify / resource shrink OFF for the first release

**Choice:** `isMinifyEnabled = false`, `isShrinkResources = false` in the release build type.

**Rationale:** The app combines three minification hazard zones: ONNX Runtime (reflection over native bindings), Compose (heavy reflection and metadata), and Rust JNI bindings (symbol names that R8 may strip). Incorrect keep rules manifest as release-only crashes that do not reproduce on debug builds. The first release should be byte-behavior-equivalent to debug. Minification can be added later as its own change with hardware-verified ProGuard rules.

**Trade-off:** Release APK is ~30–50% larger than it could be (the ONNX models dominate APK size regardless, so the absolute delta is small relative to total). Worth it for the first release.

## Risks / Trade-offs

- **Loss of the release keystore is catastrophic and irreversible under current channel mix.** Without Play App Signing (which would let Google hold an escrow copy), losing the release key means future releases under `dev.nilp0inter.subspace` cannot upgrade prior installs. → Mitigation: `RELEASE_SIGNING.md` mandates two physically separate encrypted offline backups before any other setup step. Documented loss-incident procedure.
- **HF rate limits may bite on tag-triggered release builds.** Release runs `downloadParakeetAssets` and `downloadSupertonicAssets` fresh; no warm Gradle cache exists on tag-triggered runs. Anonymous HuggingFace access is rate-limited. → Mitigation: if the first release build fails with HTTP 429, add `HF_TOKEN` as a CI secret and propagate it through the download tasks' `environment()`. Not pre-emptive; only on observed failure.
- **PR #10 may conflict with `app/build.gradle.kts` changes.** PR #10 ("fix: car-mode capture release regression") is in-progress and may touch the same file. → Mitigation: land `ci-keystore-provisioning` (PR-A) first; PR-B (release config) rebases after #10 merges. Coordinate with PR #10 author if needed.
- **Forked PR runs do not have access to repository secrets.** If external contributions are ever accepted, `assembleDebug` will fail on fork PRs because `ANDROID_DEBUG_KEYSTORE_BASE64` is unavailable. → Mitigation: not a concern at current scope (no fork PRs expected). If it becomes one, gate the provisioning step on `github.event.pull_request.head.repo.full_name == github.repository` and either skip APK assembly or fall back to AGP auto-generated keystore for fork builds only.
- **APK signature mismatch with existing debug installs.** Users (currently just the operator) with a debug build installed will need to uninstall before installing the first release APK. → Mitigation: documented in the first release's release notes. Expected, one-time.
- **`.github/release-signing-pubkey.asc` rotation is a visible operation.** Rotating the tag-signing GPG key requires a commit, optionally re-signing historical tags, and (if used for verifying historical artifacts) coordination with anyone who pinned the old key. → Mitigation: rotation is rare; document the procedure in `RELEASE_SIGNING.md`.
- **GPG tag verification uses `grep "Good signature"` to assert success.** This is robust against GPG output format drift in practice but theoretically fragile if GPG changes its success message wording. → Mitigation: pin GPG version via the runner image (`ubuntu-24.04` ships a specific version); monitor for GPG major-version bumps in runner image updates.

## Migration Plan

This change is purely additive at the CI/build level; no in-app migration is needed.

**Sequence:**

1. Operator generates (or locates) the debug keystore locally, uploads `ANDROID_DEBUG_KEYSTORE_BASE64` via `gh secret set`.
2. PR-A (`ci-keystore-provisioning` capability only) merges to `main`. Effect: PRs #6–#10 pass CI on next run. No user-visible change.
3. Operator generates the release keystore (RSA-4096, 25-year validity), backs up to two physical locations, uploads 3 release secrets via `gh secret set`.
4. Operator exports their GPG public key (Yubikey-backed signing subkey) to `.github/release-signing-pubkey.asc`.
5. Operator configures local `git config tag.gpgSign true` (and `commit.gpgSign true` if not already).
6. PR-B (release signing config + workflow + docs + pubkey file + README/.gitignore edits) merges to `main`.
7. Operator bumps `versionCode` and `versionName` in `app/build.gradle.kts`, commits to `main`, waits for `ci.yml` green.
8. Operator runs `git tag -s v<versionName>` and `git push origin v<versionName>`.
9. `release.yml` triggers; CI verifies tag signature, builds, verifies APK signature, publishes to GitHub Releases.
10. Operator smoke-tests the published APK on `B02PTT-FF01` per the `AGENTS.md` manual acceptance flow.

**Rollback:** Either PR can be reverted independently. Reverting PR-A re-breaks CI signing (the four PRs go red again). Reverting PR-B removes the release pipeline; previously-published GitHub Releases remain. Tag-signed verification failures do not affect repository state.

## Open Questions

None. All decisions resolved during planning discussion.
