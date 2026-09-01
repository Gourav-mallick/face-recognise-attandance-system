package com.digitaledu.selfieattendance.view

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.SessionVideo
import com.digitaledu.selfieattendance.utility.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionRecordingsActivity : AppCompatActivity() {

    private lateinit var rvSessionRecordings: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var tvStorageInfo: TextView
    private lateinit var tvStorageStatusBadge: TextView
    private lateinit var adapter: SessionRecordingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_recordings)

        val topBarLayout = findViewById<View>(R.id.topBarLayout)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(topBarLayout) { v, insets ->
            val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, statusBarInsets.top, v.paddingRight, v.paddingBottom)
            insets
        }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        tvStorageInfo = findViewById(R.id.tvStorageInfo)
        tvStorageStatusBadge = findViewById(R.id.tvStorageStatusBadge)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        rvSessionRecordings = findViewById(R.id.rvSessionRecordings)

        rvSessionRecordings.layoutManager = LinearLayoutManager(this)
        adapter = SessionRecordingsAdapter(
            onPlayClicked = { sessionVideo ->
                openVideoPlayer(sessionVideo)
            },
            onDeleteClicked = { sessionVideo ->
                confirmAndDeleteRecording(sessionVideo)
            }
        )
        rvSessionRecordings.adapter = adapter

        updateStorageBanner()
        loadSessionRecordings()
    }

    override fun onResume() {
        super.onResume()
        loadSessionRecordings()
        updateStorageBanner()
    }

    private fun updateStorageBanner() {
        val stats = StorageManager.getDeviceStorageStats()
        val formattedPercent = String.format("%.1f", stats.usedPercentage)
        tvStorageInfo.text = "Device Storage Used: $formattedPercent% (Cleanup threshold: 70%)"

        if (stats.usedPercentage >= StorageManager.STORAGE_THRESHOLD_PERCENT) {
            tvStorageStatusBadge.text = "HIGH USAGE"
            tvStorageStatusBadge.setTextColor(Color.parseColor("#991B1B"))
            tvStorageStatusBadge.setBackgroundColor(Color.parseColor("#FEE2E2"))
        } else {
            tvStorageStatusBadge.text = "NORMAL"
            tvStorageStatusBadge.setTextColor(Color.parseColor("#166534"))
            tvStorageStatusBadge.setBackgroundColor(Color.parseColor("#DCFCE7"))
        }
    }

    private fun loadSessionRecordings() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val videos = db.sessionVideoDao().getAllSessionVideos()

            withContext(Dispatchers.Main) {
                if (videos.isEmpty()) {
                    tvEmptyState.visibility = View.VISIBLE
                    rvSessionRecordings.visibility = View.GONE
                } else {
                    tvEmptyState.visibility = View.GONE
                    rvSessionRecordings.visibility = View.VISIBLE
                    adapter.submitList(videos)
                }
            }
        }
    }

    private fun confirmAndDeleteRecording(sessionVideo: SessionVideo) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete Session Recording")
            .setMessage("Are you sure you want to delete the recording for ${sessionVideo.teacherName} (${sessionVideo.date})?")
            .setPositiveButton("Delete") { _, _ ->
                deleteRecording(sessionVideo)
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(android.graphics.Color.parseColor("#DC2626"))
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
            ?.setTextColor(android.graphics.Color.parseColor("#4B5563"))
    }

    private fun deleteRecording(sessionVideo: SessionVideo) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Delete physical video file on disk
                val file = java.io.File(sessionVideo.encVideoPath)
                if (file.exists()) file.delete()

                val baseDir1 = getExternalFilesDir(null) ?: filesDir
                val fallback1 = java.io.File(java.io.File(baseDir1, "session_recordings"), file.name)
                val fallback2 = java.io.File(java.io.File(filesDir, "session_recordings"), file.name)
                val mp4FallbackName = file.nameWithoutExtension + ".mp4"
                val fallback3 = java.io.File(java.io.File(baseDir1, "session_recordings"), mp4FallbackName)
                val fallback4 = java.io.File(java.io.File(filesDir, "session_recordings"), mp4FallbackName)

                listOf(fallback1, fallback2, fallback3, fallback4).forEach { f ->
                    if (f.exists()) f.delete()
                }

                // Delete DB record
                val db = AppDatabase.getDatabase(applicationContext)
                db.sessionVideoDao().deleteSessionVideo(sessionVideo.sessionId)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SessionRecordingsActivity, "Session recording deleted", Toast.LENGTH_SHORT).show()
                    loadSessionRecordings()
                    updateStorageBanner()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SessionRecordingsActivity, "Failed to delete: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openVideoPlayer(sessionVideo: SessionVideo) {
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_SESSION_VIDEO, sessionVideo)
        }
        startActivity(intent)
    }
}
