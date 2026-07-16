# Implementation Evidence

## Baseline service surface

Recorded before the first responsibility cutover. Line numbers refer to the pre-cutover
`app/src/main/java/dev/nilp0inter/subspace/service/PttForegroundService.kt` snapshot.

### Android and binder surface

| Service symbol | Definition | Current caller |
|---|---:|---|
| `LocalBinder.service()` | 337-339 | `MainActivity.kt:58`; `CarMediaSessionService.kt:75`; `MainActivityServiceLifecycleTest.kt:369-370` |
| `ACTION_START_MONITORING` | 1855 | `MainActivity.kt:188-190,460-462`; `CarMediaSessionService.kt:98-100`; `SubspaceConnectionService.kt:16-17`; `MainActivityServiceLifecycleTest.kt:223-224,344-345` |
| `onCreate()` | 389 | Android framework through the start paths above |
| `onBind(Intent?)` | 922 | Android framework through `MainActivity.kt:463-466`; `CarMediaSessionService.kt:106-110`; lifecycle instrumentation temporary bind |
| `onStartCommand(Intent?, Int, Int)` | 924 | Android framework through `ACTION_START_MONITORING` intents |
| `onDestroy()` | 939 | Android framework |

### Stable observable properties

| Service symbol | Definition | Current caller |
|---|---:|---|
| `appState` | 191 | `MainActivity.kt:77`; `CarMediaSessionService.kt:155,174,278` |
| `bootstrapState` | 206 | `MainActivity.kt:85` |
| `modelAcquisitionProgress` | 210 | `MainActivity.kt:87` |
| `isCapturing` | 213 | `MainActivity.kt:83` |
| `level` | 214 | `MainActivity.kt:81` |
| `channelBrowseEntries` | 224 | `CarMediaSessionService.kt:155,173` |
| `profileUiState` | 254 | `MainActivity.kt:91` |
| `dynamicChoiceResolver` | 256 | `MainActivity.kt:93` |
| `channelDescriptors` | 261 | `MainActivity.kt:89` |
| `repository` | 340 | `MainActivity.kt:79,273,277,281` |
| `logEntries` | 343 | `MainActivity.kt:99` |
| `globalLogLevelFlow` | 346 | `MainActivity.kt:101` |
| `tagLogLevelsFlow` | 349 | `MainActivity.kt:103` |

### Stable commands and callbacks

| Service symbol | Definition | Current caller |
|---|---:|---|
| `clearLogs()` | 352 | `MainActivity.kt:422` |
| `setGlobalLogLevel(LogLevel)` | 354 | `MainActivity.kt:424` |
| `setTagLogLevel(String, LogLevel)` | 356 | `MainActivity.kt:426-427` |
| `clearTagLogLevel(String)` | 358 | `MainActivity.kt:429` |
| `refreshCarHfpConfiguration()` | 362 | `MainActivity.kt:178,215` |
| `selectCarHfpCandidate(String)` | 368 | `MainActivity.kt:182` |
| `createProfile(...)` | 375 | `MainActivity.kt:287` |
| `updateProfile(...)` | 377 | `MainActivity.kt:292` |
| `deleteProfile(String)` | 379 | `MainActivity.kt:296` |
| `testProfile(String)` | 381 | `MainActivity.kt:299-300` |
| `refreshProfile(String)` | 385 | `MainActivity.kt:303-304` |
| `refreshBootstrapPrerequisites()` | 910 | `MainActivity.kt:60,126,146` |
| `startModelAcquisition()` | 914 | `MainActivity.kt:340` |
| `retryBootstrap()` | 918 | `MainActivity.kt:317` |
| `refreshReadiness()` | 1030 | `MainActivity.kt:59,195,472` |
| `scanForDevice()` | 1054 | `MainActivity.kt:170` |
| `pairTarget()` | 1076 | `MainActivity.kt:174` |
| `connectSerial()` | 1094 | `MainActivity.kt:191` |
| `disconnectSerial()` | 1103 | `MainActivity.kt:199`; `MainActivityServiceLifecycleTest.kt:202` |
| `createChannel(...)` | 1124 | `MainActivity.kt:262` |
| `updateChannelConfiguration(...)` | 1152 | `MainActivity.kt:269` |
| `selectChannel(String)` | 1174 | `MainActivity.kt:203` |
| `startPhonePtt(String)` | 1223 | `MainActivity.kt:250` |
| `phonePttReleased(String)` | 1230 | `MainActivity.kt:254` |
| `setInputMode(InputMode)` | 1234 | `MainActivity.kt:207` |
| `onCarPttStart()` | 1295 | `CarPttCommandBus.kt:20` |
| `onCarPttRelease()` | 1300 | `CarPttCommandBus.kt:24` |
| `onTelecomCaptureStart()` | 1304 | `TelecomCarPttCoordinator.kt:14` |
| `onTelecomCaptureStop()` | 1308 | `TelecomCarPttCoordinator.kt:18` |
| `onTelecomRouteTimeout()` | 1311 | `TelecomCarPttCoordinator.kt:22` |
| `onTelecomConnectionEnded()` | 1322 | `TelecomCarPttCoordinator.kt:31,38` |
| `onCarSetActiveChannel(String)` | 1430 | `CarPttCommandBus.kt:28` |
| `onCarSetActiveChannelOffset(Int)` | 1434 | `CarPttCommandBus.kt:38` |
| `onCarSkipMessage()` | 1438 | `CarPttCommandBus.kt:48` |
| `onCarReplayMessage()` | 1442 | `CarPttCommandBus.kt:57` |
| `prepareInput(String)` | 1850 | `PttAudioSessionManager.kt:266` through `ChannelRouter` |

