## Context

Talkcan has secured its GitHub organization and domain but has not launched publicly and has no users requiring a Subspace-to-Talkcan transition. The website can therefore establish Talkcan directly as the sole public identity while the Android application completes its current development leg separately.

The supplied operational playbook defines canonical language, positioning, accessibility, trust communication, and public decision boundaries. The supplied PDF defines the visual strategy, palette, typography, logo concept, and first-surface composition. Its embedded artwork is raster reference material rather than production-ready vector source.

The extension surface is deliberately being reconsidered because the current Lua package contract may be too restrictive. This change must express programmability and the channel model without publishing a package format, author API, registry, installation mechanism, or compatibility promise.

Two relevant reference sites use different approaches. OMP uses a custom Vite/React/Tailwind single-page application with custom MDX documentation. Herdr uses Astro and Starlight, with a custom static marketing homepage and versioned Markdown documentation. Talkcan needs Herdr’s static, extensible foundation but not its monolithic raw HTML wrapper, mature documentation machinery, plugin data feeds, or Cloudflare-specific deployment.

The intended source repository, `talkcan/talkcan.github.io`, does not yet exist. These planning artifacts live temporarily in the Subspace repository and must be transferred into the target repository when implementation begins. Domain registration, DNS, organization-domain verification, and GitHub’s custom-domain setting remain manual maintainer operations.

## Goals / Non-Goals

**Goals:**

- Establish a canonical, pre-launch Talkcan website independent of the Android rename.
- Explain the hardware-first, voice-first interaction and one-can/many-strings channel metaphor accurately.
- Produce an approved, coherent, vector-first production asset family.
- Deliver a responsive, accessible, static site with strong discovery and social metadata.
- Make a fresh checkout reproducibly buildable with a small, locked toolchain.
- Publish default-branch builds through GitHub Actions to GitHub Pages.
- Preserve a clean insertion point for documentation and ecosystem discovery after their contracts stabilize.

**Non-Goals:**

- Mentioning Subspace or providing transition guidance.
- Renaming the application, Android package, source repository, releases, or technical namespaces.
- Defining or documenting the Lua runtime, plugin package, installation, catalogue, trust, or compatibility contracts.
- Publishing downloads before a Talkcan-branded release exists.
- Creating accounts, forms, analytics, databases, server functions, mutable APIs, or plugin infrastructure.
- Automating domain registration, DNS, GitHub domain verification, custom-domain association, or certificate management.
- Creating unapproved security, privacy, governance, trademark, or legal policy.
- Launching social accounts or automating community operations.

## Decisions

### D1: Use the organization-site repository as the brand boundary

Create `talkcan/talkcan.github.io` and make it the canonical source and Pages repository. This separates the new public identity from the unlaunched Android repository and lets the site ship without forcing the application rename.

A `website/` directory in Subspace was rejected because it couples deployment, access, CI, and public history to the old project identity. A `talkcan-web` repository was rejected because the organization-site repository provides the simplest default Pages address and no additional repository is needed.

The OpenSpec change will be copied into the target repository before source implementation. The copies in Subspace are planning provenance, not a second website source.

### D2: Use Astro 5 with Bun and static output

Use Astro 5, a committed `bun.lock`, and a documented Bun version. The site will render to `dist/` and contain no server adapter. Astro provides build-time components, file-based pages, metadata composition, asset handling, and a future path to content collections or Starlight without requiring a browser framework.

A Vite/React SPA like OMP was rejected because Talkcan’s initial site does not need application state or client routing, would render primary content through JavaScript, and would add a custom documentation burden. Plain HTML was rejected because the site already needs reusable metadata, layout, brand tokens, and future content growth. Jekyll was rejected because native Pages integration does not offset its weaker fit for this custom visual system. Starlight is deferred until stable technical documentation exists.

### D3: Build the homepage from semantic Astro components

