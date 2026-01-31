package com.helpofai.hoa.musicplayer.service

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.service.playback.BassBoostProcessor
import com.helpofai.hoa.musicplayer.service.playback.ReverbProcessor
import com.helpofai.hoa.musicplayer.service.playback.StereoProcessor
import com.helpofai.hoa.musicplayer.util.PreferenceUtil
import com.helpofai.hoa.musicplayer.extensions.showToast
import com.helpofai.hoa.musicplayer.extensions.uri
import com.helpofai.hoa.musicplayer.model.Song
import com.helpofai.hoa.musicplayer.service.playback.Playback.PlaybackCallbacks
import com.helpofai.hoa.musicplayer.util.PreferenceUtil.playbackPitch
import com.helpofai.hoa.musicplayer.util.PreferenceUtil.playbackSpeed
import com.helpofai.hoa.musicplayer.util.logE

@OptIn(UnstableApi::class)
class RetroExoPlayer(context: Context) : AudioManagerPlayback(context), Player.Listener {
    private val stereoProcessor = StereoProcessor()
    private val reverbProcessor = ReverbProcessor()
    private val bassBoostProcessor = BassBoostProcessor()

    private var player: ExoPlayer = createPlayer()
    override var callbacks: PlaybackCallbacks? = null

    override var isInitialized = false
        private set

    init {
        stereoProcessor.balance = PreferenceUtil.balance
        stereoProcessor.stereoWidth = PreferenceUtil.stereoWidth
        stereoProcessor.clarity = PreferenceUtil.clarity
        reverbProcessor.amount = PreferenceUtil.reverbAmount
        bassBoostProcessor.strength = PreferenceUtil.bassStrength
        player.setWakeMode(C.WAKE_MODE_LOCAL)
    }

    private fun createPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context, createRenderersFactory())
            .build()
    }

    private fun createRenderersFactory(): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                val useFloat = enableFloatOutput || PreferenceUtil.isHighResAudio
                return DefaultAudioSink.Builder()
                    .setAudioProcessors(arrayOf(stereoProcessor, reverbProcessor, bassBoostProcessor))
                    .setEnableFloatOutput(useFloat)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
    }

    /**
     * @param song The song object you want to play
     * @return True if the `player` has been prepared and is ready to play, false otherwise
     */
    override fun setDataSource(
        song: Song,
        force: Boolean,
        completion: (success: Boolean) -> Unit,
    ) {
        isInitialized = false
        val mediaItem = MediaItem.fromUri(song.uri)
        try {
            Handler(Looper.getMainLooper()).post {
                player.setMediaItem(mediaItem)
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false
                )
                player.playbackParameters = PlaybackParameters(playbackSpeed, playbackPitch)

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            player.removeListener(this)
                            isInitialized = true
                            completion(true)
                        }
                    }
                })
                player.addListener(this)
                player.prepare()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            completion(false)
        }
    }

    /**
     * Set the MediaPlayer to start when this MediaPlayer finishes playback.
     *
     * @param path The path of the file, or the http/rtsp URL of the stream you want to play
     */
    override fun setNextDataSource(path: Uri?) {}

    /**
     * Starts or resumes playback.
     */
    override fun start(): Boolean {
        super.start()
        return try {
            player.play()
            true
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Resets the MediaPlayer to its uninitialized state.
     */
    override fun stop() {
        super.stop()
        player.stop()
        isInitialized = false
    }

    /**
     * Releases resources associated with this MediaPlayer object.
     */
    override fun release() {
        stop()
        player.release()
    }

    /**
     * Pauses playback. Call start() to resume.
     */
    override fun pause(): Boolean {
        super.pause()
        return try {
            player.pause()
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    /**
     * Checks whether the MultiPlayer is playing.
     */
    override val isPlaying: Boolean
        get() = isInitialized && (player.isPlaying || player.playbackState == Player.STATE_ENDED)

    /**
     * Gets the duration of the file.
     *
     * @return The duration in milliseconds
     */
    override fun duration(): Int {
        return if (!this.isInitialized) {
            -1
        } else try {
            player.duration.toInt()
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Gets the current playback position.
     *
     * @return The current position in milliseconds
     */
    override fun position(): Int {
        return if (!this.isInitialized) {
            -1
        } else try {
            player.currentPosition.toInt()
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Gets the current playback position.
     *
     * @param whereto The offset in milliseconds from the start to seek to
     * @return The offset in milliseconds from the start to seek to
     */
    override fun seek(whereto: Int, force: Boolean): Int {
        return try {
            player.seekTo(whereto.toLong())
            whereto
        } catch (e: Exception) {
            -1
        }
    }

    override fun setVolume(vol: Float): Boolean {
        return try {
            player.volume = vol
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sets the audio session ID.
     *
     * @param sessionId The audio session ID
     */
    @OptIn(UnstableApi::class)
    override fun setAudioSessionId(sessionId: Int): Boolean {
        return try {
            player.audioSessionId = sessionId
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the audio session ID.
     *
     * @return The current audio session ID.
     */
    override val audioSessionId: Int
        @OptIn(UnstableApi::class)
        get() = player.audioSessionId

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_ENDED) {
            callbacks?.onTrackEnded()
        } else {
            callbacks?.onPlayStateChanged()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        logE(error)
        isInitialized = false
        player.release()
        player = createPlayer()
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        context.showToast(R.string.unplayable_file)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            callbacks?.onTrackWentToNext()
            return
        }
    }

    override fun setCrossFadeDuration(duration: Int) {}

    override fun setPlaybackSpeedPitch(speed: Float, pitch: Float) {
        player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    fun setBalance(balance: Float) {
        stereoProcessor.balance = balance
    }

    fun setStereoWidth(width: Float) {
        stereoProcessor.stereoWidth = width
    }

    fun setClarity(clarity: Float) {
        stereoProcessor.clarity = clarity
    }

    fun setBassStrength(strength: Float) {
        bassBoostProcessor.strength = strength
    }

    fun setReverbAmount(amount: Float) {
        reverbProcessor.amount = amount
    }

    companion object {
        val TAG: String = RetroExoPlayer::class.java.simpleName
    }
}