### Core initialization contract

| Service symbol | Definition | Current caller |
|---|---:|---|
| `constructSttTranscriber()` | 758 | `BootstrapCoordinator.kt:365` through `CoreInit` |
| `constructTtsSynthesizer()` | 787 | `BootstrapCoordinator.kt:394` through `CoreInit` |
| `constructTtsController(...)` | 805 | `BootstrapCoordinator.kt:410` through `CoreInit` |
| `constructJournalPttController()` | 835 | `BootstrapCoordinator.kt:306` through `CoreInit` |
| `initializeTextOutputCapability()` | 854 | `BootstrapCoordinator.kt:381` through `CoreInit` |
| `prepareNavigationTts()` | 858 | `BootstrapCoordinator.kt:247` through `CoreInit` |
| `discardControllers()` | 892 | `BootstrapCoordinator.kt:156` through `CoreInit` |

### Currently uncalled service members

`tagLogLevels()`, `globalLogLevel()`, `setActiveChannelId`, `setActiveChannelOffset`,
`skipCurrentMessage`, `replayLastHeard`, `setTtsText`, `setTtsVoiceStyle`, `setTtsLang`,
`setTtsTotalSteps`, `setTtsSpeed`, `setSttTtsVoiceStyle`, `setSttTtsLang`,
`setSttTtsTotalSteps`, `setSttTtsSpeed`, and `requestTtsSynthesis` had no caller under
`app/src` at baseline. They are not assumed stable solely because they are class members.

## Baseline mutable ownership

| Ownership unit | Service-owned state/resources before cutover | Construction | Teardown |
|---|---|---|---|
| Service coroutine lifetime | `serviceScope` | 189 | `serviceScope.cancel()` at 977 |
| Bootstrap | `modelRepository`, `bootstrapCoordinator` | 683-690 | `cancelAttempt()` at 962 |
| Bluetooth/HFP | `bluetoothAdapter`, `headsetProxy`, `scanner`, `readinessProbe`, `carHfpConfigurationStore`, `carHfpConfigurationController`, `targetDevice` | 396-410; 1033; 1062; 1084; 1696 | profile proxy close/null at 979-980 |
| OpenAI profile subsystem | repositories, clients, model discovery, operations, façade | 422-434 | operations close at 956 |
| Channel/runtime graph | provider registry, invocation boundary, runtime registry, capability host, text output, agent graph, journal controller/backends | 412; 446; 486; 532; 548-557; 847 | ordered closes at 949-958; journal discard at 901-902 |
| Audio/Telecom | SCO, PCM outputs, capture sources, media player, Telecom registrar, route resolver, audio-session manager, host coordinator, dispatcher, car starter | 456-485; 564-606 | dispatcher teardown at 943-946; host close at 952; scope cancellation at 977 |
| STT/TTS | model dirs, transcriber, transcription service, synthesizer/controller, model pollers, navigation engine | 762-871 | discard at 894-905; destroy backup at 950-951,974-976 |
| Serial/reconnect | `targetDevice`, `sppClient`, `serialJob`, `sppStateJob`, `reconnectJob`, `ReconnectPolicy` | 1557-1581; 1684 | explicit disconnect at 1109-1116; destroy at 968-973 |
| Foreground/readiness | `foreground`, `stopWhenPttIdleAfterSerialDisconnect`, `readinessRefreshJob` | flags at 323-324; loop at 1807 | stop transitions at 1762,1774,1787; loop cancel at 1820 |
| Car idle timer | `idleTimerJob` | 1282 | cancel/null at 967,1281,1290 |

## Baseline side-effect order

### `onCreate`

1. `super.onCreate`; logger initialization; asynchronous cache cleanup.
2. Bluetooth adapter/scanner/readiness and HFP configuration/proxy request.
3. Sleepwalker text output and OpenAI profile/model composition.
4. Provider registry, descriptors, repository, SCO, capture, Telecom, media and host audio.
5. PTT audio-session manager, agent runtime graph, capability host, runtime registry,
   car Telecom starter, and dispatcher.
6. Text-output, command-bus, Telecom, app-state, catalogue, and SCO collectors/listeners.
7. Bootstrap coordinator construction/start.
8. Reconnect monitoring, readiness, input-mode, and car-media publication.