Implement the landing page as a small composition of ordinary `.astro` components: global head/navigation, hero, interaction loop, channel metaphor, hardware-first explanation, current status, open-source action, and footer. Generate semantic HTML at build time. Do not reproduce Herdr’s large raw `index.html` injected through `set:html`.

Components are organizational boundaries, not a design-system abstraction project. Each component owns one visible section and uses shared tokens. Content that requires status classification stays adjacent to its visible classification rather than entering a speculative CMS or data model.

### D4: Default to a warm light identity with CSS-only adaptation

Use Paper and White as primary surfaces, Ink as text, Radio Blue for actions, and Can Red/String Yellow/Signal Green as restrained accents. The initial website uses the warm light identity as its canonical presentation. If a dark treatment is included, derive it from system `prefers-color-scheme` with CSS and the approved reverse mark; do not add local storage or a theme controller merely to demonstrate interactivity.

Use `prefers-reduced-motion` to remove nonessential transitions. Route and string graphics may use restrained CSS or SVG movement only when the static form communicates the complete meaning.

### D5: Keep browser JavaScript exceptional

Primary navigation, content, metadata, and brand storytelling must work without JavaScript. Add browser code only for a concrete enhancement that cannot be expressed with HTML and CSS, such as copying future installation text. The pre-launch site currently needs none. No React, Vue, Svelte, client router, analytics loader, or general animation dependency will be added.

### D6: Treat model artwork and vectorization as two separately reviewed design inputs

Use the PDF artwork and playbook as references to generate one raster candidate at a time with an image model such as the Nano Banana web interface. Preserve the accepted model output unchanged with its prompt, interface/model information when exposed, dimensions, and checksum. The maintainer must see the actual raster and explicitly accept it as the vectorization source; a candidate name, textual description, recommendation, or agent-only rendering is not approval.

Trace or controllably reconstruct the accepted raster into clean editable geometry, enforce the canonical flat palette, and remove model artifacts without silently redesigning the selected composition. Typeset and outline the wordmark deterministically rather than accepting model-generated text. Present the resulting symbol, horizontal lockup, reverse lockup, monochrome treatments, favicon-size renderings, and social-card context to the maintainer. Record a second explicit approval of those actual renderings before the family becomes canonical. The maintainer may explicitly direct a temporary live candidate deployment for contextual review without granting canonical approval. CI never invokes an image model and never regenerates the canonical mark from a prompt.

### D7: Make brand tokens the only styling authority

Define semantic CSS custom properties for all approved colors, type roles, spacing, borders, focus treatment, and maximum content widths. Self-host a pinned, licensed Inter webfont subset or complete WOFF2 distribution and retain its license; use system sans-serif as fallback. Use a licensed monospace only where code appears.

Do not copy the PDF’s layout literally. Translate its can, string, node, label, and practical-equipment vocabulary into responsive web composition. SVG content must not embed scripts, raster payloads, or remote font references.

### D8: Use a focused pre-launch information architecture

The first public surface is a compact site rather than a broad documentation portal:

1. Hero with canonical descriptor and an existing Talkcan GitHub action.
2. Choose/hold/talk/release/listen interaction loop.
3. One-can/many-strings explanation using heterogeneous conceptual destinations.
4. Hardware-first, eyes-free principles.
5. Explicit current-status section that separates available facts from concept or in-progress work.
6. Open-source/project destination.
7. Footer containing only destinations that exist.

Do not create empty documentation, download, plugin, security, privacy, governance, or trademark pages to satisfy a navigation template. Add those destinations only when approved content exists.

### D9: Build metadata from one configured canonical origin

Centralize the site name, descriptor, canonical origin, repository destination, preview image, and public status. Generate titles, descriptions, canonical links, Open Graph/Twitter fields, favicons, sitemap, robots policy, and JSON-LD from that configuration.

