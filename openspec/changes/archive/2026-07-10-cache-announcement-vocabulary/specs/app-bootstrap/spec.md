## MODIFIED Requirements

### Requirement: Core readiness gates dashboard entry
The system SHALL enter the dashboard only after all required core readiness conditions have completed successfully: runtime permissions are granted, all required model assets pass full verification, native STT and TTS engines report `Ready`, required model-backed controllers are constructed, and every required system-announcement phrase is cached as non-empty SCO-ready audio. Validated persisted announcement PCM SHALL satisfy the phrase-cache condition only after it has been hydrated into the in-memory announcement map; persistent cache reuse SHALL NOT bypass native STT/TTS readiness, controller construction, or complete-vocabulary validation.

#### Scenario: Disk assets valid but native engine loading
- **WHEN** model files pass integrity verification but either native speech engine has not reported `Ready`
- **THEN** the loading surface remains visible
- **AND** the dashboard is not shown

#### Scenario: Engines ready but controllers pending
- **WHEN** STT and TTS engines are ready but any required controller has not been successfully constructed
- **THEN** the loading surface remains visible
- **AND** the dashboard is not shown

#### Scenario: Announcements partially rendered
- **WHEN** fewer than all required system-announcement phrases are cached as non-empty SCO-ready audio
- **THEN** bootstrap is not considered ready
- **AND** the loading surface reports announcement-rendering progress

#### Scenario: Core readiness complete
- **WHEN** every core readiness condition completes successfully
- **THEN** the system automatically replaces loading with the dashboard
- **AND** it does not wait for an acknowledgement or for a loading animation to finish

#### Scenario: Valid disk hydration completes the announcement gate
- **WHEN** the authoritative announcement manifest contains valid current PCM for every vocabulary key and the native engines, permissions, models, and controllers are ready
- **THEN** the coordinator hydrates every phrase into the in-memory announcement map
- **AND** the announcement gate completes without invoking synthesis
- **AND** the dashboard is shown only after the complete hydrated map satisfies the existing SCO-ready condition

#### Scenario: Invalid disk hydration does not complete readiness
- **WHEN** one or more persisted announcement entries are missing, malformed, empty, or fail fingerprint or WAV integrity validation
- **THEN** those entries are treated as synthesis misses
- **AND** the loading surface remains visible until replacement non-empty SCO PCM is installed for every current key
- **AND** the dashboard is not shown from a partial or stale persisted map

### Requirement: Loading reports observed work
The loading surface SHALL identify the current observed bootstrap stage and SHALL display measurable progress only when the underlying operation reports a real count or byte total. The system SHALL NOT manufacture a combined percentage, random log output, or fictional initialization activity. Announcement rendering progress SHALL count validated disk-hydrated logical keys as completed units, SHALL preserve vocabulary iteration order, and SHALL report grouped exact-text misses using the first key in each group without inflating synthesis-call counts.

#### Scenario: Model file download reports bytes
- **WHEN** a model file download reports bytes read and a positive total byte count
- **THEN** loading displays the current file or model set and corresponding measurable progress

#### Scenario: Announcement rendering reports phrase count
- **WHEN** system announcements are being rendered
- **THEN** loading displays the number of successfully rendered phrases and the vocabulary-derived total

#### Scenario: Stage has no measurable total
- **WHEN** a bootstrap stage reports no meaningful total
- **THEN** loading identifies the stage without presenting a fabricated percentage

#### Scenario: Disk hits seed logical-key progress
- **WHEN** a vocabulary has validated persisted hits for some logical keys before its remaining phrases are rendered
- **THEN** loading reports those hit logical keys as already completed
- **AND** the first miss starts from that completed count against the full vocabulary-derived total
- **AND** an all-hit vocabulary transitions from `WaitingForTts` directly to ready without a rendering stage

#### Scenario: Exact-text misses report grouped progress
- **WHEN** multiple missing logical keys have identical phrase text and occur in first-occurrence vocabulary order
- **THEN** loading reports one rendering unit with the first logical key in that text group
- **AND** after non-empty PCM is installed for every key in the group, completed progress increases by that group's logical-key count
- **AND** a failed group is not included in completed progress

### Requirement: Bootstrap failures terminate in recovery
The system SHALL convert prerequisite inspection errors, native engine failures, controller construction failures, announcement rendering failures, and finite-stage timeouts into an explicit recovery state containing the failed stage, a concrete diagnostic, and a retry action when retry is safe. It SHALL NOT leave the user on an infinite loading indicator or misclassify an autonomous failure as initial setup. Retry SHALL cancel and join the prior structured bootstrap attempt before discarding its controllers or starting replacement prerequisite/core work, and a canceled prior attempt SHALL NOT commit announcement cache state after its replacement begins.

#### Scenario: Native model reports failure
- **WHEN** either native speech engine reports `Failed`
- **THEN** loading changes to recovery for that engine
- **AND** the dashboard is not shown
- **AND** a diagnostic and retry action are presented

#### Scenario: Finite stage times out
- **WHEN** a finite core-initialization stage exceeds its configured timeout
- **THEN** loading changes to recovery with the timed-out stage identified
- **AND** retry does not leave jobs or controllers from the previous attempt active

#### Scenario: Announcement phrase fails
- **WHEN** any required announcement phrase fails to produce non-empty SCO-ready audio
- **THEN** bootstrap changes to recovery with the failed phrase or stage identified
- **AND** a runtime beep fallback does not cause bootstrap to report success

#### Scenario: Retry succeeds
- **WHEN** the user retries a recoverable bootstrap failure and all core conditions subsequently complete
- **THEN** the system automatically enters the dashboard

#### Scenario: Retry waits for the prior attempt to finish cancellation
- **WHEN** a prior bootstrap attempt is synthesizing with a blocking operation that ignores cancellation until it returns and the user requests retry
- **THEN** the retry job calls `cancelAndJoin` on the prior attempt before discarding controllers
- **AND** the replacement attempt starts only after the prior attempt has completed cancellation and cleanup
- **AND** the loading state does not report replacement progress before that handoff is complete

#### Scenario: Stale attempt cannot commit after replacement starts
- **WHEN** a canceled prior attempt returns from blocking synthesis after a replacement retry has been scheduled
- **THEN** the prior attempt observes cancellation before entry or manifest promotion
- **AND** it cannot replace the authoritative announcement manifest
- **AND** only the replacement attempt may publish cache state and reach dashboard readiness

#### Scenario: Concurrent retries share one replacement attempt
- **WHEN** multiple retry actions occur while the first retry job is joining the prior attempt
- **THEN** subsequent retry actions do not create another replacement attempt
- **AND** controller discard and cache publication occur once for the tracked replacement
- **AND** a cancellation clears both tracked attempt and retry jobs
