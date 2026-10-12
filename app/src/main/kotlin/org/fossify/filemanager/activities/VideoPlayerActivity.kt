package org.fossify.filemanager.activities

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.res.Configuration
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
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.TrackSelectionParameters
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File

class VideoPlayerActivity : SimpleActivity() {
    companion object {
        private const val PLAYER_SETTINGS = "video_player_settings"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_RESIZE_MODE = "resize_mode"
        private const val KEY_ORIENTATION = "orientation"
        private const val MENU_PLAYBACK_SPEED = 1
        private const val MENU_RESIZE_MODE = 2
        private const val MENU_ORIENTATION = 3
        private const val KEY_POSITION = "player_position"
        private const val KEY_PLAY_WHEN_READY = "player_playWhenReady"
    }

    // Subtitle related constants
    private val subtitleExtensions = listOf("srt", "ass", "ssa", "vtt", "sub")
    private val arabicLanguages = listOf("ar", "ara")
    private val englishLanguages = listOf("en", "eng")

    // Helper functions
    private fun getLanguageFromFileName(fileName: String): String? {
        val lower = fileName.lowercase()
        // Patterns: .ar. _ar .arabic. _arabic .en. _en .english. _english
        if (lower.contains(".ar.") || lower.contains("_ar") || lower.contains(".arabic.") || lower.contains("_arabic")) {
            return "ar"
        }
        if (lower.contains(".en.") || lower.contains("_en") || lower.contains(".english.") || lower.contains("_english")) {
            return "en"
        }
        // If no language marker, return null (meaning unknown)
        return null
    }

    private fun getSubtitleConfigsForFileUri(uri: Uri): List<SubtitleConfiguration> {
        if (uri.scheme != "file") return emptyList()
        val path = uri.path ?: return emptyList()
        val file = File(path)
        if (!file.exists()) return emptyList()
        val parent = file.parentFile ?: return emptyList()
        if (!parent.exists() || !parent.isDirectory) return emptyList()
        val baseName = file.name.substringBeforeLast('.')
        val configs = mutableListOf<SubtitleConfiguration>()
        for (ext in subtitleExtensions) {
            // exact match without language
            val exactFile = File(parent, "$baseName.$ext")
            if (exactFile.exists()) {
                val lang = getLanguageFromFileName("$baseName.$ext") ?: "und"
                val mime = when (ext) {
                    "srt" -> "application/x-subrip"
                    "vtt" -> "text/vtt"
                    "ass", "ssa" -> "text/x-ssa"
                    else -> "*/*"
                }
                configs.add(SubtitleConfiguration.Builder(Uri.fromFile(exactFile))
                        .setMimeType(mime)
                        .setLanguage(lang)
                        .build())
            }
            // language patterns: .lang. and _lang.
            for (lang in arabicLanguages) {
                val arFile = File(parent, "$baseName.$lang.$ext")
                if (arFile.exists()) {
                    val mime = when (ext) {
                        "srt" -> "application/x-subrip"
                        "vtt" -> "text/vtt"
                        "ass", "ssa" -> "text/x-ssa"
                        else -> "*/*"
                    }
                    configs.add(SubtitleConfiguration.Builder(Uri.fromFile(arFile))
                            .setMimeType(mime)
                            .setLanguage(lang)
                            .build())
                }
                val arFile2 = File(parent, "$baseName" + "_$lang.$ext")
                if (arFile2.exists()) {
                    val mime = when (ext) {
                        "srt" -> "application/x-subrip"
                        "vtt" -> "text/vtt"
                        "ass", "ssa" -> "text/x-ssa"
                        else -> "*/*"
                    }
                    configs.add(SubtitleConfiguration.Builder(Uri.fromFile(arFile2))
                            .setMimeType(mime)
                            .setLanguage(lang)
                            .build())
                }
            }
            for (lang in englishLanguages) {
                val enFile = File(parent, "$baseName.$lang.$ext")
                if (enFile.exists()) {
                    val mime = when (ext) {
                        "srt" -> "application/x-subrip"
                        "vtt" -> "text/vtt"
                        "ass", "ssa" -> "text/x-ssa"
                        else -> "*/*"
                    }
                    configs.add(SubtitleConfiguration.Builder(Uri.fromFile(enFile))
                            .setMimeType(mime)
                            .setLanguage(lang)
                            .build())
                }
                val enFile2 = File(parent, "$baseName" + "_$lang.$ext")
                if (enFile2.exists()) {
                    val mime = when (ext) {
                        "srt" -> "application/x-subrip"
                        "vtt" -> "text/vtt"
                        "ass", "ssa" -> "text/x-ssa"
                        else -> "*/*"
                    }
                    configs.add(SubtitleConfiguration.Builder(Uri.fromFile(enFile2))
                            .setMimeType(mime)
                            .setLanguage(lang)
                            .build())
                }
            }
        }
        return configs
    }

