package com.digitaledu.selfieattendance.utility

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.SessionVideo
import kotlinx.coroutines.CoroutineScope
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
     * Checks available storage and initiates oldest-first cleanup if storage usage >= 70%.
     */
    suspend fun checkAndRunOldestFirstCleanup(context: Context) {
        withContext(Dispatchers.IO) {
            var stats = getDeviceStorageStats()
            Log.d(TAG, "Current device storage usage: ${String.format("%.2f", stats.usedPercentage)}%")

            if (stats.usedPercentage < STORAGE_THRESHOLD_PERCENT) {
                Log.d(TAG, "Storage usage is below ${STORAGE_THRESHOLD_PERCENT}%. No cleanup needed.")
                return@withContext
            }

            Log.w(TAG, "Storage threshold reached (${String.format("%.2f", stats.usedPercentage)}% >= ${STORAGE_THRESHOLD_PERCENT}%). Initiating oldest-first cleanup...")

            val db = AppDatabase.getDatabase(context)
            val dao = db.sessionVideoDao()
            val oldestRecordings = dao.getOldestSessionVideos()

            if (oldestRecordings.isEmpty()) {
                Log.w(TAG, "No session recordings available for cleanup.")
                return@withContext
            }

            for (recording in oldestRecordings) {
                // Check storage again to see if space is now available
                stats = getDeviceStorageStats()
                if (stats.usedPercentage < STORAGE_THRESHOLD_PERCENT) {
                    Log.d(TAG, "Storage usage reduced to ${String.format("%.2f", stats.usedPercentage)}%. Stopping cleanup.")
                    break
                }

                // SAFETY RULE: Never delete if local file doesn't exist
                val encFile = File(recording.encVideoPath)

                // If remote upload is enabled, upload first then delete
                if (UploadManager.isServerUploadEnabled) {
                    if (recording.uploadStatus == SessionVideo.UPLOAD_STATUS_UPLOADED) {
                        deleteLocalEncryptedCopy(dao, recording, encFile)
                    } else {
                        Log.d(TAG, "Attempting upload before cleanup for session: ${recording.sessionId}")
                        UploadManager.uploadSessionRecording(context, recording) { success ->
                            if (success) {
                                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                    deleteLocalEncryptedCopy(dao, recording, encFile)
                                }
                            } else {
                                Log.w(TAG, "SAFETY RULE ENFORCED: Upload failed for session ${recording.sessionId}. Keeping local copy.")
                            }
                        }

                    }
                } else {
                    // Local-only mode: Server upload disabled.
                    // Keep local copy to prevent data loss. Log warning.
                    Log.w(TAG, "Storage usage HIGH (${String.format("%.2f", stats.usedPercentage)}%), but server upload is disabled. Preserving local session video: ${recording.sessionId}")
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
            if (deleted) {
                Log.d(TAG, "Deleted local encrypted recording: ${recording.encVideoPath}")
            }
        }
        dao.updateUploadStatus(recording.sessionId, SessionVideo.UPLOAD_STATUS_DELETED_LOCAL)
    }
}
