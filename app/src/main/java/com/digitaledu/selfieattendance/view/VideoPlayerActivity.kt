package com.digitaledu.selfieattendance.view

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.db.entity.SessionVideo
import com.digitaledu.selfieattendance.utility.EncryptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_VIDEO = "extra_session_video"
        private const val TAG = "VideoPlayerActivity"
    }

    private var exoPlayer: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var tvPlayerSessionTitle: TextView
    private var tempPlaybackFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        val topBarLayout = findViewById<View>(R.id.topBarLayout)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(topBarLayout) { v, insets ->
            val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, statusBarInsets.top, v.paddingRight, v.paddingBottom)
            insets
        }

        playerView = findViewById(R.id.playerView)
        tvPlayerSessionTitle = findViewById(R.id.tvPlayerSessionTitle)

        findViewById<ImageView>(R.id.btnPlayerClose).setOnClickListener {
            finish()
        }

        val sessionVideo = intent.getParcelableExtra<SessionVideo>(EXTRA_SESSION_VIDEO)
        if (sessionVideo == null) {
            Toast.makeText(this, "Invalid session video data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvPlayerSessionTitle.text = "${sessionVideo.date} • ${sessionVideo.teacherName} (${sessionVideo.startTime} – ${sessionVideo.endTime})"

        var videoFile = File(sessionVideo.encVideoPath)
        if (!videoFile.exists()) {
            val baseDir1 = getExternalFilesDir(null) ?: filesDir
            val fallback1 = File(File(baseDir1, "session_recordings"), videoFile.name)
            val fallback2 = File(File(filesDir, "session_recordings"), videoFile.name)
            val mp4FallbackName = videoFile.nameWithoutExtension + ".mp4"
            val fallback3 = File(File(baseDir1, "session_recordings"), mp4FallbackName)
            val fallback4 = File(File(filesDir, "session_recordings"), mp4FallbackName)

            when {
                fallback1.exists() -> videoFile = fallback1
                fallback2.exists() -> videoFile = fallback2
                fallback3.exists() -> videoFile = fallback3
                fallback4.exists() -> videoFile = fallback4
            }
        }

        if (!videoFile.exists() || videoFile.length() == 0L) {
            Toast.makeText(this, "Session video file not found or empty", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Video file not found or empty: ${videoFile.absolutePath}")
            finish()
            return
        }

        prepareAndPlayVideo(videoFile, sessionVideo.ivBase64)
    }

    private fun prepareAndPlayVideo(videoFile: File, ivBase64: String) {
        // Direct MP4 Playback (No Decryption needed!)
        if (videoFile.name.endsWith(".mp4", ignoreCase = true) || ivBase64.isBlank()) {
            Log.d(TAG, "Playing direct MP4 video file: ${videoFile.absolutePath}")
            initializePlayer(videoFile)
            return
        }

        // Legacy support for older encrypted .enc files
        lifecycleScope.launch(Dispatchers.IO) {
            val tempFile = File(cacheDir, "temp_play_${videoFile.nameWithoutExtension}.mp4")
            if (tempFile.exists()) {
                tempFile.delete()
            }

            Log.d(TAG, "Decrypting legacy encrypted video for playback to: ${tempFile.absolutePath}")
            val success = EncryptionManager.decryptToFile(videoFile, tempFile, ivBase64)

            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    if (success && tempFile.exists() && tempFile.length() > 0L) {
                        tempPlaybackFile = tempFile
                        initializePlayer(tempFile)
                    } else {
                        Log.e(TAG, "Failed to decrypt legacy video file for playback")
                        Toast.makeText(
                            this@VideoPlayerActivity,
                            "Failed to decrypt video file",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                } else {
                    if (tempFile.exists()) tempFile.delete()
                }
            }
        }
    }

    private fun initializePlayer(decryptedFile: File) {
        try {
            val player = ExoPlayer.Builder(this).build()
            this.exoPlayer = player
            playerView.player = player

            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                    Toast.makeText(
                        this@VideoPlayerActivity,
                        "Playback error: ${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })

            val mediaItem = MediaItem.fromUri(Uri.fromFile(decryptedFile))
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ExoPlayer", e)
            Toast.makeText(this, "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            exoPlayer?.release()
            exoPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ExoPlayer", e)
        }

        // Delete temporary decrypted playback file immediately on exit
        tempPlaybackFile?.let { file ->
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Deleted temp playback video file: $deleted")
            }
        }
        tempPlaybackFile = null
    }
}
