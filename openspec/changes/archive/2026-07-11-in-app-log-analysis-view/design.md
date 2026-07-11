## Context

With the recent platform hardening (`harden-channel-platform-for-lua`), the codebase transitioned to decoupled capability hosts and isolated runtime invocation boundaries. This shift has made debugging more difficult because developers cannot easily observe trace logs (e.g., route transitions, BLE packets, capability failures) directly on the device. Connecting to a PC and running `adb logcat` is the only current diagnostic path, which introduces friction during physical device testing (specifically when the device is connected to RSM or Car hardware).

## Goals / Non-Goals

**Goals:**
- Provide a persistent, disk-backed log store that captures trace events and survives application restarts.
- Create a dedicated log analysis Compose screen for developers and testers to review, filter, and configure logs directly on the device.
- Ensure logging operations are completely non-blocking and do not introduce latency on high-priority audio/PTT paths.
- Support dynamic log level and tag configuration at runtime.
- Maintain compatibility with standard Android `Logcat` output.

**Non-Goals:**
- This UI is not for AI coding agent consumption; it is strictly formatted for human developers and testing operators.
- No remote log collection, telemetry reporting, or crashlytics integration.
- No attempt to decode or log raw PCM/audio samples.

## Decisions

### D1: Unified Logger Wrapper (`SubspaceLogger`) with Logcat Mirroring
Replace direct imports of `android.util.Log` in core packages with a custom wrapper `SubspaceLogger`. Alternatively, use an import alias:
```kotlin
import dev.nilp0inter.subspace.service.SubspaceLogger as Log
```
The wrapper delegates output to `android.util.Log` to keep `adb logcat` functional, while appending the log entries to a local circular buffer.

**Rationale:** Using a wrapper avoids introducing bulky external logging frameworks (like Timber or Logback) into the Nix flake build shell, keeping dependencies clean and compilation fast.
**Alternatives considered:**
- *Import external logging library (Timber/Logback)*: Rejected to avoid bringing new dependencies into the Nix flake and to retain full control over memory allocations on high-frequency logging paths.

### D2: Bounded Disk-Backed Log Buffer (Dual-File Rotation)
Store logs in the application's cache directory using a rotating two-file structure:
- `subspace_logs.0.log` (active write file)
- `subspace_logs.1.log` (previous log file)
When the active file exceeds 1 MB, it rotates. The total log space is bounded to 2 MB to prevent disk leakages. At startup, the background service loads the historical logs from these files into an in-memory cache to populate the initial UI stream.

**Rationale:** File-based log rotation is extremely robust, simple to implement, and guarantees that logs survive restarts and crashes without the overhead of SQLite database transactions.
**Alternatives considered:**
- *SQLite Database*: Rejected. Database connection, table serialization, and transaction write locks are too heavy for high-frequency runtime tracing.
- *Memory-only Ring Buffer*: Rejected. Does not satisfy the requirement to survive application restarts and crashes.

### D3: Asynchronous Non-Blocking Writes via Coroutine Channel
Logging calls are made from critical threads (such as the audio capture loops or BLE write threads). Writing to disk synchronously on these threads would cause audio stutter or packet drops.
`SubspaceLogger` will push log entries into a buffered Kotlin Coroutine `Channel<LogEntry>` which is drained by a single background worker on `Dispatchers.IO`.

```text
Log Call (Critical Thread) ──► [ Channel Buffer ] ──► Dispatchers.IO ──► Disk File
```

**Rationale:** Decouples logging latency from the calling thread, protecting high-priority serial BLE and PCM audio execution.
**Alternatives considered:**
- *Synchronous Disk Writing*: Rejected. Violates Android main thread restrictions and adds severe latency to high-priority execution paths.

### D4: Jetpack Compose Log Analysis Screen (`LogAnalysisScreen`)
Create a new Compose screen featuring:
- A scrollable, lazy-loaded list of logs with color-coded severity tags.
- Tag and Log Level filters.
- Message text search.
- Formatting modes: *Compact* (hides timestamps/tags to show more text) and *Detailed* (shows full metadata).
- Font size adjustment (+ / - scaling).
- Runtime Log Level settings (dynamic configuration of the minimum log level per tag or globally).

**Rationale:** Provides an easy-to-use in-app debugger interface that enables on-device troubleshooting.
**Alternatives considered:**
- *Inject logs into the main dashboard*: Rejected. Degrades dashboard layout focus and wastes rendering cycles on non-telemetry logs.

### D5: Developer Navigation Shortcut (Long-Press Title)
Long-pressing the "SUBSPACE" application title in the `TerminalHeader` on the main dashboard triggers the navigation route `DashboardRoute.LogAnalysis`.

**Rationale:** Provides an intuitive hidden gesture for developer access without cluttering the clean, hardware-focused production dashboard.
**Alternatives considered:**
- *Dedicated visible button*: Rejected. Clutters the clean dashboard visual identity.
- *Settings menu item*: Rejected. Unnecessary extra navigation steps for developers.

## Risks / Trade-offs

- **[Risk] High disk write frequency could cause battery drain or flash wear.** → Mitigation: Buffer logs in memory and write them in chunks, or drop logs below the active runtime threshold before they are written.
- **[Risk] A crash during log writing could corrupt the log file.** → Mitigation: Use a dual-file rotation system with temp-file replacement so that a corrupted active file does not affect the historical archive.

## Migration Plan

1. Implement `SubspaceLogger` and its disk-backed writer in `dev.nilp0inter.subspace.service`.
2. Update existing core imports of `android.util.Log` to point to `SubspaceLogger`.
3. Add the `LogAnalysis` route to `MainActivity.kt` and integrate the long-press gesture into `TerminalHeader`.
4. Implement `LogAnalysisScreen.kt` in `dev.nilp0inter.subspace.ui`.

No catalogue database schema changes or data migrations are required.
