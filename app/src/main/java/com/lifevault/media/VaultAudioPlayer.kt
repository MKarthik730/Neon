package com.lifevault.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.lifevault.vault.VaultSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val itemId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

/**
 * One ExoPlayer for the session, fed by [EncryptedBlobDataSource]. Released (and playback cancelled)
 * when the vault locks.
 */
@OptIn(UnstableApi::class)
class VaultAudioPlayer(private val context: Context, private val session: VaultSession) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val sourceFactory by lazy {
        ProgressiveMediaSource.Factory(
            EncryptedBlobDataSource.Factory(session.blobs),
            DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
        )
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            publish(p)
            if (p.isPlaying) main.postDelayed(this, 250)
        }
    }

    init {
        session.onClose { main.post { release() } }
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { p ->
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish(p)
                if (isPlaying) main.post(ticker)
            }
            override fun onPlaybackStateChanged(playbackState: Int) = publish(p)
            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(isPlaying = false, error = "Cannot play this recording (${error.errorCodeName}).")
            }
        })
        player = p
    }

    /** Must be called on the main thread. */
    fun play(itemId: String) {
        if (session.isClosed) return
        val p = ensurePlayer()
        if (_state.value.itemId != itemId) {
            p.setMediaSource(sourceFactory.createMediaSource(MediaItem.fromUri(EncryptedBlobDataSource.uriFor(itemId))))
            p.prepare()
            _state.value = PlaybackState(itemId = itemId)
        } else if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
        }
        p.play()
    }

    fun pause() { player?.pause() }

    fun seekTo(ms: Long) {
        player?.let { it.seekTo(ms); publish(it) }
    }

    fun stop() {
        player?.stop()
        _state.value = PlaybackState()
    }

    fun release() {
        main.removeCallbacks(ticker)
        player?.release()
        player = null
        _state.value = PlaybackState()
    }

    private fun publish(p: ExoPlayer) {
        _state.value = _state.value.copy(
            isPlaying = p.isPlaying,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it > 0 } ?: _state.value.durationMs,
        )
    }
}
