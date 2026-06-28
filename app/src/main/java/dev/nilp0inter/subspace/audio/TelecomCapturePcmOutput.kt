package dev.nilp0inter.subspace.audio

class TelecomCapturePcmOutput(
    private val captureOutput: PcmOutput,
    private val mediaResponsePlayer: MediaResponsePlayer,
    private val releaseCaptureRoute: suspend () -> Unit,
    private val awaitTelecomDisconnected: suspend () -> Unit,
) : PcmOutput {
    override suspend fun playReadyBeep(coldStart: Boolean) {
        captureOutput.playReadyBeep(coldStart)
    }

    override suspend fun playErrorBeep(coldStart: Boolean) {
        captureOutput.playErrorBeep(coldStart)
    }

    override suspend fun play(recording: RecordedPcm) {
        releaseCaptureRoute()
        awaitTelecomDisconnected()
        mediaResponsePlayer.play(recording)
    }

    override suspend fun releaseRoute() {
        releaseCaptureRoute()
        awaitTelecomDisconnected()
    }
}
