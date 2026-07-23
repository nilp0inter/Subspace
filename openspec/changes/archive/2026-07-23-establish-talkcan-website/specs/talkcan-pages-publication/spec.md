## ADDED Requirements

### Requirement: Website source lives in the organization-site repository
The implementation SHALL create and initialize the public repository `talkcan/talkcan.github.io` as the canonical website source. The repository SHALL contain the site source, locked build dependencies, brand source materials, validation commands, and Pages workflow; generated dependency directories and local build output SHALL NOT be committed as source.

#### Scenario: Website repository is initialized
- **WHEN** the target repository is prepared for the first implementation commit
- **THEN** its GitHub coordinates SHALL be `talkcan/talkcan.github.io`
- **AND** a fresh checkout SHALL contain all inputs required to reproduce the public site

### Requirement: Website has one reproducible static build
The repository SHALL pin Bun through its declared development environment and lock Astro and all transitive JavaScript dependencies in `bun.lock`. One documented build command SHALL validate the site and emit the complete static publication tree to `dist/` without network access beyond dependency acquisition and without requiring a server runtime, secret, domain credential, or application repository checkout.

#### Scenario: Maintainer builds a fresh checkout
- **WHEN** a maintainer installs the locked dependencies and runs the documented production build
- **THEN** the command SHALL emit the complete static site under `dist/`
- **AND** the output SHALL require no server-side execution after publication

#### Scenario: Build input is invalid
- **WHEN** source content, metadata, an internal link, a required asset, or generated HTML violates a configured publication check
- **THEN** the production build SHALL fail before deployment
- **AND** the prior published site SHALL remain unchanged

### Requirement: GitHub Actions publishes through the supported Pages contract
The repository SHALL use a least-privilege GitHub Actions workflow that builds the site, uploads the Pages artifact with GitHub’s supported artifact action, and deploys it with GitHub’s supported Pages action. Pull requests SHALL build and validate without deploying. Only the default branch SHALL deploy to the protected `github-pages` environment, and concurrent superseded deployments SHALL be cancelled without publishing mixed artifacts.

#### Scenario: Pull request changes the website
- **WHEN** a pull request modifies site source, dependencies, assets, or publication configuration
- **THEN** GitHub Actions SHALL run the complete build and validation path
- **AND** it SHALL NOT update the public Pages deployment

#### Scenario: Valid change reaches the default branch
- **WHEN** the default branch receives a commit whose build and validation succeed
- **THEN** GitHub Actions SHALL publish exactly that build artifact to GitHub Pages
- **AND** the deployment record SHALL identify the source commit

#### Scenario: Default-branch build fails
- **WHEN** the default-branch workflow fails before a Pages artifact is accepted
- **THEN** the workflow SHALL report failure
- **AND** the previously successful Pages deployment SHALL remain public

### Requirement: Publication validation exercises the built site
The publication checks SHALL inspect built output rather than source text alone. They SHALL verify HTML structure, canonical metadata, required brand assets, internal links, absence of placeholder URLs, and absence of accidental former-name or analytics references. Browser verification SHALL exercise the homepage at representative desktop and narrow mobile viewports, keyboard navigation, reduced motion, and absence of unexpected console or failed-resource errors.

#### Scenario: Candidate build is verified
- **WHEN** a production candidate is prepared for publication
- **THEN** automated checks SHALL validate the static output
- **AND** browser verification SHALL demonstrate the homepage’s primary content and navigation at desktop and mobile sizes

### Requirement: Domain and DNS operations remain manual
The repository and workflow SHALL NOT create or mutate registrar settings, DNS records, GitHub organization domain verification, GitHub Pages custom-domain settings, or certificate configuration. The built site SHALL accept one configured canonical origin so the maintainer can update site metadata after manually associating the domain. A source `CNAME` file SHALL NOT be treated as proof that GitHub has configured the custom domain.

#### Scenario: Initial Pages deployment succeeds before domain association
- **WHEN** the website first deploys to the organization’s default GitHub Pages address
- **THEN** it SHALL be usable at that Pages address
- **AND** the workflow SHALL make no DNS or custom-domain API request

#### Scenario: Maintainer associates the custom domain
- **WHEN** the maintainer manually completes domain verification, DNS, and the Pages custom-domain setting
- **THEN** the site configuration MAY be updated to emit the selected canonical origin
- **AND** no domain credential SHALL enter repository source or Actions secrets

### Requirement: Initial publication has no mutable backend
The publication SHALL consist only of static HTML, CSS, JavaScript, fonts, images, metadata, and machine-readable static files. It SHALL NOT provision a database, server function, worker, account system, submission endpoint, plugin registry, package host, analytics service, or mutable API.

#### Scenario: Deployed files are enumerated
- **WHEN** the Pages artifact is inspected
- **THEN** every runtime resource SHALL be a static file or an intentional external navigation destination
- **AND** no browser code SHALL depend on a private service or secret

### Requirement: Publication can be rolled back from source history
Every production deployment SHALL be reproducible from a source commit. Restoring a previously successful source commit and rerunning the Pages workflow SHALL restore its static website behavior without data migration or backend rollback.

#### Scenario: Published change must be reverted
- **WHEN** a maintainer restores the source from a previously successful deployment and runs the publication workflow
- **THEN** GitHub Pages SHALL publish the restored static artifact
- **AND** no user-data migration or server rollback SHALL be required
