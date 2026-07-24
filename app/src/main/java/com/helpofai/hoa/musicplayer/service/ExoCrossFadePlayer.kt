package com.helpofai.hoa.musicplayer.service

import android.animation.Animator
import android.content.Context
import android.net.Uri
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.extensions.showToast
import com.helpofai.hoa.musicplayer.helper.MusicPlayerRemote
import com.helpofai.hoa.musicplayer.model.Song
import com.helpofai.hoa.musicplayer.service.AudioFader.Companion.createFadeAnimator
import com.helpofai.hoa.musicplayer.service.playback.Playback
import com.helpofai.hoa.musicplayer.service.playback.Playback.PlaybackCallbacks
import com.helpofai.hoa.musicplayer.util.PreferenceUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * High-Fidelity CrossFade Player using dual HoaExoPlayer instances.
 * This ensures that custom DSP (3D, Bass, etc.) remains active during transitions.
 */
class ExoCrossFadePlayer(context: Context) : AudioManagerPlayback(context), PlaybackCallbacks {

    private var currentPlayerType: CurrentPlayer = CurrentPlayer.PLAYER_ONE
    private var player1 = HoaExoPlayer(context)
    private var player2 = HoaExoPlayer(context)
    private var durationListener = DurationListener()
    private var mIsInitialized = false
    private var nextDataSource: Uri? = null
    private var crossFadeAnimator: Animator? = null
    override var callbacks: PlaybackCallbacks? = null
    private var crossFadeDuration = PreferenceUtil.crossFadeDuration
    var isCrossFading = false

    init {
        player1.callbacks = this
        player2.callbacks = this
    }

    override fun start(): Boolean {
        durationListener.start()
        resumeFade()
        return try {
            getCurrentPlayer().start()
            if (isCrossFading) {
                getNextPlayer().start()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun release() {
        stop()
        cancelFade()
        player1.release()
        player2.release()
        durationListener.stop()
    }

    override fun stop() {
        player1.stop()
        player2.stop()
        mIsInitialized = false
    }

    override fun pause(): Boolean {
        durationListener.stop()
        pauseFade()
        if (player1.isPlaying) player1.pause()
        if (player2.isPlaying) player2.pause()
        return true
    }

    override fun seek(whereto: Int, force: Boolean): Int {
        if (force) {
            endFade()
        }
        getNextPlayer().stop()
        return try {
            getCurrentPlayer().seek(whereto, force)
            whereto
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }

    override fun setVolume(vol: Float): Boolean {
        // If we are crossfading, we don't want to manually set volume as animator is handling it
        if (!isCrossFading) {
            return getCurrentPlayer().setVolume(vol)
        }
        return true
    }

    override val isInitialized: Boolean
        get() = mIsInitialized

    override val isPlaying: Boolean
        get() = mIsInitialized && getCurrentPlayer().isPlaying

    override fun setDataSource(
        song: Song,
        force: Boolean,
        completion: (success: Boolean) -> Unit,
    ) {
        cancelFade()
        isCrossFading = false
        mIsInitialized = false
        getCurrentPlayer().setDataSource(song, force) { success ->
            mIsInitialized = success
            completion(success)
        }
    }

    override fun setNextDataSource(path: Uri?) {
        nextDataSource = path
    }

    override fun setAudioSessionId(sessionId: Int): Boolean {
        player1.setAudioSessionId(sessionId)
        player2.setAudioSessionId(sessionId)
        return true
    }

    override val audioSessionId: Int
        get() = getCurrentPlayer().audioSessionId

    override fun duration(): Int = getCurrentPlayer().duration()

    override fun position(): Int = getCurrentPlayer().position()

    private fun getCurrentPlayer(): Playback = if (currentPlayerType == CurrentPlayer.PLAYER_ONE) player1 else player2

    private fun getNextPlayer(): Playback = if (currentPlayerType == CurrentPlayer.PLAYER_ONE) player2 else player1

    private fun crossFade(fadeInPlayer: Playback, fadeOutPlayer: Playback) {
        isCrossFading = true
        crossFadeAnimator = createFadeAnimator(context, fadeInPlayer, fadeOutPlayer) {
            crossFadeAnimator = null
            isCrossFading = false
            fadeOutPlayer.stop()
        }
        crossFadeAnimator?.start()
    }

    private fun endFade() {
        crossFadeAnimator?.end()
        crossFadeAnimator = null
    }

    private fun cancelFade() {
        crossFadeAnimator?.cancel()
        crossFadeAnimator = null
    }

    private fun pauseFade() {
        crossFadeAnimator?.pause()
    }

    private fun resumeFade() {
        if (crossFadeAnimator?.isPaused == true) {
            crossFadeAnimator?.resume()
        }
    }

    enum class CurrentPlayer {
        PLAYER_ONE,
        PLAYER_TWO
    }

    inner class DurationListener {
        private val scope = CoroutineScope(Job() + Dispatchers.Default)
        private var job: Job? = null

        fun start() {
            job?.cancel()
            job = scope.launch {
                while (isActive) {
                    delay(500)
                    val pos = position()
                    val dur = duration()
                    if (dur > 0 && (dur - pos) <= crossFadeDuration * 1000 && !isCrossFading) {
                        launch(Dispatchers.Main) {
                            prepareAndStartCrossFade()
                        }
                    }
                }
            }
        }

        fun stop() {
            job?.cancel()
        }
    }

    private fun prepareAndStartCrossFade() {
        val nextSong = MusicPlayerRemote.nextSong
        if (nextSong != null && nextSong != Song.emptySong) {
            getNextPlayer().setDataSource(nextSong, true) { success ->
                if (success) {
                    switchPlayer()
                }
            }
        }
    }

    private fun switchPlayer() {
        val fadeInPlayer = getNextPlayer()
        val fadeOutPlayer = getCurrentPlayer()
        
        fadeInPlayer.start()
        crossFade(fadeInPlayer, fadeOutPlayer)
        
        currentPlayerType = if (currentPlayerType == CurrentPlayer.PLAYER_ONE) {
            CurrentPlayer.PLAYER_TWO
        } else {
            CurrentPlayer.PLAYER_ONE
        }
        callbacks?.onTrackEndedWithCrossfade()
    }

    override fun setCrossFadeDuration(duration: Int) {
        crossFadeDuration = duration
    }

    override fun setPlaybackSpeedPitch(speed: Float, pitch: Float) {
        player1.setPlaybackSpeedPitch(speed, pitch)
        player2.setPlaybackSpeedPitch(speed, pitch)
    }

    // PlaybackCallbacks implementation
    override fun onTrackWentToNext() {
        // Handled by our own crossfade logic
    }

    override fun onTrackEnded() {
        if (!isCrossFading) {
            callbacks?.onTrackEnded()
        }
    }

    override fun onTrackEndedWithCrossfade() {
        // Not used here
    }

    override fun onPlayStateChanged() {
        callbacks?.onPlayStateChanged()
    }
}
