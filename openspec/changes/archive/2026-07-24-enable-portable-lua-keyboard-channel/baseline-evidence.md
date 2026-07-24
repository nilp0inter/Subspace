# Built-in Keyboard baseline

Date: 2026-07-23

## JVM unit behavior

Command:

```text
gradle :app:testDebugUnitTest \
  --tests dev.nilp0inter.subspace.channel.KeyboardBuiltInProviderTest \
  --tests dev.nilp0inter.subspace.channel.BuiltInRuntimesTest \
  --tests dev.nilp0inter.subspace.channel.SleepwalkerTextOutputServiceTest \
  --tests dev.nilp0inter.subspace.service.KeyboardTerminalDeliveryTest \
  --tests dev.nilp0inter.subspace.service.ServiceIntegrationTest
```

Result: `BUILD SUCCESSFUL in 23s`; 44 actionable tasks, 1 executed and 43 up-to-date.

The focused classes cover the built-in provider/profile projection, runtime preparation and independent profiles, transcription/trailing-space/SOS behavior, shared Sleepwalker preparation and terminal delivery, and service-level lifecycle integration.

## Instrumentation baseline

No source under `app/src/androidTest` references Keyboard, `builtin:keyboard`, `KeyboardRuntime`, or text output. Therefore the current built-in Keyboard has no dedicated instrumentation test result to preserve.

`adb devices` reported no attached devices, so no device instrumentation or physical behavior claim is recorded in this baseline. Physical side-by-side acceptance remains explicitly required by tasks 15.1-15.8.
