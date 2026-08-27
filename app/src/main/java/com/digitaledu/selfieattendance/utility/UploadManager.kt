package com.digitaledu.selfieattendance.utility

import android.content.Context
import android.util.Log
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.SessionVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UploadManager {

    private const val TAG = "UploadManager"

    // Set to true when server endpoints for session video uploads are configured
    var isServerUploadEnabled: Boolean = false

    /**
     * Handles background upload workflow for session video recording.
     */
    suspend fun uploadSessionRecording(
        context: Context,
        sessionVideo: SessionVideo,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val dao = db.sessionVideoDao()

            if (!isServerUploadEnabled) {
                Log.d(TAG, "Server upload is currently disabled. Session video kept as LOCAL_ONLY: ${sessionVideo.sessionId}")
                dao.updateUploadStatus(sessionVideo.sessionId, SessionVideo.UPLOAD_STATUS_LOCAL_ONLY)
                onResult?.invoke(false)
                return@withContext
            }

            try {
                dao.updateUploadStatus(sessionVideo.sessionId, SessionVideo.UPLOAD_STATUS_UPLOADING)
                Log.d(TAG, "Starting upload for session video: ${sessionVideo.sessionId}")

                // Simulated / Future upload network request
                // In production with remote server: Perform multipart upload here.
                val uploadSuccess = false // Set to true when remote endpoint integration is active

                if (uploadSuccess) {
                    dao.updateUploadStatus(sessionVideo.sessionId, SessionVideo.UPLOAD_STATUS_UPLOADED)
                    Log.d(TAG, "Upload completed successfully for session video: ${sessionVideo.sessionId}")
                    onResult?.invoke(true)
                } else {
                    dao.updateUploadStatus(sessionVideo.sessionId, SessionVideo.UPLOAD_STATUS_UPLOAD_FAILED)
                    Log.w(TAG, "Upload failed for session video: ${sessionVideo.sessionId}. Retaining local encrypted copy.")
                    onResult?.invoke(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload exception for session video: ${sessionVideo.sessionId}", e)
                dao.updateUploadStatus(sessionVideo.sessionId, SessionVideo.UPLOAD_STATUS_UPLOAD_FAILED)
                onResult?.invoke(false)
            }
        }
    }
}
