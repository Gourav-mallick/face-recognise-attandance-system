package com.digitaledu.selfieattendance.utility

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.SessionVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object StorageManager {

    private const val TAG = "StorageManager"
    const val STORAGE_THRESHOLD_PERCENT = 90.0

    data class StorageStats(
        val totalBytes: Long,
        val freeBytes: Long,
        val usedBytes: Long,
        val usedPercentage: Double
    )

    /**
     * Returns storage statistics for internal device data directory.
     */
    fun getDeviceStorageStats(): StorageStats {
        val path = Environment.getDataDirectory().path
        val stat = StatFs(path)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes
        val usedBytes = totalBytes - freeBytes
        val usedPercentage = if (totalBytes > 0) {
            (usedBytes.toDouble() / totalBytes.toDouble()) * 100.0
        } else 0.0

        return StorageStats(totalBytes, freeBytes, usedBytes, usedPercentage)
    }

    /**
     * Checks available storage and initiates oldest-first cleanup if storage usage >= threshold (90%).
     * Deletes oldest local recordings sequentially until storage drops below threshold,
     * ensuring new session recordings can always be saved cleanly.
     */
    suspend fun checkAndRunOldestFirstCleanup(context: Context) {
        withContext(Dispatchers.IO) {
            var stats = getDeviceStorageStats()
            Log.d(TAG, "Current device storage usage: ${String.format("%.2f", stats.usedPercentage)}%")

            if (stats.usedPercentage < STORAGE_THRESHOLD_PERCENT) {
                Log.d(TAG, "Storage usage (${String.format("%.2f", stats.usedPercentage)}%) is below ${STORAGE_THRESHOLD_PERCENT}%. No cleanup needed.")
                return@withContext
            }

            Log.w(TAG, "Storage threshold reached (${String.format("%.2f", stats.usedPercentage)}% >= ${STORAGE_THRESHOLD_PERCENT}%). Initiating oldest-first cleanup...")

            val db = AppDatabase.getDatabase(context)
            val dao = db.sessionVideoDao()
            val oldestRecordings = dao.getOldestSessionVideos()

            if (oldestRecordings.isEmpty()) {
                Log.w(TAG, "No session recordings available in database for cleanup.")
                return@withContext
            }

            for (recording in oldestRecordings) {
                // Check storage again to see if usage has dropped below threshold
                stats = getDeviceStorageStats()
                if (stats.usedPercentage < STORAGE_THRESHOLD_PERCENT) {
                    Log.d(TAG, "Storage usage reduced to ${String.format("%.2f", stats.usedPercentage)}%. Stopping cleanup.")
                    break
                }

                // Resolve encrypted file path (including external/internal fallbacks)
                var encFile = File(recording.encVideoPath)
                if (!encFile.exists()) {
                    val baseDir1 = context.getExternalFilesDir(null) ?: context.filesDir
                    val fallback1 = File(File(baseDir1, "session_recordings"), encFile.name)
                    val fallback2 = File(File(context.filesDir, "session_recordings"), encFile.name)
                    if (fallback1.exists()) {
                        encFile = fallback1
                    } else if (fallback2.exists()) {
                        encFile = fallback2
                    }
                }

                if (UploadManager.isServerUploadEnabled) {
                    if (recording.uploadStatus == SessionVideo.UPLOAD_STATUS_UPLOADED) {
                        deleteLocalEncryptedCopy(dao, recording, encFile)
                    } else {
                        Log.d(TAG, "Attempting server upload before cleanup for session: ${recording.sessionId}")
                        UploadManager.uploadSessionRecording(context, recording) { success ->
                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                deleteLocalEncryptedCopy(dao, recording, encFile)
                            }
                        }
                    }
                } else {
                    // Local mode: Delete oldest encrypted video file to free up storage for new session
                    deleteLocalEncryptedCopy(dao, recording, encFile)
                }
            }
        }
    }

    private suspend fun deleteLocalEncryptedCopy(
        dao: com.digitaledu.selfieattendance.db.dao.SessionVideoDao,
        recording: SessionVideo,
        encFile: File
    ) {
        if (encFile.exists()) {
            val deleted = encFile.delete()
            Log.d(TAG, "Deleted oldest local encrypted recording: ${encFile.absolutePath} (Result: $deleted)")
        }
        dao.updateUploadStatus(recording.sessionId, SessionVideo.UPLOAD_STATUS_DELETED_LOCAL)
    }
}
