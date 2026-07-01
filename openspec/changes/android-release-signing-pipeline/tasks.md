## 1. Operator setup: debug keystore secret (PR-A prerequisite)

- [x] 1.1 Locate existing `~/.android/debug.keystore` (AGP auto-created) or generate a fresh one via `nix develop -c keytool -genkeypair -v -keystore ~/.android/debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"`
- [x] 1.2 Upload debug keystore as a repository secret: `base64 -w0 ~/.android/debug.keystore | gh secret set ANDROID_DEBUG_KEYSTORE_BASE64`
- [x] 1.3 Verify the secret exists: `gh secret list | grep ANDROID_DEBUG_KEYSTORE_BASE64`

## 2. PR-A: Debug keystore provisioning in CI (ci-keystore-provisioning capability)

- [x] 2.1 Create a feature branch `ci/debug-keystore-provisioning` from `main`
- [x] 2.2 Edit `.github/workflows/ci.yml` to insert a "Provision debug keystore" step between the existing "Verify devshell toolchain" step (line 35) and "Run JVM tests" step (line 37), using `env: DEBUG_KEYSTORE_BASE64: ${{ secrets.ANDROID_DEBUG_KEYSTORE_BASE64 }}`, `mkdir -p .android`, `printf '%s' "$DEBUG_KEYSTORE_BASE64" | base64 -d > .android/debug.keystore`, and `test -s .android/debug.keystore`
- [x] 2.3 Validate the workflow YAML locally: `nix run nixpkgs#actionlint -- .github/workflows/ci.yml`
- [x] 2.4 Commit with message `ci: provision debug keystore from secret to unblock signing validation`
- [x] 2.5 Push and open PR titled "ci: provision debug keystore from GitHub Actions secret"
- [x] 2.6 Confirm CI on the PR itself passes (the secret is available to PR runs from the same repo)
- [x] 2.7 After merge to `main`, manually re-trigger CI on PRs #6, #7, #8, #9, #10 (via `gh pr run <n> || gh pr checks <n> --watch` then comment `/rerun` if needed) and confirm all report green

## 3. Operator setup: release keystore secrets (PR-B prerequisite)

- [x] 3.1 Generate release keystore: `nix develop -c keytool -genkeypair -v -keystore release.keystore -storepass <pw1> -alias subspace-release -keypass <pw2> -keyalg RSA -keysize 4096 -validity 9125 -dname "CN=Subspace Release,O=nilp0inter,C=US"`
- [ ] 3.2 Back up `release.keystore` to two physically separate encrypted offline locations (e.g., encrypted USB drive in a safe + attachment in a password manager). Record the backup locations in operator-private notes (not in the repo).
- [ ] 3.3 Upload release keystore as base64: `base64 -w0 release.keystore | gh secret set ANDROID_RELEASE_KEYSTORE_BASE64`
- [ ] 3.4 Upload keystore password: `gh secret set ANDROID_RELEASE_KEYSTORE_PASSWORD` (read from stdin via heredoc or `printf`)
- [ ] 3.5 Upload key password: `gh secret set ANDROID_RELEASE_KEY_PASSWORD`
- [ ] 3.6 Verify all three secrets exist: `gh secret list | grep ANDROID_RELEASE_`
- [ ] 3.7 Delete the local `release.keystore` from disk only after backups and secrets are confirmed (the local copy can stay for testing but should not be relied upon)

## 4. Operator setup: GPG tag-signing key (PR-B prerequisite)

- [x] 4.1 Confirm Yubikey-backed GPG signing subkey is configured locally (`gpg --list-secret-keys --keyid-format=long` shows `[S]` capability with `card-no` indicator)
- [x] 4.2 Set `git config user.signingKey <key-id>` (if not already)
- [x] 4.3 Set `git config tag.gpgSign true` (and `commit.gpgSign true` if not already)
- [x] 4.4 Smoke-test: create and verify a throwaway signed tag locally (`git tag -s test-sig && git tag -v test-sig && git tag -d test-sig`)

## 5. PR-B part 1: release signing config in build.gradle.kts

