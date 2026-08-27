package com.digitaledu.selfieattendance.utility

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.SessionVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingManager {

    private const val TAG = "RecordingManager"

    @Volatile
    var isRecordingActive: Boolean = false
        private set

    private var activeRecording: Recording? = null

    // Use strong reference — lifecycle cleanup prevents leaks.
    // WeakReference caused premature GC of VideoCapture during active recording.
    private var activeVideoCapture: VideoCapture<Recorder>? = null

    private var tempVideoFile: File? = null
    private var currentSessionId: String? = null
    private var currentTeacherId: String? = null
    private var currentTeacherName: String? = null
    private var sessionStartTimeMillis: Long = 0L

    var onRecordingStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Clears only the VideoCapture reference.
     * Called from fragment onDestroyView() to break the GC chain
     * WITHOUT clearing pending session params or the recording state callback.
     *
     * This allows the next fragment to call onVideoCaptureReady() and
     * pick up any pending recording that hasn't started yet.
     */
    fun releaseVideoCapture() {
        activeVideoCapture = null
        // DO NOT clear onRecordingStateChanged here — the next fragment will set it
        // DO NOT clear currentSessionId/teacherId — they are pending params for the next fragment
    }

    /**
     * Updates teacher metadata for the active or pending recording session once teacher face is verified.
     */
    fun updateTeacherInfo(teacherId: String, teacherName: String) {
        currentTeacherId = teacherId
        currentTeacherName = teacherName
        Log.d(TAG, "Updated teacher info for active recording: $teacherName ($teacherId)")
    }

    /**
     * Fully resets ALL recording state. Only call this when the session is
     * truly over (end session, mass bunk, discard).
     */
    fun resetSession() {
        activeVideoCapture = null
        onRecordingStateChanged = null
        currentSessionId = null
        currentTeacherId = null
        currentTeacherName = null
        sessionStartTimeMillis = 0L
    }


    val activeSessionId: String?
        get() = currentSessionId

    /**
     * Initializes CameraX VideoCapture for binding to CameraProvider alongside ImageAnalysis/Preview.
     */
    fun createVideoCapture(): VideoCapture<Recorder> {
        val qualitySelector = QualitySelector.from(
            Quality.SD,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        val videoCapture = VideoCapture.withOutput(recorder)
        activeVideoCapture = videoCapture
        return videoCapture
    }

    /**
     * Called by Fragments as soon as CameraX VideoCapture is bound to the lifecycle.
     * If a session was queued (startRecording was called but VideoCapture wasn't ready),
     * this will kick off the actual recording.
     */
    fun onVideoCaptureReady(context: Context, videoCapture: VideoCapture<Recorder>) {
        activeVideoCapture = videoCapture
        val sessionId = currentSessionId
        val teacherId = currentTeacherId
        val teacherName = currentTeacherName
        if (!isRecordingActive && sessionId != null && teacherId != null && teacherName != null) {
            Log.d(TAG, "VideoCapture is now ready! Starting pending session recording for session: $sessionId")
            startRecording(context, sessionId, teacherId, teacherName, videoCapture)
        }
    }

    /**
     * Starts video recording for an attendance session.
     */
    fun startRecording(
        context: Context,
        sessionId: String,
        teacherId: String,
        teacherName: String,
        videoCapture: VideoCapture<Recorder>? = activeVideoCapture
    ) {
        if (isRecordingActive) {
            Log.w(TAG, "Recording is already active for session: $currentSessionId")
            return
        }

        currentSessionId = sessionId
        currentTeacherId = teacherId
        currentTeacherName = teacherName
        if (sessionStartTimeMillis == 0L) {
            sessionStartTimeMillis = System.currentTimeMillis()
        }

        val targetVideoCapture = videoCapture ?: activeVideoCapture
        if (targetVideoCapture == null) {
            Log.w(TAG, "VideoCapture is not ready yet. Session $sessionId saved as pending recording.")
            return
        }

        try {
            val appContext = context.applicationContext
            val baseDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
            val recordDir = File(baseDir, "session_recordings").apply { if (!exists()) mkdirs() }
            val tempFile = File(recordDir, "temp_session_${sessionId}_${System.currentTimeMillis()}.mp4")
            tempVideoFile = tempFile

            val fileOutputOptions = FileOutputOptions.Builder(tempFile).build()

            val recording = targetVideoCapture.output
                .prepareRecording(appContext, fileOutputOptions)
                .start(ContextCompat.getMainExecutor(appContext)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecordingActive = true
                            Log.d(TAG, "Session video recording started for session: $sessionId")
                            onRecordingStateChanged?.invoke(true)
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecordingActive = false
                            onRecordingStateChanged?.invoke(false)
                            if (!event.hasError()) {
                                Log.d(TAG, "Video recording finalized cleanly for session: $sessionId")
                            } else {
                                Log.e(TAG, "Video recording error event: ${event.error}")
                            }
                        }
                    }
                }

            activeRecording = recording
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session video recording", e)
            isRecordingActive = false
            onRecordingStateChanged?.invoke(false)
        }
    }

    /**
     * Cancels any pending or active recording WITHOUT saving.
     * Used for discard/cancel session flows.
     */
    fun cancelPendingAndStopRecording() {
        Log.d(TAG, "Cancelling recording for session: $currentSessionId")

        try {
            activeRecording?.stop()
            activeRecording?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping active recording during cancel", e)
        }

        // Delete any temp file without encrypting
        tempVideoFile?.let {
            if (it.exists()) {
                val deleted = it.delete()
                Log.d(TAG, "Deleted temp video file on cancel: $deleted")
            }
        }

        activeRecording = null
        activeVideoCapture = null
        isRecordingActive = false
        onRecordingStateChanged?.invoke(false)
        currentSessionId = null
        currentTeacherId = null
        currentTeacherName = null
        sessionStartTimeMillis = 0L
        tempVideoFile = null
    }


    /**
     * Stops recording, encrypts video, saves SessionVideo entity, and deletes raw file.
     */
    fun stopRecording(
        context: Context,
        studentCount: Int,
        onComplete: ((SessionVideo?) -> Unit)? = null
    ) {
        val appContext = context.applicationContext

        val rawFile = tempVideoFile
        val recording = activeRecording

        if (!isRecordingActive && recording == null && (rawFile == null || !rawFile.exists() || rawFile.length() == 0L)) {
            Log.w(TAG, "stopRecording called, but no active recording session or valid temp file found.")
            resetSession()
            onComplete?.invoke(null)
            return
        }
        val sessionId = currentSessionId ?: "UNKNOWN_${System.currentTimeMillis()}"
        val teacherId = currentTeacherId ?: "123"
        val teacherName = currentTeacherName ?: "Teacher"
        val startTimeMs = sessionStartTimeMillis
        val endTimeMs = System.currentTimeMillis()
        val durationMs = if (startTimeMs > 0) endTimeMs - startTimeMs else 0L

        try {
            recording?.stop()
            recording?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping active recording", e)
        }

        activeRecording = null
        activeVideoCapture = null
        isRecordingActive = false
        onRecordingStateChanged?.invoke(false)
        currentSessionId = null
        currentTeacherId = null
        currentTeacherName = null
        sessionStartTimeMillis = 0L

        CoroutineScope(Dispatchers.IO).launch {
            if (rawFile == null || !rawFile.exists()) {
                Log.e(TAG, "Raw temp video file does not exist for session: $sessionId")
                onComplete?.invoke(null)
                return@launch
            }

            // Wait briefly for file write completion
            var retries = 0
            while (rawFile.length() == 0L && retries < 20) {
                kotlinx.coroutines.delay(100)
                retries++
            }

            val baseDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
            val recordDir = File(baseDir, "session_recordings").apply { if (!exists()) mkdirs() }
            val encFile = File(recordDir, "session_$sessionId.enc")

            // Encrypt raw file to AES-256 GCM
            val ivBase64 = EncryptionManager.encryptFile(rawFile, encFile)

            // CRITICAL SAFETY: Delete unencrypted raw video file immediately!
            if (rawFile.exists()) {
                val deletedRaw = rawFile.delete()
                Log.d(TAG, "Deleted unencrypted temp video file: $deletedRaw")
            }

            if (ivBase64 == null || !encFile.exists()) {
                Log.e(TAG, "Failed to create encrypted session recording for session: $sessionId")
                onComplete?.invoke(null)
                return@launch
            }

            val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

            val dateStr = dateFormat.format(Date(startTimeMs))
            val startTimeStr = timeFormat.format(Date(startTimeMs))
            val endTimeStr = timeFormat.format(Date(endTimeMs))

            val sessionVideo = SessionVideo(
                sessionId = sessionId,
                teacherId = teacherId,
                teacherName = teacherName,
                date = dateStr,
                startTime = startTimeStr,
                endTime = endTimeStr,
                durationMs = durationMs,
                studentCount = studentCount,
                encVideoPath = encFile.absolutePath,
                ivBase64 = ivBase64,
                uploadStatus = SessionVideo.UPLOAD_STATUS_LOCAL_ONLY,
                createdAtMillis = startTimeMs
            )

            val db = AppDatabase.getDatabase(appContext)
            db.sessionVideoDao().insertSessionVideo(sessionVideo)
            Log.d(TAG, "Saved SessionVideo record in DB: $sessionVideo")

            // Check storage and run oldest-first cleanup if usage >= threshold
            StorageManager.checkAndRunOldestFirstCleanup(appContext)

            onComplete?.invoke(sessionVideo)
        }
    }
}
