## Why

With the transition to a hardened, capability-based runtime registry (`ChannelRuntimeRegistry`, `RuntimeInvocationBoundary`), live execution status and capability operations are decoupled from the main thread and service state. Standard debugging currently requires a computer running `adb logcat`, causing a loss of live on-device observability for developer and user testing.

## What Changes

- **In-App Observability Portal**: Introduce a dedicated screen (`LogAnalysisScreen`) accessible via the main dashboard.
- **Log Interception**: Wrap standard Android logging calls (`android.util.Log`) with a unified logger (`SubspaceLogger`) that simultaneously writes to `Logcat` and a local disk-backed buffer.
- **Disk-Backed Buffering**: Store structured log entries in a persistent, circular disk file to ensure they survive application restarts and crashes.
- **Dynamic Log Filters**: Allow developers to change log filters (severity thresholds, tags) at runtime.
- **Title Long-Press Navigation**: Add a hidden developer gesture—long-pressing the title in `TerminalHeader` on the main dashboard routes directly to the log analysis screen.
- **User-Centric Formatting**: Provide display customizations like adjustable font sizing and compact/detailed log formatting suited for human review on mobile displays.

## Capabilities

### New Capabilities
- `observability-logs`: In-app disk-backed logging subsystem with runtime level and formatting configuration, accessible via `LogAnalysisScreen`.

### Modified Capabilities
- `main-device-dashboard`: Support long-press title interaction on the header to navigate to the observability log panel.

## Impact

- **UI & Navigation**: Modifies `MainDashboardScreen.kt`, `TerminalHeader` (shared or screen-specific), and `MainActivity.kt`'s router.
- **Logging Infrastructure**: Replaces direct `android.util.Log` imports in core packages (`service`, `channel`, `audio`, `telecom`, `bluetooth`) with `SubspaceLogger`.
- **Performance & Disk I/O**: Introduces lightweight, non-blocking asynchronous disk writes for logs to prevent UI stutter or PTT capture blocking.
- **Storage**: Allocates a small, bounded directory space for circular log storage in the application cache.
