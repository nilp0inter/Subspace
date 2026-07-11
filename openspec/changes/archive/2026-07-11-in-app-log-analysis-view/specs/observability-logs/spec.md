## ADDED Requirements

### Requirement: Unified Log Interception and Logcat mirroring
The system SHALL intercept log statements from all core application modules and write them to both standard Android Logcat and an in-app persistent log storage.

#### Scenario: Log written to Logcat and disk buffer
- **WHEN** a component writes a message using the logging subsystem
- **THEN** the system SHALL output that message to standard Android Logcat
- **AND** write it to the persistent circular log file

### Requirement: Disk-backed persistent circular log storage
The log storage SHALL be persistent, surviving application restarts, and SHALL be bounded in size to prevent unlimited storage growth by using a circular buffer (e.g., rotating files or fixed line capacity).

#### Scenario: Logs survive application restarts
- **WHEN** the application is restarted and the user opens the Log Analysis screen
- **THEN** the system SHALL load and display log entries captured before the restart

#### Scenario: Bounded log storage size
- **WHEN** the total size of stored logs exceeds the configured limit
- **THEN** the system SHALL evict the oldest log entries to make room for new ones

### Requirement: Reactive Log Stream publication
The background service SHALL publish log entry changes through a reactive `StateFlow` to the UI layer for real-time log inspection.

#### Scenario: Live updates on the log screen
- **WHEN** the Log Analysis screen is open and a new log entry is written by the application
- **THEN** the screen SHALL update to display the new entry in real-time

### Requirement: Log Analysis view with Level, Tag, and Search filtering
The `LogAnalysisScreen` SHALL provide interactive controls to search log messages by text, filter logs by log severity levels, and filter by tag names.

#### Scenario: Filtering logs by level
- **WHEN** the user selects the "Warn" level filter
- **THEN** the screen SHALL display only log entries with severity Warn or Error

#### Scenario: Searching logs by text
- **WHEN** the user enters a search query
- **THEN** the screen SHALL display only log entries containing the query text

### Requirement: Dynamic log level configuration at runtime
The user/developer SHALL be able to adjust the minimum logging level globally or per component tag at runtime.

#### Scenario: Changing active log level
- **WHEN** the user adjusts the runtime log level threshold for a tag
- **THEN** log messages for that tag with severity below the new threshold SHALL be ignored and NOT written to the log buffer

### Requirement: In-app log formatting and font scaling
The log screen SHALL support switching between Compact and Detailed view modes, and SHALL allow the developer to scale the font size up or down for readability.

#### Scenario: Toggling compact mode
- **WHEN** the user selects Compact view format
- **THEN** the screen SHALL hide extra metadata to show more messages in a single line
