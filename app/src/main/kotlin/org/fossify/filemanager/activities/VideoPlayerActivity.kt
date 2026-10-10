package org.fossify.filemanager.activities

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ProgressBar
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.GestureDetectorCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.fossify.commons.extensions.toast
import org.fossify.filemanager.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class VideoPlayerActivity : SimpleActivity() {
    companion object {
        private const val PLAYER_SETTINGS = "video_player_settings"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_RESIZE_MODE = "resize_mode"
        private const val KEY_ORIENTATION = "orientation"
        private const val MENU_PLAYBACK_SPEED = 1
        private const val MENU_RESIZE_MODE = 2
        private const val MENU_ORIENTATION = 3
    }

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
    private val systemBarsHandler = Handler(Looper.getMainLooper())
    private val hideSystemBarsRunnable = Runnable { hideSystemBars() }
    private val playerSettings by lazy { getSharedPreferences(PLAYER_SETTINGS, MODE_PRIVATE) }
    private val playbackSpeeds = floatArrayOf(
        0.25f, 0.5f, 0.75f, 1.0f, 1.25f,
        1.5f, 1.75f, 2.0f, 2.5f, 3.0f
    )
    private var playbackSpeed = 1.0f
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var orientationMode = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private var gestureTrackingEnabled = false
    private var gestureMode = GestureMode.NONE
    private var initialPosition = 0L
    private var initialVolume = 0
    private var minVolume = 0
    private var maxVolume = 0
    private var initialBrightness = 0.5f
    private var seekStartX = 0f
    private var seekStartPosition = 0L
    private var isSeeking = false
    private var pendingSeekPosition: Long? = null

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean {
            initialPosition = player?.currentPosition ?: 0L
            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            initialBrightness = getCurrentBrightness()
            gestureMode = GestureMode.NONE
            isSeeking = false
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
            firstEvent: MotionEvent?,
            currentEvent: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val startEvent = firstEvent ?: return true
            val totalX = currentEvent.x - startEvent.x
            val totalY = currentEvent.y - startEvent.y
            if (gestureMode == GestureMode.NONE) {
                val touchSlop = ViewConfiguration.get(this@VideoPlayerActivity).scaledTouchSlop
                if (maxOf(abs(totalX), abs(totalY)) < touchSlop) {
                    return true
                }

                gestureMode = if (abs(totalX) > abs(totalY)) {
                    seekStartX = startEvent.x
                    seekStartPosition = initialPosition
                    isSeeking = true
                    GestureMode.SEEK
                } else if (startEvent.x >= getPlayerWidth() / 2f) {
                    GestureMode.VOLUME
                } else {
                    GestureMode.BRIGHTNESS
                }
            }

            when (gestureMode) {
                GestureMode.VOLUME -> updateVolume(totalY)
                GestureMode.BRIGHTNESS -> updateBrightness(totalY)
                GestureMode.SEEK -> updateSeekPreview(currentEvent.x)
                GestureMode.NONE -> Unit
            }
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPlayerSettings()
        applyOrientation()
        setContentView(R.layout.activity_video_player)
        hideSystemBars()

        playerView = findViewById(R.id.video_player_view)
        playerView?.setResizeMode(resizeMode)
        playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.layoutDirection =
            View.LAYOUT_DIRECTION_RTL
        findViewById<TextView>(R.id.video_settings_button).setOnClickListener { button ->
            showSettingsMenu(button)
        }
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
        exoPlayer.setPlaybackSpeed(playbackSpeed)

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                toast(R.string.unknown_error_occurred)
            }
        })

        exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
        exoPlayer.prepare()
        exoPlayer.play()
    }

    private fun loadPlayerSettings() {
        playbackSpeed = playerSettings.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
            .takeIf { speed -> playbackSpeeds.any { allowedSpeed -> allowedSpeed == speed } } ?: 1.0f
        resizeMode = playerSettings.getInt(
            KEY_RESIZE_MODE,
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        ).takeIf {
            it == AspectRatioFrameLayout.RESIZE_MODE_FIT
                || it == AspectRatioFrameLayout.RESIZE_MODE_FILL
                || it == AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } ?: AspectRatioFrameLayout.RESIZE_MODE_FIT
        orientationMode = playerSettings.getInt(
            KEY_ORIENTATION,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ).takeIf {
            it == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                || it == ActivityInfo.SCREEN_ORIENTATION_SENSOR
                || it == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                || it == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun applyOrientation() {
        requestedOrientation = orientationMode
    }

    private fun showSettingsMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(
                Menu.NONE,
                MENU_PLAYBACK_SPEED,
                Menu.NONE,
                "سرعة التشغيل (${formatSpeed(playbackSpeed)})"
            )
            menu.add(Menu.NONE, MENU_RESIZE_MODE, Menu.NONE, "نمط العرض (${getResizeModeLabel()})")
            menu.add(Menu.NONE, MENU_ORIENTATION, Menu.NONE, "قفل الاتجاه (${getOrientationLabel()})")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_PLAYBACK_SPEED -> showPlaybackSpeedMenu(anchor)
                    MENU_RESIZE_MODE -> showResizeModeMenu(anchor)
                    MENU_ORIENTATION -> showOrientationMenu(anchor)
                }
                true
            }
            show()
        }
    }

    private fun showPlaybackSpeedMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.setGroupCheckable(Menu.NONE, true, true)
            playbackSpeeds.forEachIndexed { index, speed ->
                menu.add(Menu.NONE, index, Menu.NONE, formatSpeed(speed)).apply {
                    isCheckable = true
                    isChecked = speed == playbackSpeed
                }
            }
            setOnMenuItemClickListener { item ->
                setPlaybackSpeed(playbackSpeeds[item.itemId])
                true
            }
            show()
        }
    }

    private fun showResizeModeMenu(anchor: View) {
        val labels = arrayOf("Fit", "Fill", "Zoom")
        val modes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        PopupMenu(this, anchor).apply {
            menu.setGroupCheckable(Menu.NONE, true, true)
            labels.forEachIndexed { index, label ->
                menu.add(Menu.NONE, index, Menu.NONE, label).apply {
                    isCheckable = true
                    isChecked = modes[index] == resizeMode
                }
            }
            setOnMenuItemClickListener { item ->
                setResizeMode(modes[item.itemId])
                true
            }
            show()
        }
    }

    private fun showOrientationMenu(anchor: View) {
        val labels = arrayOf("تلقائي", "أفقي", "رأسي")
        val modes = intArrayOf(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        )
        PopupMenu(this, anchor).apply {
            menu.setGroupCheckable(Menu.NONE, true, true)
            labels.forEachIndexed { index, label ->
                menu.add(Menu.NONE, index, Menu.NONE, label).apply {
                    isCheckable = true
                    isChecked = modes[index] == orientationMode
                }
            }
            setOnMenuItemClickListener { item ->
                setOrientationMode(modes[item.itemId])
                true
            }
            show()
        }
    }

    private fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        player?.setPlaybackSpeed(speed)
        playerSettings.edit().putFloat(KEY_PLAYBACK_SPEED, speed).apply()
    }

    private fun setResizeMode(mode: Int) {
        resizeMode = mode
        playerView?.setResizeMode(mode)
        playerSettings.edit().putInt(KEY_RESIZE_MODE, mode).apply()
    }

    private fun setOrientationMode(mode: Int) {
        orientationMode = mode
        playerSettings.edit().putInt(KEY_ORIENTATION, mode).apply()
        applyOrientation()
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed % 1f == 0f) "${speed.toInt()}.0x" else "${speed}x"
    }

    private fun getResizeModeLabel(): String {
        return when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            else -> "Fit"
        }
    }

    private fun getOrientationLabel(): String {
        return when (orientationMode) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "أفقي"
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "رأسي"
            else -> "تلقائي"
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            showSystemBarsTemporarily()
        }

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
                        finishGesture()
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
        systemBarsHandler.removeCallbacks(hideSystemBarsRunnable)
        showSystemBars()
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        feedbackHandler.removeCallbacksAndMessages(null)
        systemBarsHandler.removeCallbacks(hideSystemBarsRunnable)
        showSystemBars()
        releasePlayer()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            window.setDecorFitsSystemWindows(true)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 0
        }
    }

    private fun showSystemBarsTemporarily() {
        systemBarsHandler.removeCallbacks(hideSystemBarsRunnable)
        showSystemBars()
        systemBarsHandler.postDelayed(hideSystemBarsRunnable, 3000L)
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

    private fun updateSeekPreview(currentX: Float) {
        val currentPlayer = player ?: return
        val duration = currentPlayer.duration
        if (duration == C.TIME_UNSET || duration <= 0L) {
            return
        }

        val totalDistance = currentX - seekStartX
        val fraction = totalDistance / getPlayerWidth().toFloat()
        val maxSeekRange = if (duration < 30 * 60 * 1000L) {
            duration
        } else {
            300_000L
        }
        val delta = (-fraction * maxSeekRange).toLong()
        val newPosition = (seekStartPosition + delta).coerceIn(0L, duration)
        pendingSeekPosition = newPosition
        val percentage = (newPosition * 100 / duration).toInt().coerceIn(0, 100)
        showGestureFeedback(
            "${formatSeekTime(newPosition)} (${formatSeekDifference(newPosition - seekStartPosition)})",
            percentage
        )
    }

    private fun finishGesture() {
        if (isSeeking) {
            pendingSeekPosition?.let { player?.seekTo(it) }
            isSeeking = false
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

    private fun formatSeekTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes % 60L, seconds)
    }

    private fun formatSeekDifference(milliseconds: Long): String {
        val sign = if (milliseconds >= 0L) "+" else "-"
        return "$sign${formatSeekTime(abs(milliseconds))}"
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
