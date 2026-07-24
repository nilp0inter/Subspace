# Production construction map

Date: 2026-07-23

- Package validator: `InstalledPackageStore.loadAndMaterialize` reparses immutable archive bytes through `PackageValidator`; `InstalledPackagesCoordinator` creates the store/repository production path.
- Package materializer: `LuaPackageMaterializer.materialize` compiles validated configuration and capabilities and constructs `LuaChannelImplementationProvider` without Lua execution.
- Provider registry: `PttForegroundService` creates `ChannelImplementationProviderRegistry`, registers built-in providers, and gives the same registry to `InstalledPackagesCoordinator` for installed Lua publication.
- Runtime registry: `PttForegroundService` creates `RuntimeInvocationBoundary` and `ChannelRuntimeRegistry`; `ChannelRuntimeRegistry.reconcile` constructs and retires provider runtime generations.
- Capability host: `PttForegroundService` constructs `ServiceChannelCapabilityHost`; `RevocableChannelCapabilityScope` binds acquisitions to instance/generation identities; `ServiceChannelCapabilityHost` maps `CapabilityKey.TextOutput` to the shared output service.
- Configuration editor: `MainActivity` supplies the dynamic-choice resolver to `ChannelConfigurationScreen`; the screen resolves `ChannelConfigurationField.DynamicChoiceField` values through the generic resolver while retaining persisted scalar values.
- Dynamic-choice sources: `PttForegroundService` constructs the service resolver/facade from OpenAI profile/model sources and Sleepwalker/keymap-derived text-output profiles.
- Shared Sleepwalker output: `PttForegroundService` creates one `SleepwalkerTextOutputService`; `ServiceChannelCapabilityHost` leases instance-bound semantic capabilities from it; `KeyboardBuiltInProvider`/`KeyboardRuntime` use those leases through the runtime registry rather than owning the service.
- Native actor: `LuaChannelImplementationProvider` constructs `LuaAdapterRuntime`, which owns `ActorRuntime` backed by `LuaNativeKernelBridge`; host operations cross the existing typed claim/resume/cancel JNI boundary.

These are the only production composition roots for the change. Package identity, label, repository, and implementation ID are not construction discriminators.