- [x] 5.1 Create a feature branch `release/signing-pipeline` from `main`
- [x] 5.2 Add a `release` signing config to the `signingConfigs { ... }` block in `app/build.gradle.kts`: `create("release") { storeFile = System.getenv("ANDROID_RELEASE_KEYSTORE_PATH")?.let(::file); storePassword = System.getenv("ANDROID_RELEASE_KEYSTORE_PASSWORD") ?: ""; keyAlias = "subspace-release"; keyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD") ?: "" }`
- [x] 5.3 Add a `release` build type to the `buildTypes { ... }` block: `create("release") { isMinifyEnabled = false; isShrinkResources = false; signingConfig = signingConfigs.getByName("release") }`
- [ ] 5.4 Verify locally that `nix develop --no-write-lock-file -c gradle assembleRelease` (without env) produces `app/build/outputs/apk/release/app-release-unsigned.apk` and does NOT fail
- [ ] 5.5 Verify locally that `nix develop --no-write-lock-file -c gradle assembleRelease` with the three env vars set (pointing at a local copy of the release keystore) produces a signed `app-release.apk` and `apksigner verify --verbose app/build/outputs/apk/release/app-release.apk` succeeds
- [ ] 5.6 Confirm `nix develop --no-write-lock-file -c gradle assembleDebug` still works unchanged

## 6. PR-B part 2: release workflow

- [x] 6.1 Create `.github/workflows/release.yml` with `name: Release`, `on: push: tags: ['v*']` plus `workflow_dispatch`, `permissions: contents: write`, `concurrency: { group: release-${{ github.ref }}, cancel-in-progress: false }`
- [x] 6.2 Add steps in order: `actions/checkout@<pinned>` with `fetch-depth: 0`, `DeterminateSystems/determinate-nix-action@<pinned>`, `DeterminateSystems/flakehub-cache-action@<pinned>` (use the same pins as `ci.yml`)
- [x] 6.3 Add "Verify release tag signature" step: `gpg --import .github/release-signing-pubkey.asc && git tag -v "${GITHUB_REF_NAME}" 2>&1 | tee /tmp/verify.out && grep -E "Good signature from" /tmp/verify.out`
- [x] 6.4 Add "Provision keystores" step (env contains both base64 secrets): `mkdir -p .android && printf '%s' "$DEBUG_KEYSTORE_BASE64" | base64 -d > .android/debug.keystore && printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 -d > release.keystore && test -s .android/debug.keystore && test -s release.keystore`
- [x] 6.5 Add "Build release APK" step with `ANDROID_RELEASE_KEYSTORE_PATH: ${{ github.workspace }}/release.keystore`, `ANDROID_RELEASE_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_RELEASE_KEYSTORE_PASSWORD }}`, `ANDROID_RELEASE_KEY_PASSWORD: ${{ secrets.ANDROID_RELEASE_KEY_PASSWORD }}`, running `nix develop --no-write-lock-file -c gradle assembleRelease`
- [x] 6.6 Add "Verify APK signature" step: `nix develop --no-write-lock-file -c bash -c 'apksigner verify --verbose --print-certs app/build/outputs/apk/release/*.apk | tee /tmp/sig.out && grep -E "Verified" /tmp/sig.out'`
- [x] 6.7 Add "Upload APK artifact" step using `actions/upload-artifact@<pinned>` with `name: subspace-release-apk`, `path: app/build/outputs/apk/release/*.apk`, `if-no-files-found: error`, `retention-days: 90`
- [x] 6.8 Add "Publish GitHub Release" step: `env: GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}`, `run: gh release create "${GITHUB_REF_NAME}" --title "${GITHUB_REF_NAME}" --generate-notes app/build/outputs/apk/release/*.apk`
- [x] 6.9 Validate workflow YAML: `nix run nixpkgs#actionlint -- .github/workflows/release.yml`

## 7. PR-B part 3: GPG public key, documentation, defensive .gitignore

