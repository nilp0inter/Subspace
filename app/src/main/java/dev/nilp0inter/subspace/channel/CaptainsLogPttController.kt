package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.AudioRecorder
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.model.CaptainsLogChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CaptainsLogPttController(
    private val scope: CoroutineScope,
    private val sco: ScoRoute,
    private val recorder: AudioRecorder,
    private val output: PcmOutput,
    private val captainsLog: CaptainsLogController,
    private val channelProvider: () -> CaptainsLogChannel,
) {
    private var pttDown: Boolean = false
    private var setupJob: Job? = null
    private var maxDurationJob: Job? = null
    private var closeScoJob: Job? = null
    private var retainedAfterMaxDuration: RecordedPcm? = null

    fun onPttPressed() {
        pttDown = true
        if (setupJob?.isActive == true || recorder.isActive) return
        closeScoJob?.cancel()
        retainedAfterMaxDuration = null
        setupJob = scope.launch { startSession() }
    }

    fun onPttReleased() {
        pttDown = false
        scope.launch { finishSessionIfNeeded() }
    }

    fun cancelAndRelease() {
        setupJob?.cancel()
        maxDurationJob?.cancel()
        closeScoJob?.cancel()
        recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
    }

    private suspend fun startSession() {
        runCatching {
            if (!sco.acquire()) return
            if (!pttDown) {
                releaseScoAfterWarmup()
                return
            }
            output.playReadyBeep(sco.coldStart)
            if (!pttDown) {
                releaseScoAfterWarmup()
                return
            }
            if (!recorder.start()) return
            scheduleMaxDurationStop()
        }.onFailure {
            recorder.stopIfActiveOrEmpty()
            releaseScoAfterWarmup()
        }
    }

    private fun scheduleMaxDurationStop() {
        maxDurationJob?.cancel()
        maxDurationJob = scope.launch {
            delay(MAX_DURATION_MS)
            if (pttDown && recorder.isActive) {
                retainedAfterMaxDuration = recorder.stopIfActiveOrEmpty()
            }
        }
    }

    private suspend fun finishSessionIfNeeded() {
        maxDurationJob?.cancel()
        maxDurationJob = null
        val retained = retainedAfterMaxDuration
        retainedAfterMaxDuration = null
        val recording = retained ?: recorder.stopIfActiveOrEmpty()
        releaseScoAfterWarmup()
        if (!recording.isEmpty) {
            captainsLog.handleCapture(channelProvider(), recording.samples, recording.sampleRate)
        }
    }

    private fun releaseScoAfterWarmup() {
        closeScoJob = scope.launch {
            delay(SCO_WARMUP_MS)
            sco.release()
        }
    }

    companion object {
        private const val MAX_DURATION_MS = 60_000L
        private const val SCO_WARMUP_MS = 30_000L
    }
}
