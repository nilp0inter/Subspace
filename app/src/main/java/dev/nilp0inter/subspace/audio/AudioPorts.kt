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

interface AudioRecorder {
    val isActive: Boolean
    suspend fun start(): Boolean
    fun stopIfActiveOrEmpty(): RecordedPcm
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
    val recorder: AudioRecorder,
)

fun resolveAudioRoute(
    scoRoute: ScoRoute,
    scoOutput: PcmOutput,
    scoRecorder: AudioRecorder,
    localOutput: PcmOutput,
    localRecorder: AudioRecorder,
): ResolvedAudioRoute {
    val scoUsable = scoRoute.hasAvailableScoDevice() || scoRoute.isActive()
    return if (scoUsable) {
        ResolvedAudioRoute(sco = scoRoute, output = scoOutput, recorder = scoRecorder)
    } else {
        ResolvedAudioRoute(sco = NoopScoRoute(), output = localOutput, recorder = localRecorder)
    }
}
