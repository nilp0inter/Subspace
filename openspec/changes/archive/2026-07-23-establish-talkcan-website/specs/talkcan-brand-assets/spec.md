## ADDED Requirements

### Requirement: Image-model raster candidates retain provenance
At least one logo or symbol candidate SHALL be generated with an image model using the supplied Talkcan brand sources as reference. The accepted raw raster SHALL be retained unchanged outside the public build together with the exact prompt, exposed model or interface information, native dimensions, checksum, and approval boundary. Model output SHALL remain non-production source material and SHALL NOT be embedded in canonical SVG masters.

#### Scenario: Maintainer accepts a raster for vectorization
- **WHEN** the maintainer is shown the actual generated raster and explicitly accepts it
- **THEN** the repository SHALL preserve that exact raster and its provenance as the vectorization source
- **AND** a candidate name, textual description, recommendation, or agent-only inspection SHALL NOT substitute for viewing the image

### Requirement: Canonical artwork requires separate vector approval
Production artwork SHALL be traced or controllably reconstructed from the accepted raster and SHALL become canonical only after the maintainer reviews actual renderings of the resulting vector family and explicitly approves them. At the maintainer’s explicit direction, the vector family MAY be deployed temporarily for contextual review without becoming canonical. Image-generation output, automatic tracing output, extracted PDF raster artwork, and unreviewed redraws SHALL NOT become canonical merely because they were generated or deployed successfully.

#### Scenario: Vector candidate is prepared
- **WHEN** the accepted raster has been converted into a symbol, horizontal lockup, reverse lockup, monochrome treatments, favicon-size renderings, and social-card context
- **THEN** those actual renderings SHALL be presented to the maintainer
- **AND** production assets SHALL remain unchanged until explicit approval of the rendered vector family is recorded

### Requirement: Approved masters are true editable vectors
The canonical logo, symbol, reverse lockup, and monochrome variants SHALL be stored as editable SVG masters using paths, shapes, and deterministic typography treatment. Canonical SVG files SHALL NOT embed raster images, remote resources, scripts, event handlers, animation, editor-private binary payloads, or fonts fetched at render time. Their visible geometry SHALL remain legible at the documented minimum sizes.

#### Scenario: Canonical SVG is inspected
- **WHEN** an approved master is statically inspected
- **THEN** it SHALL contain no embedded raster image or executable content
- **AND** it SHALL render without network access
- **AND** its title or accessible use context SHALL identify the Talkcan mark

### Requirement: Asset family preserves canonical identity
The approved family SHALL include a horizontal logo, reverse horizontal logo, standalone symbol, dark monochrome mark, light monochrome mark, favicon source, and social-preview source. Every variant SHALL preserve `talkcan` as the visual wordmark treatment, one recognizable can, a string leaving the can, and branching endpoints without service logos. Variants SHALL use only approved brand colors or one solid monochrome color.

#### Scenario: Variant family is compared
- **WHEN** the approved variants are displayed together
- **THEN** their can, string, endpoints, proportions, and wordmark treatment SHALL identify one coherent mark
- **AND** no variant SHALL use `TalkCan`, metallic effects, service logos, or an unapproved color

### Requirement: Typography and colors are reproducible tokens
The asset source SHALL define the approved color values `#16181D`, `#F7F3E8`, `#F25F5C`, `#F6C945`, `#2256A8`, `#B9C0C8`, `#FFFFFF`, and `#2D7A55` as named reusable tokens. Wordmark and website typography SHALL use an approved Inter source and SHALL retain the applicable font license without committing an unlicensed or model-generated imitation of the typeface.

#### Scenario: Website consumes brand tokens
- **WHEN** the website renders a brand color or canonical type role
- **THEN** it SHALL reference the shared token or documented type role
- **AND** it SHALL not introduce a visually similar magic value as a second brand authority

### Requirement: Distribution assets derive from approved masters
Raster favicons, application icons, and social-preview images SHALL be generated or exported from approved source artwork at documented dimensions. Re-running deterministic export steps with the same source SHALL produce visually equivalent output. Generated distribution files SHALL NOT become an independent editable source of truth.

#### Scenario: Distribution images are regenerated
- **WHEN** approved vector or social-card sources are exported again
- **THEN** the outputs SHALL have the documented dimensions and color treatment
- **AND** replacing generated files SHALL not require manually redrawing the canonical mark

### Requirement: Brand sources retain provenance and usage guidance
The website source repository SHALL retain the operational playbook, visual guideline reference or an immutable reference to it, asset provenance, approval record, font and asset licenses, canonical filenames, permitted use, and prohibited transformations. These source materials SHALL remain outside the generated public site unless a specific file is intentionally selected for publication.

#### Scenario: Future maintainer changes public artwork
- **WHEN** a maintainer prepares a logo or brand-asset change
- **THEN** the repository SHALL expose the canonical source, provenance, applicable license, and approval boundary
- **AND** the maintainer SHALL be able to distinguish source references from public build output

### Requirement: Brand assets support accessible website use
Decorative uses of the symbol SHALL be suppressible from accessibility APIs, while meaningful logo uses SHALL expose the name `Talkcan`. Social preview and documentation examples SHALL not rely on the image to communicate essential product status, installation instructions, or safety information.

#### Scenario: Logo appears beside visible product name
- **WHEN** the symbol is rendered next to visible `Talkcan` text
- **THEN** the symbol SHALL be marked decorative to avoid duplicate announcement

#### Scenario: Standalone logo links to the homepage
- **WHEN** the logo is the only visible content of a homepage link
- **THEN** the link SHALL expose an accessible name identifying the Talkcan homepage
