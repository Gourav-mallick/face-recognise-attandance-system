package com.digitaledu.selfieattendance.ml

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

data class LivenessResult(
    val passed: Boolean,
    val guidance: String
)

/**
 * Active on-device liveness challenge used before SFace enrollment or matching.
 *
 * The analyzer must call [update] from one background thread. The user must complete
 * one open -> closed -> open blink. A successful challenge remains valid briefly so
 * registration can collect its three consistent samples.
 *
 * This blocks static printed/displayed images. It is not a replacement for a trained
 * presentation-attack detector against sophisticated replay or deepfake attacks.
 */
class ActiveLivenessVerifier : AutoCloseable {
    @Volatile
    private var closed = false

    private enum class Stage {
        WAITING_FOR_OPEN_EYES,
        WAITING_FOR_EYES_CLOSED,
        WAITING_FOR_EYES_REOPENED,
        PASSED
    }

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
    )
    private var stage = Stage.WAITING_FOR_OPEN_EYES
    private var challengeStartedAt = 0L
    private var passedAt = 0L
    private var faceMissingSince = 0L

    @Synchronized
    fun update(bitmap: Bitmap): LivenessResult {
        check(!closed) { "Liveness verifier is closed" }
        val now = SystemClock.elapsedRealtime()
        if (stage == Stage.PASSED) {
            if (now - passedAt <= PASS_VALIDITY_MS) {
                return LivenessResult(true, "Live face confirmed")
            }
            reset()
        }
        if (challengeStartedAt == 0L) challengeStartedAt = now
        if (now - challengeStartedAt > CHALLENGE_TIMEOUT_MS) reset()

        return try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            if (face == null) {
                if (faceMissingSince == 0L) faceMissingSince = now
                if (now - faceMissingSince >= FACE_MISSING_RESET_MS) reset()
                LivenessResult(false, "Place your live face inside the oval")
            } else {
                faceMissingSince = 0L
                advance(face, now)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Active liveness frame failed", error)
            LivenessResult(false, "Unable to verify liveness — try again")
        }
    }

    @Synchronized
    fun reset() {
        if (closed) return
        stage = Stage.WAITING_FOR_OPEN_EYES
        challengeStartedAt = 0L
        passedAt = 0L
        faceMissingSince = 0L
    }

    private fun advance(face: Face, now: Long): LivenessResult {
        val leftEye = face.leftEyeOpenProbability
        val rightEye = face.rightEyeOpenProbability
        val eyesAvailable = leftEye != null && rightEye != null
        val eyesOpen = eyesAvailable && leftEye!! >= EYE_OPEN_THRESHOLD &&
            rightEye!! >= EYE_OPEN_THRESHOLD
        val eyesClosed = eyesAvailable && leftEye!! <= EYE_CLOSED_THRESHOLD &&
            rightEye!! <= EYE_CLOSED_THRESHOLD

        when (stage) {
            Stage.WAITING_FOR_OPEN_EYES -> {
                if (eyesOpen) {
                    stage = Stage.WAITING_FOR_EYES_CLOSED
                }
            }

            Stage.WAITING_FOR_EYES_CLOSED -> {
                if (eyesClosed) stage = Stage.WAITING_FOR_EYES_REOPENED
            }

            Stage.WAITING_FOR_EYES_REOPENED -> {
                if (eyesOpen) markPassed(now)
            }

            Stage.PASSED -> Unit
        }

        return when (stage) {
            Stage.WAITING_FOR_OPEN_EYES ->
                LivenessResult(false, "Look straight at the camera with eyes open")
            Stage.WAITING_FOR_EYES_CLOSED ->
                LivenessResult(false, "Blink slowly once to verify liveness")
            Stage.WAITING_FOR_EYES_REOPENED ->
                LivenessResult(false, "Open your eyes")
            Stage.PASSED ->
                LivenessResult(true, "Live face confirmed")
        }
    }

    private fun markPassed(now: Long) {
        stage = Stage.PASSED
        passedAt = now
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        detector.close()
    }

    companion object {
        private const val TAG = "ActiveLiveness"
        private const val EYE_OPEN_THRESHOLD = 0.65f
        private const val EYE_CLOSED_THRESHOLD = 0.35f
        private const val CHALLENGE_TIMEOUT_MS = 12_000L
        private const val FACE_MISSING_RESET_MS = 800L
        private const val PASS_VALIDITY_MS = 5_000L
    }
}
