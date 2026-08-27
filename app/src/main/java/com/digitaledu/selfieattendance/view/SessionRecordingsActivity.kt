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

        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        tvStorageInfo = findViewById(R.id.tvStorageInfo)
        tvStorageStatusBadge = findViewById(R.id.tvStorageStatusBadge)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        rvSessionRecordings = findViewById(R.id.rvSessionRecordings)

        rvSessionRecordings.layoutManager = LinearLayoutManager(this)
        adapter = SessionRecordingsAdapter { sessionVideo ->
            openVideoPlayer(sessionVideo)
        }
        rvSessionRecordings.adapter = adapter

        updateStorageBanner()
        loadSessionRecordings()
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

    private fun openVideoPlayer(sessionVideo: SessionVideo) {
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_SESSION_VIDEO, sessionVideo)
        }
        startActivity(intent)
    }
}