- [x] 7.1 Export the GPG public key whose private counterpart will sign release tags: `gpg --armor --export <signing-subkey-id> > .github/release-signing-pubkey.asc`
- [x] 7.2 Verify the exported file is ASCII-armored (`head -1 .github/release-signing-pubkey.asc` shows `-----BEGIN PGP PUBLIC KEY BLOCK-----`)
- [x] 7.3 Create top-level `RELEASE_SIGNING.md` documenting: keystore location (secret, not file), key parameters (RSA-4096, 25-year, `subspace-release`), two-physical-backup requirement, loss-incident procedure (no Play App Signing → no upgrade path under current channel mix), rotation procedure, GPG tag-signing setup, operator release-cutting checklist
- [x] 7.4 Edit `README.md` Continuous Integration section: add a "Releases" subsection summarizing the tag-triggered flow and linking to `RELEASE_SIGNING.md`
- [x] 7.5 Edit `.gitignore`: add `release.keystore` and `*.keystore` lines (defensive — `.android/` is already excluded)
- [x] 7.6 Confirm `release.keystore` and `.android/debug.keystore` are both ignored: `git check-ignore release.keystore .android/debug.keystore` reports both
- [ ] 7.7 Stage all PR-B files: `app/build.gradle.kts`, `.github/workflows/release.yml`, `.github/release-signing-pubkey.asc`, `RELEASE_SIGNING.md`, `README.md`, `.gitignore`

## 8. PR-B: review and merge

- [ ] 8.1 Commit PR-B with message `feat: add release signing pipeline with GPG-signed tag verification`
- [ ] 8.2 Push `release/signing-pipeline` and open PR titled "feat: release signing pipeline with GPG-signed tag verification"
- [ ] 8.3 CI on PR-B runs only `ci.yml` (release.yml does not trigger on PRs by design); confirm `ci.yml` green on the PR
- [ ] 8.4 Optional dry-run of release.yml on PR-B branch via `workflow_dispatch` (the workflow supports manual trigger): confirm "Verify release tag signature" step behaves correctly (it should fail on the untagged dispatch, confirming the verification is wired correctly)
- [ ] 8.5 Merge PR-B to `main`

## 9. First release: end-to-end verification

- [ ] 9.1 Edit `app/build.gradle.kts`: bump `versionCode` from `1` to `2`, bump `versionName` from `"0.1.0"` to `"0.2.0"`
- [ ] 9.2 Commit on `main` with message `release: bump version to 0.2.0 for first signed release`
- [ ] 9.3 Push and wait for `ci.yml` on `main` to report green
- [ ] 9.4 Create signed tag: `git tag -s v0.2.0 -m "v0.2.0"` (uses Yubikey; expect touch prompt)
- [ ] 9.5 Push the tag: `git push origin v0.2.0`
- [ ] 9.6 Watch the Release workflow run: `gh run watch` — confirm every step green through "Publish GitHub Release"
- [ ] 9.7 Verify the GitHub Release exists: `gh release view v0.2.0` — confirm APK asset is attached
- [ ] 9.8 Verify the APK signature locally: `gh release download v0.2.0 --pattern '*.apk' --dir /tmp/release-check && nix develop -c apksigner verify --verbose /tmp/release-check/*.apk`
- [ ] 9.9 Install on `B02PTT-FF01`: `nix develop -c adb install -r /tmp/release-check/*.apk` (note: this will fail if a debug build is already installed due to signature mismatch; uninstall first with `adb uninstall dev.nilp0inter.subspace` then install)
- [ ] 9.10 Run the `AGENTS.md` manual acceptance flow on hardware: PTT press/release, Group → Control mode, Volume Up/Down in Control mode, echo with headset routing, foreground service notification on app switch, disconnect cleanup. All checks pass.
- [ ] 9.11 Write the `v0.2.0` release notes manually (or edit the auto-generated ones) to include the signature-mismatch note for existing debug installs.

## 10. Post-merge housekeeping

- [ ] 10.1 Update `AGENTS.md` "Standard Commands" or add a Releases reference pointing to `RELEASE_SIGNING.md` (operator-facing documentation)
- [ ] 10.2 Verify Renovate picks up the new Actions pins in `release.yml` on its next run (the workflow uses the same pinning style as `ci.yml`)
- [ ] 10.3 Optional: rotate the release-signing GPG key only if a stronger key algorithm or new Yubikey warrants it (otherwise leave as-is)