Use the organization Pages URL for initial deployment. After the maintainer manually completes domain association, update the canonical origin in source to the selected domain and redeploy. Do not store registrar or DNS credentials and do not treat a `CNAME` file as domain configuration authority.

### D10: Deploy only immutable static artifacts through GitHub Pages

Use GitHub’s supported Pages workflow: checkout, install the declared Bun version, install from the frozen lockfile, run the production validation/build command, upload `dist/` as the Pages artifact, and deploy from the protected `github-pages` environment only on the default branch. Pin third-party actions by commit and grant only `contents: read`, `pages: write`, and `id-token: write` where required.

Pull requests run the same validation and build path but do not deploy. Concurrency cancels superseded runs. A failed build leaves the previous Pages deployment intact. Rollback is a source revert followed by a normal deployment.

### D11: Validate behavior at the built-output and browser boundaries

The build must validate generated HTML, metadata, internal links, required assets, sitemap/robots output, forbidden placeholders, forbidden former-name references, and absence of analytics integrations. Keep validation boring and deterministic; use small build-time scripts before introducing a broad test framework.

Before acceptance, serve `dist/` locally and drive the real output in Chromium at desktop and 320-pixel mobile widths. Exercise skip navigation, keyboard focus order, external actions, reduced motion, resource loading, and console errors. Check representative text/background pairs against WCAG AA and ensure 200 percent zoom preserves operation.

## Risks / Trade-offs

- [The target repository does not exist] → Create it under the Talkcan organization first, transfer these planning artifacts and canonical brand sources, and make the first implementation commit there rather than adding website code to Subspace.
- [Generated artwork is inconsistent or contains artifacts] → Preserve the accepted raw model raster as provenance, reconstruct deterministic vectors from it, inspect the actual rendered family at small and monochrome sizes, and require separate maintainer approval before publication.
- [Brand approval delays the full visual site] → Complete content structure and token work independently, but do not publish an unapproved approximation as the canonical mark.
- [Public claims drift away from product reality] → Classify every behavior claim and review site claims against public releases on each change; do not copy the stale Subspace README as current truth.
- [GitHub Pages cannot set arbitrary response headers] → Keep the site static, avoid sensitive flows and third-party scripts, use safe markup and local assets, and do not pretend a meta policy replaces unavailable response headers.
- [Astro and Bun add more machinery than plain HTML] → Lock dependencies, expose one build command, generate no server runtime, and avoid framework integrations until a requirement demands them.
- [A future documentation system changes navigation] → Keep the homepage independent and add Starlight or content collections later under stable routes rather than prebuilding empty sections.
- [Initial Pages and later custom-domain origins differ] → Centralize the origin and perform one source configuration change after the maintainer completes manual association.

## Migration Plan

1. Create the public `talkcan/talkcan.github.io` repository with protected default-branch and Pages environment settings.
2. Transfer this completed OpenSpec change, the operational playbook, the visual guideline reference, and recorded brand decisions into the target repository.
3. Bootstrap the locked Astro/Bun site and publication validation without configuring a custom domain.
4. Generate and retain an image-model raster candidate, obtain maintainer acceptance of the viewed raster as the vectorization source, reconstruct the vector family, and publish it only after the maintainer separately approves the rendered production contexts.
5. Build and browser-verify the complete pre-launch site locally.
6. Merge through the default branch and verify deployment at the organization’s GitHub Pages URL.
7. The maintainer manually verifies the domain, configures DNS and the Pages custom domain, and confirms HTTPS.
8. Update the site’s canonical origin to the selected domain and redeploy after manual association is live.

Rollback consists of restoring the last accepted website source commit and redeploying its static artifact. Domain rollback remains a manual maintainer operation because this change never owns DNS or Pages domain settings.

## Open Questions

- What exact custom domain should replace the organization Pages URL in canonical metadata after manual association?
- Should the visual guideline PDF itself be public in the website repository, or retained as a non-published source reference accessible only to maintainers?
