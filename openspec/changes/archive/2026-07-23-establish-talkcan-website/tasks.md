## 1. Target Repository and Planning Transfer

- [x] 1.1 Create the public `talkcan/talkcan.github.io` organization-site repository with `main` as its default branch, without adding website source to the Subspace repository
- [x] 1.2 Transfer the completed `establish-talkcan-website` OpenSpec artifacts and the two supplied Talkcan brand source documents into non-published source paths in the target repository
- [x] 1.3 Add target-repository agent instructions that make the operational playbook authoritative for public language, require maintainer approval at its escalation boundaries, and identify the PDF as the visual reference
- [x] 1.4 Configure default-branch protection and a protected `github-pages` deployment environment while leaving domain verification, DNS, custom-domain, and certificate settings untouched

## 2. Reproducible Static-Site Foundation

- [x] 2.1 Add an Astro 5 project configured for static `dist/` output, declare the supported Bun version, and commit a frozen `bun.lock`
- [x] 2.2 Add a Nix development shell exposing the declared Bun toolchain and repository checks without introducing global tooling or a server runtime
- [x] 2.3 Define one production command that runs publication validation and builds the complete site, plus one local preview command that serves the generated output
- [x] 2.4 Add shared site configuration for the Talkcan identity, organization Pages origin, GitHub destination, public maturity, preview image, and future canonical-domain replacement
- [x] 2.5 Add repository ignore rules that exclude dependencies, local caches, and `dist/` while preserving every source and license input required for a fresh build

## 3. Canonical Brand Asset Approval

- [x] 3.1 Extract the approved palette, typography roles, logo constraints, minimum-size behavior, and prohibited transformations into reusable website brand tokens and asset guidance
- [x] 3.2 Generate one raster candidate at a time with an image model using the supplied artwork as reference, retain the accepted raw PNG with its exact prompt, exposed model/interface information, dimensions, checksum, and explicit maintainer acceptance as the vectorization source
- [x] 3.3 Trace or controllably reconstruct the accepted raster into a sanitized editable standalone SVG symbol with canonical flat colors and no raster embedding, remote dependency, script, animation, font, or editor-private payload
- [x] 3.4 Build the deterministic horizontal, reverse, monochrome, favicon-size, and social-card vector family; publish the maintainer-directed live review deployment; present the actual contexts; and obtain explicit feedback or canonical approval
- [x] 3.5 Add the licensed Inter webfont source and applicable font/asset license records, then expose approved typography through local font files and fallbacks
- [x] 3.6 After vector-family approval, replace the production masters, regenerate deterministic favicon and social-preview distributions, update provenance records, and verify every output at native size

## 4. Pre-Launch Website Content and Structure

- [x] 4.1 Establish the semantic site shell with canonical head metadata, visible skip navigation, keyboard-operable header, main landmark, and footer containing only existing destinations
- [x] 4.2 Implement the hero using `Talkcan`, `A programmable walkie-talkie for your tools.`, the approved mark, an existing Talkcan GitHub action, and no download or former-name messaging
- [x] 4.3 Implement the `Choose a channel` / `Hold to talk` / `Release` / `Listen when something answers` interaction sequence as readable HTML with a complete static meaning
- [x] 4.4 Implement the one-can/many-strings channel explanation with accessible responsive SVG or CSS graphics and heterogeneous destinations broader than AI
- [x] 4.5 Implement the hardware-first and eyes-free principles without implying proprietary hardware, emergency use, safety-critical reliability, or support that has not been publicly demonstrated
- [x] 4.6 Audit every behavior claim against public Talkcan release evidence and label unreleased behavior as experimental, in progress, planned, community-maintained, or concept
- [x] 4.7 Implement the pre-launch status and open-source sections without installation instructions, plugin-format promises, empty documentation, or placeholder policies
- [x] 4.8 Apply the approved warm Paper/White/Ink presentation, restrained signal colors, responsive spacing, readable type scale, visible focus treatment, and reduced-motion behavior across all sections

## 5. Discovery, Privacy, and Static Validation

- [x] 5.1 Generate unique titles, descriptions, canonical links, language and viewport metadata, Open Graph/Twitter fields, favicons, sitemap, and robots policy from the centralized site configuration
- [x] 5.2 Add valid `WebSite`, `Organization`, and `SoftwareApplication` JSON-LD whose repository, licensing, maturity, and capability claims match visible approved content
- [x] 5.3 Add built-output checks for semantic HTML, required metadata, internal links, required assets, placeholder URLs, accidental `Subspace` references, and unexpected analytics or tracking integrations
- [x] 5.4 Verify the generated site makes no analytics, advertising, account, form-submission, telemetry, or visitor-identifier request and requires no private backend or secret
- [x] 5.5 Verify meaningful media has text alternatives, decorative brand art is hidden appropriately, no state depends only on color, and no audio, video, flashing, or nonessential motion starts automatically

## 6. GitHub Pages Publication

- [x] 6.1 Add a least-privilege, commit-pinned GitHub Actions workflow that installs the declared Bun version, installs the frozen dependency graph, runs the production build, and uploads `dist/` as the supported Pages artifact
- [x] 6.2 Make pull requests execute the complete validation and build path without deployment, and make only successful `main` builds deploy to the protected `github-pages` environment
- [x] 6.3 Add deployment concurrency and source-commit traceability so superseded runs are cancelled and a failed build leaves the preceding Pages deployment unchanged
- [x] 6.4 Enable GitHub Pages with GitHub Actions as the publication source and deploy the verified site while preserving the maintainer-configured `talkcan.io` custom domain
- [x] 6.5 Confirm that restoring a previously deployed source commit and rerunning the workflow reproduces a valid rollback artifact without backend or data migration

## 7. End-to-End Acceptance

- [x] 7.1 Build from a fresh target-repository checkout through the declared Nix development shell and frozen Bun dependency graph, then serve the generated `dist/` rather than a development server
- [x] 7.2 Drive the corrected built homepage in Chromium at representative desktop and 320-pixel mobile viewports and verify primary content, logo rendering, navigation, wrapping, resources, and absence of console errors
- [x] 7.3 Exercise the built site using keyboard-only navigation, the skip link, visible focus, 200 percent zoom, and reduced-motion preference
- [x] 7.4 Verify representative color pairs meet WCAG AA, the standalone and adjacent corrected logo cases expose correct accessible names, and the approved mark remains legible at minimum rendered sizes
- [x] 7.5 Inspect the corrected deployment at `talkcan.io` for assets, metadata, social preview, internal links, HTTPS, and exact correspondence to the successful source commit
- [x] 7.6 Record the completed maintainer handoff for domain verification, OVH DNS, Pages custom-domain association, HTTPS enforcement, and the source update from the Pages origin to `talkcan.io`
