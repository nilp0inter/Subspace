## 1. Logging Infrastructure

- [x] 1.1 Define `LogLevel`, `LogEntry` data structures, and the `SubspaceLogger` interface/object
- [x] 1.2 Implement disk-backed circular log buffer writing with dual-file rotation (limiting logs to 2 MB)
- [x] 1.3 Implement asynchronous Coroutine Channel log dispatcher on `Dispatchers.IO` to ensure non-blocking log operations
- [x] 1.4 Expose logs as a reactive `StateFlow` and provide `clear` and `setLogLevel` operations in `PttForegroundService`

## 2. Logger Interception

- [x] 2.1 Replace direct imports of `android.util.Log` with `SubspaceLogger` in routing files (`ScoAudioController.kt`, `PttAudioRouteResolver.kt`)
- [x] 2.2 Replace direct imports of `android.util.Log` with `SubspaceLogger` in BLE files (`SleepwalkerBleConnection.kt`, `SleepwalkerTextOutputService.kt`)
- [x] 2.3 Replace direct imports of `android.util.Log` with `SubspaceLogger` in capabilities and runtimes (`BuiltInCapabilityAdapters.kt`, `KeyboardRuntimeFactory.kt`, `DebugRuntimeFactory.kt`, `JournalRuntimeFactory.kt`)
- [x] 2.4 Replace direct imports of `android.util.Log` with `SubspaceLogger` in dispatching files (`PttForegroundService.kt`, `PttDispatcher.kt`, `CarTelecomStarter.kt`)

## 3. UI Implementation (Compose Screen)

- [x] 3.1 Implement the layout of `LogAnalysisScreen.kt` with a scrollable list of logs, level toggle chips, and a tag filter dropdown
- [x] 3.2 Add search input field for log messages and a clear logs button
- [x] 3.3 Add visual configuration controls: font size scaling (+ / -) and layout formatting (Compact vs. Detailed)
- [x] 3.4 Add runtime log level configuration sliders/dropdowns for setting active log thresholds globally and per-tag

## 4. Routing & Shortcut Navigation

- [x] 4.1 Define the `LogAnalysis` route in `DashboardRoute` in `MainActivity.kt`
- [x] 4.2 Add long-press handler to the "SUBSPACE" application title in `TerminalHeader` to navigate to the `LogAnalysis` screen
- [x] 4.3 Update back-handler navigation to return from the log analysis view to the main dashboard screen

## 5. Verification & Testing

- [x] 5.1 Add unit tests for `SubspaceLogger` verifying log level filtering and circular disk log rotation
- [x] 5.2 Add unit tests for the asynchronous log channel verifying that logging calls on critical threads are non-blocking
- [x] 5.3 Add UI or state projection tests to verify that log filters and configurations update correctly
