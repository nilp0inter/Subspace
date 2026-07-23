package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity
import dev.nilp0inter.subspace.dependency.InstalledPackageRepository
import dev.nilp0inter.subspace.dependency.InstalledPackageStore
import dev.nilp0inter.subspace.dependency.MaterializationResult
import dev.nilp0inter.subspace.dependency.MutationResult
import dev.nilp0inter.subspace.dependency.PackageFailure
import dev.nilp0inter.subspace.dependency.PackageOutcome
import dev.nilp0inter.subspace.dependency.PackageSourceRecord
import dev.nilp0inter.subspace.dependency.PackageValidationBounds
import dev.nilp0inter.subspace.dependency.StoredPackageRevision
import dev.nilp0inter.subspace.dependency.StoredProviderRecord
import dev.nilp0inter.subspace.dependency.toPackageUnavailable
import dev.nilp0inter.subspace.dependency.toPackageUnavailableProjection
import dev.nilp0inter.subspace.lua.LuaKernelBridge
import dev.nilp0inter.subspace.lua.PluginLogSink
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelImplementationProviderRegistry
import dev.nilp0inter.subspace.model.ChannelProviderError
import dev.nilp0inter.subspace.model.InstalledProvidersPublicationResult
import dev.nilp0inter.subspace.model.InstalledProvidersRejectionReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Host-domain state for the installed-package subsystem.
 *
 * Every non-terminal variant carries a monotonic [generation] so observers can distinguish
 * a stale publication from a current one. Generation is assigned under the coordinator
 * operation mutex immediately before the repository call begins.
 *
 * - [Loading]: packages are being hashed, validated, and materialized off the main thread.
 * - [Ready]: a complete installed-provider snapshot has been published.
 * - [Failed]: the store could not be loaded, recovered, or reconciled.
 * - [Closed]: shutdown is complete; [error] is non-null if close failed.
 */
sealed interface InstalledPackagesState {
    val generation: Long

    data class Loading(override val generation: Long) : InstalledPackagesState
    data class Ready(
        override val generation: Long,
        val snapshotRevision: Long,
    ) : InstalledPackagesState
    data class Failed(
        override val generation: Long,
        val error: PackageFailure,
    ) : InstalledPackagesState
    data class Closed(val error: PackageFailure? = null) : InstalledPackagesState {
        override val generation: Long = -1L
    }
}

/**
 * Service-owned coordinator integrating the installed-package repository into foreground
 * service composition.
 *
 * Owns one [InstalledPackageStore] and one [InstalledPackageRepository], exposing a
 * [StateFlow] of host-domain loading/ready/failed/closed states. All package I/O runs on
 * a bounded [Dispatchers.IO] boundary; built-in provider registration and foreground-service
 * startup are never blocked.
 *
 * **Generation contract:** Every operation (load, reload, mutation) acquires [operationMutex],
 * increments [generation], and records [currentGeneration] before calling the repository.
 * The injected publisher lambda reads [currentGeneration] to decide whether state publication
 * is still valid for the operation that initiated it. This prevents an older load from
 * publishing after a newer generation begins.
 *
 * **Publication ordering:** The publisher atomically replaces the installed-provider
 * snapshot (valid bindings + typed unavailable failures) in the registry, then reconciles
 * the current catalogue **outside** registry synchronization. A reconcile failure after a
 * successful commit sets [Failed] with [PackageFailure.LoadingDetail.RECONCILIATION_FAILED];
 * the committed index remains authoritative for the next recovery.
 *
 * Lua remains dormant: no Lua state or actor is created during loading, materialization, or
 * provider registration.
 */
