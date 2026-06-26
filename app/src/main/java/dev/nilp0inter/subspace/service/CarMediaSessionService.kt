package dev.nilp0inter.subspace.service

import android.content.Intent
import android.os.Build
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.browse.MediaBrowser.MediaItem
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.view.KeyEvent
import dev.nilp0inter.subspace.R

class CarMediaSessionService : MediaBrowserService() {
    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        val serviceIntent = Intent(this, PttForegroundService::class.java).apply {
            action = PttForegroundService.ACTION_START_MONITORING
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        session = MediaSession(this, SESSION_TAG).apply {
            setCallback(callback)
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setMetadata(metadata(getString(R.string.car_media_ready_title)))
            setPlaybackState(playbackState(PlaybackState.STATE_PAUSED))
            isActive = true
        }
        sessionToken = session.sessionToken
        CarMediaStateBus.setListener { state -> updateSessionState(state) }
    }

    override fun onDestroy() {
        CarPttCommandBus.release()
        CarMediaStateBus.setListener(null)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        val item = MediaItem(
            MediaDescription.Builder()
                .setMediaId(ROOT_ID)
                .setTitle(getString(R.string.car_media_ready_title))
                .setSubtitle(getString(R.string.car_media_subtitle))
                .build(),
            MediaItem.FLAG_PLAYABLE,
        )
        result.sendResult(mutableListOf(item))
    }

    private fun updateSessionState(state: CarMediaPttState) {
        val playbackState = when (state) {
            CarMediaPttState.NotReady -> PlaybackState.STATE_ERROR
            CarMediaPttState.Ready -> PlaybackState.STATE_PAUSED
            CarMediaPttState.Recording -> PlaybackState.STATE_PLAYING
            CarMediaPttState.Finalizing -> PlaybackState.STATE_BUFFERING
        }
        val title = when (state) {
            CarMediaPttState.NotReady -> R.string.car_media_not_ready_title
            CarMediaPttState.Ready -> R.string.car_media_ready_title
            CarMediaPttState.Recording -> R.string.car_media_recording_title
            CarMediaPttState.Finalizing -> R.string.car_media_finalizing_title
        }
        session.setMetadata(metadata(getString(title)))
        session.setPlaybackState(playbackState(playbackState))
    }

    private fun metadata(title: String): MediaMetadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
        .putString(MediaMetadata.METADATA_KEY_ALBUM, getString(R.string.car_media_subtitle))
        .build()

    private fun playbackState(state: Int): PlaybackState = PlaybackState.Builder()
        .setActions(
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PLAY_PAUSE,
        )
        .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
        .build()

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = CarPttCommandBus.startTelecomCapture()
        override fun onPause() = Unit
        override fun onStop() = Unit
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (event?.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                -> CarPttCommandBus.startTelecomCapture()
            }
            return true
        }
    }

    companion object {
        private const val SESSION_TAG = "SubspaceCarPtt"
        private const val ROOT_ID = "subspace-car-ptt"
    }
}

internal object CarMediaStateBus {
    private var listener: ((CarMediaPttState) -> Unit)? = null
    private var state: CarMediaPttState = CarMediaPttState.NotReady

    fun setListener(listener: ((CarMediaPttState) -> Unit)?) {
        this.listener = listener
        listener?.invoke(state)
    }

    fun update(state: CarMediaPttState) {
        this.state = state
        listener?.invoke(state)
    }
}

internal enum class CarMediaPttState { NotReady, Ready, Recording, Finalizing }