    private fun getSubtitleConfigsForContentUri(uri: Uri): List<SubtitleConfiguration> {
        if (uri.scheme != "content") return emptyList()
        val contentResolver = contentResolver
        val docFile = DocumentFile.fromSingleUri(this, uri)
        val parentDoc = docFile?.parentFile
        val parentFile: File? = if (parentDoc != null) parentDoc.file else null
        var baseName: String? = null
        if (parentFile != null) {
            // Try to get display name via OpenableColumns.DISPLAY_NAME
            val displayName = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            baseName = displayName?.substringBeforeLast('.')
        } else {
            // Fallback: use DocumentsContract.findDocumentPath
            val path = DocumentsContract.findDocumentPath(contentResolver, uri)
            if (path != null) {
                // path is like "/document/...": we cannot easily get a File, so we give up.
                Log.d("Subtitle", "DocumentsContract path obtained but cannot resolve to File: $path")
                return emptyList()
            } else {
                return emptyList()
            }
        }
        if (baseName == null || parentFile == null || !parentFile.exists() || !parentFile.isDirectory) return emptyList()
        val configs = mutableListOf<SubtitleConfiguration>()
        val files = parentFile.listFiles()
        if (files == null) return emptyList()
        for (file in files) {
            if (!file.isFile) continue
            val name = file.name
            if (!name.startsWith(baseName)) continue
            val extension = file.name.substringAfterLast('.')
            if (!subtitleExtensions.contains(extension)) continue
            // Extract language from the part between baseName and extension
            val middle = name.substring(baseName.length)
            // Remove leading dot or underscore if present
            val langPart = if (middle.startsWith(".") || middle.startsWith("_")) middle.substring(1) else middle
            // Remove extension from langPart
            val langRaw = langPart.substringBeforeLast('.')
            val language = getLanguageFromFileName(langRaw) ?: "und"
            val mime = when (extension) {
                "srt" -> "application/x-subrip"
                "vtt" -> "text/vtt"
                "ass", "ssa" -> "text/x-ssa"
                else -> "*/*"
            }
            configs.add(SubtitleConfiguration.Builder(Uri.fromFile(file))
                    .setMimeType(mime)
                    .setLanguage(language)
                    .build())
        }
        return configs
    }

    private fun sortSubtitleConfigs(configs: List<SubtitleConfiguration>): List<SubtitleConfiguration> {
        return configs.sortedWith(compareBy<SubtitleConfiguration> {
            val lang = it.language ?: ""
            when {
                lang.startsWith("ar") -> 0
                lang.startsWith("en") -> 1
                else -> 2
            }
        })
    }

    private fun showArabicSubtitleToast() {
        Toast.makeText(this, "تم تحميل الترجمة العربية", Toast.LENGTH_SHORT).show()
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

        // Detect subtitles
        val subtitleConfigs = getSubtitleConfigsForFileUri(videoUri) ?: getSubtitleConfigsForContentUri(videoUri) ?: emptyList()
        val sortedConfigs = sortSubtitleConfigs(subtitleConfigs)
        if (sortedConfigs.any { it.language?.startsWith("ar") == true }) {
            showArabicSubtitleToast()
        }

        if (player == null) {
            initializePlayer(videoUri)
        }

        // Apply subtitle configurations if any
        if (sortedConfigs.isNotEmpty()) {
            val builder = MediaItem.Builder().setUri(videoUri)
            builder.setSubtitleConfigurations(sortedConfigs)
            val mediaItem = builder.build()
            player?.setMediaItem(mediaItem)
        } else {
            player?.setMediaItem(MediaItem.fromUri(videoUri))
        }

        // Set track selection parameters to prefer Arabic text tracks
        val currentParams = player?.trackSelectionParameters
        val newParams = currentParams?.buildUpon()
                ?.setPreferredTextLanguage("ar")
                ?.build()
        player?.let { it.trackSelectionParameters = newParams }

        // Restore playback state if available
        if (savedInstanceState != null) {
            val savedPosition = savedInstanceState.getLong(KEY_POSITION, C.TIME_UNSET)
            if (savedPosition != C.TIME_UNSET) {
                player?.seekTo(savedPosition)
            }
            val savedPlayWhenReady = savedInstanceState.getBoolean(KEY_PLAY_WHEN_READY, false)
            if (savedPlayWhenReady) {
                player?.play()
            } else {
                player?.pause()
            }
        }
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
        player?.pause()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // No-op; player continues.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val currentPos = player?.currentPosition ?: C.TIME_UNSET
        outState.putLong(KEY_POSITION, currentPos)
        outState.putBoolean(KEY_PLAY_WHEN_READY, player?.playWhenReady ?: false)
    }
}