internal class InstalledPackagesCoordinator(
    storeRoot: File,
    private val providerRegistry: ChannelImplementationProviderRegistry,
    bridge: LuaKernelBridge,
    logSink: PluginLogSink,
    runtimeResourcesFactory: dev.nilp0inter.subspace.lua.LuaRuntimeResourcesFactory? = null,
    private val onCatalogueReconcile: suspend () -> Unit,
    private val serviceScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    bounds: PackageValidationBounds = PackageValidationBounds.DEFAULT,
) {
    private val store = if (runtimeResourcesFactory == null) {
        InstalledPackageStore(storeRoot, logSink)
    } else {
        InstalledPackageStore(storeRoot, logSink, runtimeResourcesFactory)
    }
    private val closed = AtomicBoolean(false)

    private val operationMutex = Mutex()
    /**
     * Publication mutex shared with shutdown. Both [publishMaterialization] and [shutdown]
     * acquire this mutex, guaranteeing that the publication+reconcile block and the
     * shutdown gate are mutually exclusive. Shutdown wins the boundary before setting
     * [closed] and calling [InstalledPackageRepository.requestClose]; no swap or reconcile
     * can start once shutdown has acquired this mutex.
     */
    private val publicationMutex = Mutex()
    private val generation = AtomicLong(0L)

    /** Generation of the operation currently holding [operationMutex]. -1 = idle. */
    @Volatile
    private var currentGeneration: Long = -1L

    private val _state = MutableStateFlow<InstalledPackagesState>(
        InstalledPackagesState.Loading(0L)
    )
    val state: StateFlow<InstalledPackagesState> = _state.asStateFlow()

    private val repository: InstalledPackageRepository = InstalledPackageRepository(
        store = store,
        bridge = bridge,
        publisher = ::publishMaterialization,
        dispatcher = ioDispatcher,
        bounds = bounds,
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Public operations — all serialized through [operationMutex]
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Launches asynchronous load/recovery on the service-owned I/O boundary.
     * Non-blocking: returns immediately after launching the load coroutine.
     */
    fun start() {
        serviceScope.launch {
            executeOperation(failClosedOnFailure = true) { gen ->
                _state.value = InstalledPackagesState.Loading(gen)
                repository.loadAndPublish()
            }
        }
    }

    /** Re-runs recovery from the committed index and republishes. */
    suspend fun reload() {
        executeOperation(failClosedOnFailure = true) { gen ->
            _state.value = InstalledPackagesState.Loading(gen)
            repository.recover()
        }
    }

    suspend fun installOrUpdate(
        inputStream: InputStream,
        sourceRecord: PackageSourceRecord,
    ): PackageOutcome<MutationResult> =
        executeOperation { repository.installOrUpdate(inputStream, sourceRecord) }

    suspend fun rollback(repositoryId: GitHubRepositoryIdentity): PackageOutcome<MutationResult> =
        executeOperation { repository.rollback(repositoryId) }

    suspend fun remove(repositoryId: GitHubRepositoryIdentity): PackageOutcome<MutationResult> =
        executeOperation { repository.remove(repositoryId) }

    suspend fun shutdown() {
        publicationMutex.withLock {
            if (!closed.compareAndSet(false, true)) return@withLock
            repository.requestClose()
        }
        val closeOutcome = try {
            repository.closeAndAwait(SHUTDOWN_AWAIT_MILLIS)
        } catch (e: Exception) {
            PackageOutcome.Failure(
                PackageFailure.Shutdown(PackageFailure.ShutdownDetail.TRANSACTION_ABORTED)
            )
        }
        val closeError = (closeOutcome as? PackageOutcome.Failure)?.error
        if (closeError != null) {
            SubspaceLogger.w(
                DIAGNOSTIC_TAG,
                "phase=shutdown outcome=failed detail=${closeError.category}"
            )
        } else {
            SubspaceLogger.i(DIAGNOSTIC_TAG, "phase=shutdown outcome=ok")
        }
        _state.value = InstalledPackagesState.Closed(closeError)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Operation serialization + generation tracking
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Acquires [operationMutex], assigns a fresh monotonic generation, and runs [block].
     * On failure, sets [Failed] state if the generation is still current. The publisher
     * lambda (called from within [block]) independently sets [Ready] state.
     */
    private suspend fun <T> executeOperation(
        failClosedOnFailure: Boolean = false,
        block: suspend (gen: Long) -> PackageOutcome<T>,
    ): PackageOutcome<T> {
        return operationMutex.withLock {
            if (closed.get()) {
                return@withLock PackageOutcome.Failure(
                    PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                )
            }
            val gen = generation.incrementAndGet()
            currentGeneration = gen
            val result = block(gen)
            if (currentGeneration == gen && !closed.get()) {
                when (result) {
                    is PackageOutcome.Failure -> {
                        if (result.error !is PackageFailure.Shutdown) {
                            // For store-level failures during start/reload, publish fail-closed
                            // before exposing Failed so installed-provider IDs resolve as unavailable
                            // rather than missing. Only do this for load errors where the publisher
                            // was never reached (state is still Loading).
                            if (failClosedOnFailure &&
                                _state.value is InstalledPackagesState.Loading &&
                                result.error !is PackageFailure.Loading) {
                                publishStoreUnavailable(gen, result.error)
                            }
                            // Mutation validation failures must not clear a healthy snapshot.
                            val isPublicationRejection = result.error is PackageFailure.Loading &&
                                result.error.detail == PackageFailure.LoadingDetail.PUBLICATION_REJECTED
                            val hasHealthySnapshot = _state.value is InstalledPackagesState.Ready
                            if (!isPublicationRejection || !hasHealthySnapshot) {
                                _state.value = InstalledPackagesState.Failed(gen, result.error)
                            }
                        }
                    }
                    is PackageOutcome.Success -> {
                        when (val value = result.value) {
                            is MutationResult.PublicationFailure -> {
                                // With shutdown serialization, PublicationFailure wraps SHUTDOWN_IN_PROGRESS;
                                // don't clear a healthy snapshot if one exists.
                                if (_state.value !is InstalledPackagesState.Ready) {
                                    _state.value = InstalledPackagesState.Failed(gen, value.error)
                                }
                            }
                            is MutationResult.Reinstalled -> {
                                _state.value = InstalledPackagesState.Ready(
                                    gen,
                                    providerRegistry.snapshotRevision
                                )
                            }
                            else -> { /* Installed/Updated/RolledBack/Removed: publisher set state */ }
                        }
                    }
                }
            }
            result
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Publisher lambda — injected into InstalledPackageRepository
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Called from within the repository's transaction serialization after a successful
     * commit. Atomically replaces the installed-provider snapshot, reconciles the catalogue
     * outside registry sync, and emits structured diagnostics.
     *
     * Only publishes state for the operation that is currently holding [operationMutex]
     * (identified by [currentGeneration]).
     */
    private suspend fun publishMaterialization(result: MaterializationResult): PackageOutcome<Unit> {
        return publicationMutex.withLock {
            val gen = currentGeneration

            if (closed.get()) {
                return@withLock PackageOutcome.Failure(
                    PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                )
            }

            val unavailable = LinkedHashMap<ChannelImplementationId, ChannelProviderError.PackageUnavailable>()
            for ((id, failure) in result.failures) {
                unavailable[id] = failure.toPackageUnavailable(id)
            }

            // Atomic publication: registry internally synchronized.
            val publicationResult = providerRegistry.publishInstalledProviders(
                candidate = result.bindings,
                unavailable = unavailable,
            )

            val outcome: PackageOutcome<Unit> = when (publicationResult) {
                is InstalledProvidersPublicationResult.Success -> {
                    emitPublicationDiagnostics(result, unavailable, publicationResult.snapshotRevision)
                    // Catalogue reconciliation OUTSIDE provider-registry synchronization.
                    val reconcileOutcome = runCatching { onCatalogueReconcile() }
                    if (reconcileOutcome.isFailure) {
                        SubspaceLogger.e(
                            DIAGNOSTIC_TAG,
                            "phase=reconcile rev=${publicationResult.snapshotRevision} " +
                                "outcome=failed"
                        )
                        return@withLock PackageOutcome.Failure(
                            PackageFailure.Loading(PackageFailure.LoadingDetail.RECONCILIATION_FAILED)
                        )
                    }
                    // Only set Ready if this operation generation is still current.
                    if (currentGeneration == gen && !closed.get()) {
                        _state.value = InstalledPackagesState.Ready(gen, publicationResult.snapshotRevision)
                    }
                    PackageOutcome.Success(Unit)
                }
                is InstalledProvidersPublicationResult.Rejected -> {
                    val code = mapRejectionCode(publicationResult.error)
                    SubspaceLogger.e(
                        DIAGNOSTIC_TAG,
                        "phase=publish outcome=rejected code=$code"
                    )
                    PackageOutcome.Failure(
                        PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED)
                    )
                }
            }
            return@withLock outcome
        }
    }

    /**
     * Fail-closed publication for store-level failures during coordinator start/reload.
     * Publishes a global installed-store failure template so that every canonical
     * github-repository ID without an explicit entry resolves as [PackageUnavailable]
     * with the category/detail derived from the original failure. Runs catalogue
     * reconciliation after the registry state is updated; reconcile failures are
     * diagnosed but do not abort the transition to [Failed].
     *
     * Must be called from within [executeOperation] (holds [operationMutex]) and
     * acquires [publicationMutex] to serialize with shutdown.
     */
    private suspend fun publishStoreUnavailable(gen: Long, error: PackageFailure) {
        publicationMutex.withLock {
            if (closed.get()) return@withLock
            val unavailable = error.toPackageUnavailableProjection()
            val pubResult = providerRegistry.publishFailClosed(unavailable.category, unavailable.detail)
            if (pubResult is InstalledProvidersPublicationResult.Success) {
                SubspaceLogger.w(
                    DIAGNOSTIC_TAG,
                    "phase=fail-closed gen=$gen rev=${pubResult.snapshotRevision} " +
                        "category=${unavailable.category} detail=${unavailable.detail}"
                )
            }
            val reconcileOutcome = runCatching { onCatalogueReconcile() }
            if (reconcileOutcome.isFailure) {
                SubspaceLogger.e(
                    DIAGNOSTIC_TAG,
                    "phase=fail-closed-reconcile gen=$gen outcome=failed"
                )
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Structured diagnostic sink
    //
    // Emits ONLY: provider ID, digest prefix, numeric source release/asset ID,
    // publication phase, typed category/detail, outcome.
    // NEVER emits: Lua source, config payloads, credentials, message text, audio,
    // raw archive bytes, MACs, release tags, asset names, coordinates, or mutable
    // filesystem paths outside the package root.
    // ──────────────────────────────────────────────────────────────────────────

    private fun emitPublicationDiagnostics(
        result: MaterializationResult,
        unavailable: Map<ChannelImplementationId, ChannelProviderError.PackageUnavailable>,
        snapshotRevision: Long,
    ) {
        for ((id, binding) in result.bindings) {
            val record = result.records[id]
            val digestPrefix = binding.expectedDigest.value.take(DIGEST_PREFIX_LENGTH)
            val sourceIds = record?.let { formatSourceIds(it) } ?: "source=unknown"
            SubspaceLogger.i(
                DIAGNOSTIC_TAG,
                "phase=publish rev=$snapshotRevision provider=$id " +
                    "digest=$digestPrefix $sourceIds outcome=available"
            )
        }
        for ((id, error) in unavailable) {
            val record = result.records[id]
            val digestPrefix = record?.digest?.value?.take(DIGEST_PREFIX_LENGTH) ?: "unknown"
            val sourceIds = record?.let { formatSourceIds(it) } ?: "source=unknown"
            SubspaceLogger.w(
                DIAGNOSTIC_TAG,
                "phase=publish rev=$snapshotRevision provider=$id " +
                    "digest=$digestPrefix $sourceIds " +
                    "outcome=unavailable category=${error.category} detail=${error.detail}"
            )
        }
    }

    /**
     * Formats numeric source release/asset IDs only. Never logs tag, name, or coordinates.
     */
    private fun formatSourceIds(record: StoredPackageRevision): String {
        val releaseId = record.sourceRecord.release.releaseId
        val assetId = record.sourceRecord.asset.assetId
        return "release=$releaseId asset=$assetId"
    }

    /**
     * Maps exact typed rejection reason to a bounded diagnostic code string.
     * Never stringifies the arbitrary data-class object.
     */
    private fun mapRejectionCode(reason: InstalledProvidersRejectionReason): String = when (reason) {
        is InstalledProvidersRejectionReason.InvalidId -> "invalid_id"
        is InstalledProvidersRejectionReason.ReservedCollision -> "reserved_collision"
        is InstalledProvidersRejectionReason.AgreementMismatch -> "agreement_mismatch"
        is InstalledProvidersRejectionReason.MissingRevision -> "missing_revision"
        is InstalledProvidersRejectionReason.DuplicateValue -> "duplicate_value"
        is InstalledProvidersRejectionReason.RevisionOverflow -> "revision_overflow"
    }

    internal fun committedSnapshot(): Map<GitHubRepositoryIdentity, StoredProviderRecord> {
        return try {
            when (val result = store.loadIndex()) {
                is PackageOutcome.Success -> result.value.index.providers.toMap()
                is PackageOutcome.Failure -> emptyMap()
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private companion object {
        const val DIAGNOSTIC_TAG = "InstalledPackages"
        const val DIGEST_PREFIX_LENGTH = 12
        const val SHUTDOWN_AWAIT_MILLIS = 10_000L
    }
}

/**
 * Internal service-owned facade exposing installed-package state and mutation methods
 * to host actions (tests, future install/remove commands). Wraps the coordinator without
 * exposing repository internals. No UI surface.
 */
internal class InstalledPackagesFacade(private val coordinator: InstalledPackagesCoordinator) {
    val state: StateFlow<InstalledPackagesState> get() = coordinator.state

    suspend fun reload() = coordinator.reload()

    suspend fun installOrUpdate(
        inputStream: InputStream,
        sourceRecord: PackageSourceRecord,
    ): PackageOutcome<MutationResult> = coordinator.installOrUpdate(inputStream, sourceRecord)

    suspend fun rollback(repositoryId: GitHubRepositoryIdentity): PackageOutcome<MutationResult> =
        coordinator.rollback(repositoryId)

    suspend fun remove(repositoryId: GitHubRepositoryIdentity): PackageOutcome<MutationResult> =
        coordinator.remove(repositoryId)

    suspend fun shutdown() = coordinator.shutdown()

    /**
     * Immutable snapshot of the committed installed index for package-management
     * summaries. Never exposes content paths, source bytes, or store clients.
     */
    fun committedSnapshot(): Map<GitHubRepositoryIdentity, StoredProviderRecord> =
        coordinator.committedSnapshot()
}
