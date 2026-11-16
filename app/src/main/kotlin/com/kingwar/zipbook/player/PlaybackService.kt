package com.kingwar.zipbook.player

import android.animation.ValueAnimator
import android.content.Intent
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.kingwar.zipbook.R

/**
 * Foreground media session so a book keeps playing when the screen is off or the app is
 * backgrounded — the normal way an audiobook is consumed. Without a session service,
 * Android stops playback shortly after the activity leaves the foreground.
 *
 * Audio focus is handled manually (not by ExoPlayer) so that transient focus loss —
 * notifications, the assistant chime, etc. — fades the volume down ("ducks") instead of
 * pausing playback outright. ExoPlayer's own built-in focus handling does NOT duck for
 * speech content (Android intentionally skips auto-ducking for speech, since a ducked
 * voice is hard to follow), so for an audiobook player it would otherwise pause on every
 * notification. We take over the AudioManager focus request ourselves and only pause for
 * a real exclusive loss (phone calls, another player taking full focus) while fading the
 * volume for the rest.
 *
 * Also owns the sleep timer: a custom command button shown next to play/pause in the
 * system notification and on the lock screen, since there is no "stop in N minutes"
 * control in the stock media3 notification otherwise.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sleepTimerIndex = -1 // -1 = off, else index into SLEEP_TIMER_MINUTES
    private val sleepTimerRunnable = Runnable {
        mediaSession?.player?.pause()
        sleepTimerIndex = -1
        updateSleepTimerButton()
    }

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var fadeAnimator: ValueAnimator? = null

    /** True once we've paused playback ourselves due to an exclusive focus loss, so we
     * know to resume (rather than leaving the user paused) when focus returns. */
    private var pausedByFocusLoss = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val player = mediaSession?.player ?: return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    player.play()
                }
                fadeVolumeTo(1.0f)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // A notification sound, the assistant's TTS, etc. — fade the book down
                // instead of stopping it so the user keeps their place in the audio.
                fadeVolumeTo(DUCK_VOLUME)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Something needs exclusive but temporary focus (a phone call). Pause
                // and remember to resume automatically once we regain focus.
                if (player.isPlaying) {
                    pausedByFocusLoss = true
                    player.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss — another app is taking over playback. Stop and let
                // the user explicitly resume; do not auto-resume on a later GAIN.
                pausedByFocusLoss = false
                player.pause()
                abandonAudioFocus()
            }
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SLEEP_TIMER_COMMAND)
                .build()
            return MediaSession.ConnectionResult.accept(available, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == SLEEP_TIMER_ACTION) {
                cycleSleepTimer()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                // We manage focus ourselves (see focusChangeListener) so transient loss
                // ducks the volume instead of ExoPlayer pausing for speech content.
                /* handleAudioFocus = */ false,
            )
            // Pause instead of blasting audio out of the speaker when headphones unplug.
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) requestAudioFocus() else abandonAudioFocus()
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .setCustomLayout(listOf(sleepTimerButton(active = false)))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return
        val attributes = PlatformAudioAttributes.Builder()
            .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
            .setContentType(PlatformAudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
            // We handle ducking ourselves on AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK rather
            // than letting the system fade us — setWillPauseWhenDucked stays false so
            // we keep getting the callback instead of the system silently ducking us.
            .build()
        val result = audioManager.requestAudioFocus(request)
        audioFocusRequest = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) request else null
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        pausedByFocusLoss = false
    }

    /** Smoothly ramps the player volume to [target] over [FADE_DURATION_MS] instead of
     * snapping it, so ducking reads as a fade rather than a glitch. */
    private fun fadeVolumeTo(target: Float) {
        val player = mediaSession?.player ?: return
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(player.volume, target).apply {
            duration = FADE_DURATION_MS
            addUpdateListener { anim -> player.volume = anim.animatedValue as Float }
            start()
        }
    }

    /** Off -> 15 -> 30 -> 45 -> 60 -> off. Tapping again at 60 cancels the timer. */
    private fun cycleSleepTimer() {
        mainHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerIndex = if (sleepTimerIndex + 1 >= SLEEP_TIMER_MINUTES.size) -1 else sleepTimerIndex + 1
        if (sleepTimerIndex >= 0) {
            mainHandler.postDelayed(sleepTimerRunnable, SLEEP_TIMER_MINUTES[sleepTimerIndex] * 60_000L)
        }
        updateSleepTimerButton()
    }

    private fun updateSleepTimerButton() {
        mediaSession?.setCustomLayout(listOf(sleepTimerButton(active = sleepTimerIndex >= 0)))
    }

    private fun sleepTimerButton(active: Boolean): CommandButton {
        val label = if (active) {
            "Сон: ${SLEEP_TIMER_MINUTES[sleepTimerIndex]} мин"
        } else {
            "Таймер сна"
        }
        return CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setSessionCommand(SLEEP_TIMER_COMMAND)
            .setDisplayName(label)
            .setCustomIconResId(if (active) R.drawable.ic_sleep_timer_active else R.drawable.ic_sleep_timer)
            .setEnabled(true)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused should not leave a dead notification.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(sleepTimerRunnable)
        fadeAnimator?.cancel()
        abandonAudioFocus()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        const val SLEEP_TIMER_ACTION = "com.kingwar.zipbook.TOGGLE_SLEEP_TIMER"
        val SLEEP_TIMER_COMMAND = SessionCommand(SLEEP_TIMER_ACTION, Bundle.EMPTY)
        val SLEEP_TIMER_MINUTES = listOf(15, 30, 45, 60)

        /** Volume the book ducks to while something else has transient focus. */
        const val DUCK_VOLUME = 0.2f
        const val FADE_DURATION_MS = 300L
    }
}
