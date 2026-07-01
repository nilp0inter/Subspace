package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.ScoState
import kotlinx.coroutines.flow.StateFlow

data class RecordedPcm(
    val samples: ShortArray,
    val sampleRate: Int,
) {
    val isEmpty: Boolean
        get() = samples.isEmpty()
}

enum class AudioRouteEndpoint { Rsm, Car, Local, Unspecified }

interface ScoRoute {
    val endpoint: AudioRouteEndpoint
        get() = AudioRouteEndpoint.Unspecified
    val state: StateFlow<ScoState>
    val coldStart: Boolean
        get() = false
    fun hasAvailableScoDevice(): Boolean
    suspend fun acquire(): Boolean
    fun isActive(): Boolean
    fun release()
}

interface PcmOutput {
    suspend fun playReadyBeep(coldStart: Boolean = false)
    suspend fun playErrorBeep(coldStart: Boolean = false)
    suspend fun play(recording: RecordedPcm)
    suspend fun releaseRoute() {}
}

data class ResolvedAudioRoute(
    val sco: ScoRoute,
    val output: PcmOutput,
    val source: CaptureSource,
    val endpoint: AudioRouteEndpoint = AudioRouteEndpoint.Unspecified,
)

object NoopCaptureSource : CaptureSource {
    override val sourceId: CaptureSourceId = CaptureSourceId.Mic
    override suspend fun open(): OpenedCaptureSource? = null
}

/**
 * Delegation-only [PcmOutput] that owns its route's release semantics.
 *
 * `releaseRoute()` invokes [release] — a lambda captured at route
 * resolution time that performs the correct release for the selected route
 * (warm 30-second retention for SCO, immediate release for telecom, no-op
 * for local fallback). All other [PcmOutput] operations delegate to
 * [delegate]. This completes the `PcmOutput.releaseRoute()` pattern
 * introduced for [TelecomCapturePcmOutput]: controllers call
 * `route.output.releaseRoute()` and never reach for `ScoRoute` directly in
 * the PTT flow.
 */
class ScopedPcmOutput(
    private val delegate: PcmOutput,
    private val release: suspend () -> Unit,
) : PcmOutput {
    override suspend fun playReadyBeep(coldStart: Boolean) = delegate.playReadyBeep(coldStart)
    override suspend fun playErrorBeep(coldStart: Boolean) = delegate.playErrorBeep(coldStart)
    override suspend fun play(recording: RecordedPcm) = delegate.play(recording)
    override suspend fun releaseRoute() = release()
}

fun resolveScoAudioRoute(
    scoRoute: ScoRoute,
    scoOutput: PcmOutput,
    scoSource: CaptureSource,
    endpoint: AudioRouteEndpoint,
): ResolvedAudioRoute {
    require(scoRoute.endpoint == endpoint) { "Route endpoint mismatch: ${scoRoute.endpoint} != $endpoint" }
    return ResolvedAudioRoute(
        sco = scoRoute,
        output = ScopedPcmOutput(scoOutput) { scoRoute.release() },
        source = scoSource,
        endpoint = endpoint,
    )
}

fun resolveLocalAudioRoute(
    localOutput: PcmOutput,
    localSource: CaptureSource,
): ResolvedAudioRoute = ResolvedAudioRoute(
    sco = NoopScoRoute(),
    output = ScopedPcmOutput(localOutput) { },
    source = localSource,
    endpoint = AudioRouteEndpoint.Local,
)
