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

interface ScoRoute {
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
)

object NoopCaptureSource : CaptureSource {
    override val sourceId: CaptureSourceId = CaptureSourceId.Mic
    override suspend fun open(): OpenedCaptureSource? = null
}

fun resolveAudioRoute(
    scoRoute: ScoRoute,
    scoOutput: PcmOutput,
    scoSource: CaptureSource,
    localOutput: PcmOutput,
    localSource: CaptureSource,
): ResolvedAudioRoute {
    val scoUsable = scoRoute.hasAvailableScoDevice() || scoRoute.isActive()
    return if (scoUsable) {
        ResolvedAudioRoute(sco = scoRoute, output = scoOutput, source = scoSource)
    } else {
        ResolvedAudioRoute(sco = NoopScoRoute(), output = localOutput, source = localSource)
    }
}
