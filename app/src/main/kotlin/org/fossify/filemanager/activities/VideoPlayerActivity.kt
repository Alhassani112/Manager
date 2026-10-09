package org.fossify.filemanager.activities

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.fossify.commons.extensions.toast
import org.fossify.filemanager.R

class VideoPlayerActivity : SimpleActivity() {
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        playerView = findViewById(R.id.video_player_view)
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

    override fun onPause() {
        player?.pause()
        super.onPause()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        playerView?.player = null
        player?.release()
        player = null
    }
}
