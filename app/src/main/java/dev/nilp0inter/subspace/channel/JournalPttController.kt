package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.AudioRecorder
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.model.JournalChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class JournalPttController(
    private val scope: CoroutineScope,
    private val sco: ScoRoute,
    private val recorder: AudioRecorder,
    private val output: PcmOutput,
    private val journal: JournalController,
    private val channelProvider: () -> JournalChannel,
) {
    private var pttDown: Boolean = false
    private var setupJob: Job? = null
    private var maxDurationJob: Job? = null
    private var retainedAfterMaxDuration: RecordedPcm? = null

    fun onPttPressed() {
        pttDown = true
        if (setupJob?.isActive == true || recorder.isActive) return
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
        recorder.stopIfActiveOrEmpty()
        retainedAfterMaxDuration = null
        sco.release()
    }

    private suspend fun startSession() {
        runCatching {
            if (!sco.acquire()) return
            if (!pttDown) {
                sco.release()
                return
            }
            output.playReadyBeep(sco.coldStart)
            if (!pttDown) {
                sco.release()
                return
            }
            if (!recorder.start()) return
            scheduleMaxDurationStop()
        }.onFailure {
            recorder.stopIfActiveOrEmpty()
            sco.release()
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
        sco.release()
        if (!recording.isEmpty) {
            journal.handleCapture(channelProvider(), recording.samples, recording.sampleRate)
        }
    }

    companion object {
        private const val MAX_DURATION_MS = 60_000L
    }
}
