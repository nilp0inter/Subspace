package dev.nilp0inter.subspace.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.delay

interface ResponsePlayer {
    suspend fun play(recording: RecordedPcm)
}

class MediaResponsePlayer(
    private val audioManager: AudioManager,
    private val output: PcmOutput,
) : ResponsePlayer {
    private var focusRequest: AudioFocusRequest? = null

    override suspend fun play(recording: RecordedPcm) {
        if (recording.isEmpty) return
        waitForMediaRoute()
        if (!requestFocus()) return
        try {
            output.play(recording)
        } finally {
            abandonFocus()
        }
    }

    private suspend fun waitForMediaRoute() {
        repeat(60) {
            val communicationDevice = audioManager.communicationDevice
            if (audioManager.mode == AudioManager.MODE_NORMAL && communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return
            }
            delay(50)
        }
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
