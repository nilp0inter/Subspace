## Why

Talkcan needs a canonical public identity before product launch, but the Android application is still completing its current development leg and the extension contract remains intentionally unsettled. A static, pre-launch website can establish the brand, explain the product accurately, and create a durable home for future documentation without coupling the site to the application rename, plugin format, or server infrastructure.

## What Changes

- Establish `talkcan/talkcan.github.io` as the source repository for the canonical Talkcan website.
- Create a static, responsive, accessible pre-launch website presenting Talkcan as “A programmable walkie-talkie for your tools” through the approved can, string, endpoint, and channel metaphor.
- Present Talkcan as the sole public identity without migration, former-name, download, or compatibility messaging.
- Describe the product interaction and vision while distinguishing current, experimental, and conceptual behavior and avoiding commitments to the evolving extension surface.
- Produce an approved brand asset family through a documented image-model-raster-to-editable-vector workflow using the supplied brand guidelines and reference artwork, with separate explicit maintainer approval of the viewed raster source and the rendered production vector family.
- Build the site with Astro 5, Bun, custom Astro components, custom CSS, static output, and only narrowly justified browser JavaScript; defer Starlight until stable documentation warrants it.
- Publish deterministic site output through GitHub Actions and GitHub Pages, including canonical metadata, social previews, structured data, sitemap, and robots policy.
- Verify responsive behavior, keyboard navigation, readable contrast, reduced-motion behavior, semantic structure, and the absence of broken or placeholder destinations.
- Keep domain registration, DNS records, GitHub domain verification, and custom-domain association as manual operator work.
- Exclude application renaming, Android changes, plugin contracts, plugin discovery, author tooling, accounts, databases, dynamic backends, analytics, social automation, and unapproved governance, privacy, trademark, or legal policy creation.

## Capabilities

### New Capabilities

- `talkcan-public-website`: Defines the public content, brand expression, accuracy, accessibility, metadata, and pre-launch interaction contract for the Talkcan website.
- `talkcan-brand-assets`: Defines the review, approval, vector-master, variant, color, typography, and distribution requirements for production website brand assets.
- `talkcan-pages-publication`: Defines reproducible static builds, automated GitHub Pages deployment, publication checks, and the boundary around manual domain and DNS operations.

### Modified Capabilities

None.

## Impact

- Introduces the separate public repository `talkcan/talkcan.github.io`; the existing Subspace Android source, build, package identity, release pipeline, and runtime behavior remain unchanged.
- Adds a Bun/Astro static-site toolchain and GitHub Actions Pages workflow in the website repository.
- Uses the supplied `Talkcan_Brand_Community_Agent_Playbook.md` as the canonical operational language policy and `Talkcan_Brand_Guidelines_v0.1.docx.pdf` as the visual and strategic reference, subject to explicit maintainer decisions.
- Produces only static public files and GitHub Pages deployment artifacts; no service credentials, persistent user data, application API, or mutable backend are introduced.
- The OpenSpec planning artifacts originate in the current repository because the target organization-site repository does not yet exist; implementation must create and initialize the target repository before applying website source changes there.
