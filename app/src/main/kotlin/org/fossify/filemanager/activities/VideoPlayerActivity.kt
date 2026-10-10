package org.fossify.filemanager.activities

import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.GestureDetectorCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.fossify.commons.extensions.toast
import org.fossify.filemanager.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class VideoPlayerActivity : SimpleActivity() {
    private enum class GestureMode {
        NONE,
        VOLUME,
        BRIGHTNESS,
        SEEK
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var audioManager: AudioManager
    private lateinit var gestureFeedback: View
    private lateinit var gestureFeedbackText: TextView
    private lateinit var gestureFeedbackProgress: ProgressBar
    private val feedbackHandler = Handler(Looper.getMainLooper())

    private var gestureTrackingEnabled = false
    private var gestureMode = GestureMode.NONE
    private var initialPosition = 0L
    private var initialVolume = 0
    private var minVolume = 0
    private var maxVolume = 0
    private var initialBrightness = 0.5f
    private var pendingSeekPosition: Long? = null

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean {
            initialPosition = player?.currentPosition ?: 0L
            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            initialBrightness = getCurrentBrightness()
            gestureMode = GestureMode.NONE
            pendingSeekPosition = null
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                } else {
                    it.play()
                }
            }
            return true
        }

        override fun onScroll(
            firstEvent: MotionEvent,
            currentEvent: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val totalX = currentEvent.x - firstEvent.x
            val totalY = currentEvent.y - firstEvent.y
            if (gestureMode == GestureMode.NONE) {
                val touchSlop = ViewConfiguration.get(this@VideoPlayerActivity).scaledTouchSlop
                if (maxOf(abs(totalX), abs(totalY)) < touchSlop) {
                    return true
                }

                gestureMode = if (abs(totalX) > abs(totalY)) {
                    GestureMode.SEEK
                } else if (firstEvent.x >= getPlayerWidth() / 2f) {
                    GestureMode.VOLUME
                } else {
                    GestureMode.BRIGHTNESS
                }
            }

            when (gestureMode) {
                GestureMode.VOLUME -> updateVolume(totalY)
                GestureMode.BRIGHTNESS -> updateBrightness(totalY)
                GestureMode.SEEK -> updateSeekPreview(totalX)
                GestureMode.NONE -> Unit
            }
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        playerView = findViewById(R.id.video_player_view)
        gestureFeedback = findViewById(R.id.gesture_feedback)
        gestureFeedbackText = findViewById(R.id.gesture_feedback_text)
        gestureFeedbackProgress = findViewById(R.id.gesture_feedback_progress)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        minVolume = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        gestureDetector = GestureDetectorCompat(this, gestureListener)

        val videoUri = intent.data
        if (videoUri == null) {
            toast(R.string.unknown_error_occurred)
            finish()
            return
        }

        initializePlayer(videoUri)
    }

    private fun initializePlayer(videoUri: Uri) {
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        playerView?.player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                toast(R.string.unknown_error_occurred)
            }
        })

        exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
        exoPlayer.prepare()
        exoPlayer.play()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (player != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureTrackingEnabled = !isPlayerControlsTouch(event)
                    if (gestureTrackingEnabled) {
                        gestureDetector.onTouchEvent(event)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gestureTrackingEnabled) {
                        gestureDetector.onTouchEvent(event)
                        finishGesture(event.actionMasked == MotionEvent.ACTION_UP)
                    }
                    gestureTrackingEnabled = false
                }

                else -> if (gestureTrackingEnabled) {
                    gestureDetector.onTouchEvent(event)
                }
            }
        }

        return super.dispatchTouchEvent(event)
    }

    override fun onPause() {
        player?.pause()
        super.onPause()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        feedbackHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    private fun updateVolume(totalY: Float) {
        val volumeRange = maxVolume - minVolume
        val volumeChange = (-totalY / getPlayerHeight() * volumeRange).roundToInt()
        val volume = (initialVolume + volumeChange).coerceIn(minVolume, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)

        val percentage = if (volumeRange == 0) 0 else ((volume - minVolume) * 100 / volumeRange)
        showGestureFeedback("الصوت $percentage%", percentage)
    }

    private fun updateBrightness(totalY: Float) {
        val brightnessChange = -totalY / getPlayerHeight()
        val brightness = (initialBrightness + brightnessChange).coerceIn(0f, 1f)
        val attributes = window.attributes
        attributes.screenBrightness = brightness
        window.attributes = attributes

        val percentage = (brightness * 100).roundToInt().coerceIn(0, 100)
        showGestureFeedback("السطوع $percentage%", percentage)
    }

    private fun updateSeekPreview(totalX: Float) {
        val currentPlayer = player ?: return
        val duration = currentPlayer.duration
        if (duration == C.TIME_UNSET || duration <= 0L) {
            return
        }

        val positionChange = (totalX / getPlayerWidth() * duration).toLong()
        val newPosition = (initialPosition + positionChange).coerceIn(0L, duration)
        pendingSeekPosition = newPosition
        val percentage = (newPosition * 100 / duration).toInt().coerceIn(0, 100)
        showGestureFeedback(
            "${formatTime(newPosition)} / ${formatTime(duration)}",
            percentage
        )
    }

    private fun finishGesture(commitSeek: Boolean) {
        if (gestureMode == GestureMode.SEEK && commitSeek) {
            pendingSeekPosition?.let { player?.seekTo(it) }
        }

        if (gestureMode != GestureMode.NONE) {
            scheduleFeedbackHide()
        }
        gestureMode = GestureMode.NONE
        pendingSeekPosition = null
    }

    private fun showGestureFeedback(text: String, progress: Int) {
        feedbackHandler.removeCallbacksAndMessages(null)
        gestureFeedbackText.text = text
        gestureFeedbackProgress.progress = progress.coerceIn(0, 100)
        gestureFeedbackProgress.visibility = View.VISIBLE
        gestureFeedback.alpha = 1f
        gestureFeedback.visibility = View.VISIBLE
    }

    private fun scheduleFeedbackHide() {
        feedbackHandler.removeCallbacksAndMessages(null)
        feedbackHandler.postDelayed({
            gestureFeedback.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    gestureFeedback.visibility = View.GONE
                }
        }, 1000L)
    }

    private fun getCurrentBrightness(): Float {
        val windowBrightness = window.attributes.screenBrightness
        if (windowBrightness >= 0f) {
            return windowBrightness.coerceIn(0f, 1f)
        }

        val systemBrightness = try {
            Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
        } catch (_: Settings.SettingNotFoundException) {
            128
        }
        return (systemBrightness / 255f).coerceIn(0f, 1f)
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun getPlayerWidth() = playerView?.width?.coerceAtLeast(1) ?: 1

    private fun getPlayerHeight() = playerView?.height?.coerceAtLeast(1) ?: 1

    @OptIn(UnstableApi::class)
    private fun isPlayerControlsTouch(event: MotionEvent): Boolean {
        val view = playerView ?: return false
        if (!view.isControllerFullyVisible) {
            return false
        }

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val localY = event.rawY - location[1]
        return localY >= view.height * 0.72f
    }

    private fun releasePlayer() {
        playerView?.player = null
        player?.release()
        player = null
    }
}
