## ADDED Requirements

### Requirement: Website presents the canonical Talkcan identity
The website SHALL present the product name as `Talkcan`, the primary descriptor as `A programmable walkie-talkie for your tools.`, and the canonical brand line as `Talk into the can.`. Public prose SHALL use `Talkcan` casing, while handles, repository names, commands, identifiers, and URLs SHALL use lowercase `talkcan` where applicable. The website SHALL NOT mention the former application name or describe Talkcan as a migration, rename, or compatibility layer.

#### Scenario: Visitor opens the homepage
- **WHEN** a visitor opens the canonical homepage
- **THEN** the page SHALL identify the project as Talkcan
- **AND** it SHALL present the approved primary descriptor
- **AND** it SHALL contain no former-name or migration wording

### Requirement: Website explains the interaction before the implementation
The homepage SHALL explain Talkcan through the sequence `Choose a channel`, `Hold to talk`, `Release`, and `Listen when something answers`, and SHALL visualize one voice interface connected through channels to heterogeneous destinations. The explanation SHALL keep voice primary, channels as the organizing model, AI as one possible destination, and the screen as a configuration, monitoring, and review surface rather than the primary live control surface.

#### Scenario: Visitor scans the product explanation
- **WHEN** a visitor reads the homepage without following external links
- **THEN** the visitor SHALL encounter the push-to-talk interaction sequence
- **AND** the visitor SHALL encounter examples broader than AI
- **AND** the page SHALL explain that the selected channel determines the destination or action

### Requirement: Pre-launch claims remain accurate and classified
Every public behavior claim SHALL be either demonstrably available in a linked public release or explicitly classified as `Experimental`, `In progress`, `Planned`, `Community-maintained`, or `Concept`. The website SHALL NOT provide installation or download actions before a Talkcan-branded public release exists and SHALL NOT present roadmap intent as delivered functionality.

#### Scenario: Capability is not released
- **WHEN** the website mentions a capability that has no Talkcan-branded public release
- **THEN** the capability SHALL carry an explicit non-available classification
- **AND** it SHALL NOT appear in download, installation, or compatibility copy

#### Scenario: No public release exists
- **WHEN** the site is built before the first Talkcan-branded public release
- **THEN** it SHALL omit download and installation calls to action
- **AND** its primary project action SHALL lead to an existing Talkcan GitHub destination

### Requirement: Extension language does not freeze the evolving contract
The website MAY describe channels, integrations, programmability, and an open extension direction at a conceptual level, but SHALL NOT promise a particular language, archive shape, manifest, capability model, installation mechanism, registry, compatibility rule, or author API until that contract has its own approved specification.

#### Scenario: Website describes extensibility
- **WHEN** the website explains that Talkcan is programmable or extensible
- **THEN** it SHALL describe user-visible outcomes and the channel model
- **AND** it SHALL NOT assert that the current Lua package contract is the final public extension interface

### Requirement: Website uses approved brand expression
The website SHALL use the approved Talkcan vector assets, palette, typography, can/string/endpoint visual language, and ordinary direct language defined by the canonical brand sources. It SHALL remain approachable without treating reliability, privacy, security, accessibility, or failure as a joke and SHALL NOT use generic AI futurism, call-center imagery, photorealistic metal effects, or unapproved logo variants.

#### Scenario: Brand assets have been approved
- **WHEN** the website renders a logo, symbol, route illustration, color token, or display treatment
- **THEN** the rendered form SHALL derive from the approved asset family or documented brand tokens
- **AND** it SHALL preserve canonical casing and palette behavior

### Requirement: Website is responsive and accessibly structured
The website SHALL provide semantic landmarks and heading order, keyboard-reachable navigation and controls, a visible skip link, visible focus indication, sufficient text and control contrast, meaningful alternative text, and text equivalents for audio or video demonstrations. It SHALL remain usable at a 320 CSS-pixel viewport, at 200 percent browser zoom, with reduced-motion preference enabled, and without color as the sole carrier of state or meaning. It SHALL NOT autoplay audio or video or publish flashing or rapidly pulsing animation.

#### Scenario: Keyboard-only visitor navigates the site
- **WHEN** a visitor operates the website using only a keyboard
- **THEN** every interactive element SHALL be reachable in a logical order
- **AND** focus SHALL remain visibly identifiable
- **AND** the visitor SHALL be able to bypass repeated navigation

#### Scenario: Visitor requests reduced motion
- **WHEN** the browser reports `prefers-reduced-motion: reduce`
- **THEN** nonessential movement and transitions SHALL be removed or reduced
- **AND** no information SHALL become unavailable

#### Scenario: Visitor uses a narrow viewport
- **WHEN** the viewport width is 320 CSS pixels
- **THEN** primary content and controls SHALL remain readable and operable without horizontal page scrolling

### Requirement: Website publishes complete discovery metadata
Every public page SHALL publish a unique title, description, canonical URL, language, viewport metadata, and indexability policy. The homepage SHALL publish Open Graph and social-card metadata, favicon and application-icon variants, and valid `WebSite`, `Organization`, and `SoftwareApplication` structured data whose claims match visible content. The build SHALL emit a sitemap and robots policy using the configured canonical site URL.

#### Scenario: Homepage metadata is inspected
- **WHEN** a crawler or social-preview service reads the built homepage
- **THEN** it SHALL find absolute canonical and preview-image URLs for the configured site origin
- **AND** structured data SHALL identify Talkcan and its existing public repository destination
- **AND** metadata SHALL contain no unclassified future capability claim

### Requirement: Public destinations are real and conservative
The website SHALL publish only links and policies that exist at build time. It SHALL NOT ship placeholder destinations, empty documentation sections, fake testimonials, generated product screenshots, unapproved partnerships, or footer links to absent privacy, governance, security, code-of-conduct, or trademark documents. External links SHALL identify when the destination leaves the website where that distinction is not otherwise apparent.

#### Scenario: Site is prepared for publication
- **WHEN** a production build is validated
- **THEN** every internal link and required public asset SHALL resolve successfully
- **AND** no placeholder URL, empty policy destination, or fabricated endorsement SHALL remain

### Requirement: Initial website collects no visitor data
The initial website SHALL contain no analytics, advertising, tracking pixel, account, form submission, cookie, local-storage identifier, or application telemetry. Narrow local browser state used solely for an explicit presentation preference MAY be introduced only if it contains no visitor identity and no value leaves the browser.

#### Scenario: Visitor loads the initial website
- **WHEN** the homepage completes loading
- **THEN** it SHALL make no analytics or tracking request
- **AND** it SHALL set no tracking cookie or persistent visitor identifier