### `onStartCommand`

1. For `ACTION_START_MONITORING` with monitoring requested: `ensureForeground()`.
2. Otherwise: `ensureForeground()`, `stopForegroundIfNeeded()`, `stopSelf(startId)`.
3. Return `START_NOT_STICKY`.

### Serial end and explicit disconnect

Natural end: cancel RSM PTT, release TTS, refresh readiness, then either stop foreground/service
when monitoring ended or compute/log/handle the reconnect decision. Explicit disconnect: cancel PTT,
clear monitoring and set deferred-stop state, cancel reconnect/readiness/serial collectors, disconnect
and clear SPP, publish disconnected state, release TTS, reevaluate shutdown, then refresh readiness.

### PTT terminal completion

1. Notify `PttDispatcher`.
2. Start the On-the-Road idle timer when applicable.
3. Notify immediate playback, then deferred playback.
4. Update car-media state.
5. Reevaluate deferred serial-disconnect shutdown.

### `onDestroy`

1. In a 45-second `runBlocking`/Default/timeout boundary: cancel PTT, shut down runtime
   coordinator, navigation TTS, host audio, both playback queues, runtime registry, profile
   operations, text output, and invocation boundary in that order.
2. Cancel bootstrap attempt.
3. Remove command/AA/Telecom listeners and force-abort Telecom.
4. Cancel idle/reconnect/readiness/serial/model jobs, disconnect SPP, release TTS.
5. Cancel service scope; stop foreground; close/null HEADSET proxy; `super.onDestroy()`.

## Focused baseline verification

- `gradle compileDebugKotlin`: passed.
- Focused unit classes covering channel catalogue/mutation/runtime/projection, PTT dispatch,
  reconnect and lifetime policy, readiness refresh, input mode, bootstrap, announcement text,
  navigation TTS, capture/playback, and PTT terminal completion: passed.
- Gradle UTP launch for `MainActivityServiceLifecycleTest` failed before test execution in
  `UtpTestResultListenerServerRunner.createUtpTempFile` with `java.io.IOException: No such file or
  directory`. App and test APKs were installed directly with ADB instead.
- Direct instrumentation ran all three lifecycle tests. Two passed; the disconnect test failed in
  the full-class run at its service-state wait. An isolated rerun of
  `disconnectSerialSuppressesRepeatedStartIntentUntilActivityUnbind` passed. Treat this as a
  baseline order-dependent/flaky observation, not a decomposition regression.
## Final decomposition size evidence

Measured with `cloc --by-file` after service-shell consolidation. Code-line counts are evidence,
not acceptance thresholds.

| Kotlin file | Code lines |
|---|---:|
| `PttForegroundService.kt` | 1,162 |
| `ServiceCoreInitializer.kt` | 367 |
| `RsmSerialConnectionCoordinator.kt` | 268 |
| `ForegroundServiceCoordinator.kt` | 97 |
| `ServiceChannelManager.kt` | 80 |
| `ServiceStateProjector.kt` | 56 |
| `RsmAnnouncementCoordinator.kt` | 42 |
| **Total** | **2,072** |

The service shell decreased from the recorded 1,645 Kotlin code lines to 1,162 while retaining
the explicit Android lifecycle and composition root. The added code is isolated ownership logic
and independently exercised collaborator contracts rather than duplicated service paths.

## Final verification

- The six focused collaborator unit-test classes passed after cleanup:
  `ServiceChannelManagerTest`, `ServiceStateProjectorTest`,
  `RsmSerialConnectionCoordinatorTest`, `ForegroundServiceCoordinatorTest`,
  `ServiceCoreInitializerTest`, and `RsmAnnouncementCoordinatorTest`.
- Direct device instrumentation passed all three `MainActivityServiceLifecycleTest` cases in one
  run, including background service retention, explicit-disconnect suppression, and repeated
  activity start/stop behavior.
- The final debug APK installed and launched on physical device `a5c3b76a`. Android reported
  `minSdk=31`, `targetSdk=35`, non-SDK API use disabled, and all requested runtime Bluetooth,
  microphone, and notification permissions granted. Reinstall-with-retention preserved the
  channel catalogue and model assets; the UI displayed the persisted Debug channel and RSM-ready
  state.
- With the app backgrounded, Android retained foreground notification ID 41 titled
  `Subspace connected`; the explicit-disconnect lifecycle path was exercised by the passing
  device instrumentation.
- Physical `B02PTT-FF01` acceptance passed for PTT press/release, Group-to-Control transition,
  Control-mode PTT return, and Volume Up/Down click expiry. Phone and RSM audio routing, including
  RSM echo recording/playback, passed.
- Android Auto Telecom capture/playback and coexistence with the connected RSM remain deferred
  because the car was unavailable. OpenSpec task 9.8 remains unchecked until that physical check
  is run.
