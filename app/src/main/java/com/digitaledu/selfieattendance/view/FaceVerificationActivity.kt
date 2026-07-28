package com.digitaledu.selfieattendance.view

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.ml.ActiveLivenessVerifier
import com.digitaledu.selfieattendance.ml.YuNetFace
import com.digitaledu.selfieattendance.ml.YuNetSFaceEngine
import com.digitaledu.selfieattendance.utility.VoiceGuidance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class FaceVerificationActivity : ComponentActivity() {
    private lateinit var viewFinder: PreviewView
    private lateinit var faceGuide: android.view.View
    private lateinit var landmarkOverlay: FaceLandmarkOverlay
    private lateinit var tvBlinkWarning: TextView
    private lateinit var tvStudentName: TextView
    private lateinit var tvAttempts: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var btnCancel: Button
    private lateinit var db: AppDatabase
    private lateinit var faceEngine: YuNetSFaceEngine
    private lateinit var livenessVerifier: ActiveLivenessVerifier
    private lateinit var voiceGuidance: VoiceGuidance
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null

    private var studentId = ""
    private var studentName = ""
    private var targetEmbedding: FloatArray? = null
    @Volatile private var isVerifying = false
    private var faceStableStart = 0L
    private var lastProcessTime = 0L
    private var previousFace: YuNetFace? = null
    private var attemptCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_verification)
        viewFinder = findViewById(R.id.viewFinder)
        faceGuide = findViewById(R.id.faceGuide)
        landmarkOverlay = findViewById(R.id.landmarkOverlay)
        tvBlinkWarning = findViewById(R.id.tvBlinkWarning)
        tvStudentName = findViewById(R.id.tvStudentName)
        tvAttempts = findViewById(R.id.tvAttempts)
        tvInstruction = findViewById(R.id.tvInstruction)
        btnCancel = findViewById(R.id.btnCancel)

        studentId = intent.getStringExtra("STUDENT_ID").orEmpty()
        studentName = intent.getStringExtra("STUDENT_NAME").orEmpty()
        tvStudentName.text = studentName
        updateAttemptUi()

        db = AppDatabase.getDatabase(this)
        faceEngine = YuNetSFaceEngine(applicationContext)
        livenessVerifier = ActiveLivenessVerifier()
        voiceGuidance = VoiceGuidance(applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()
        btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        loadTargetEmbedding()
    }

    private fun loadTargetEmbedding() {
        lifecycleScope.launch(Dispatchers.IO) {
            val value = db.studentsDao().getStudentById(studentId)?.embedding
            val parsed = value
                ?.split(",")
                ?.mapNotNull { it.trim().toFloatOrNull() }
                ?.toFloatArray()

            if (parsed == null || parsed.size != YuNetSFaceEngine.SFACE_DIMENSIONS) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@FaceVerificationActivity,
                        "No compatible registered face found for this student",
                        Toast.LENGTH_LONG
                    ).show()
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
                return@launch
            }
            targetEmbedding = YuNetSFaceEngine.l2Normalize(parsed)
            withContext(Dispatchers.Main) {
                if (cameraPermissionGranted()) startCamera()
                else requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis = analysis
            analysis.setAnalyzer(cameraExecutor, ::processFrame)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (error: Exception) {
                Log.e(TAG, "Camera binding failed", error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (isVerifying || now - lastProcessTime < FRAME_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastProcessTime = now
        var frame: Bitmap? = null

        try {
            val upright = imageProxyToBitmapUpright(imageProxy)
            frame = mirrorBitmap(upright)
            upright.recycle()
            val liveness = livenessVerifier.update(frame)
            val face = faceEngine.detect(frame)
                .maxByOrNull { it.bounds.width() * it.bounds.height() }

            if (face == null) {
                faceStableStart = 0L
                previousFace = null
                runOnUiThread {
                    landmarkOverlay.clear()
                    faceGuide.background.setTint(Color.YELLOW)
                    tvInstruction.text = "Place your live face inside the oval"
                    voiceGuidance.guide(
                        "Place your face inside the oval",
                        "verification_no_face"
                    )
                }
                return
            }

            // Verification is recognition, so use the normal quality gate. The strict
            // gate is intended for enrollment and can prevent valid registered users
            // from ever reaching the matching step on ordinary phone cameras.
            val quality = faceEngine.assessQuality(frame, face, strict = true)
            val stable = isStable(face)
            if (!quality.accepted || !stable || !liveness.passed) faceStableStart = 0L
            else if (faceStableStart == 0L) faceStableStart = now
            previousFace = face

            runOnUiThread {
                landmarkOverlay.show(face.landmarks, frame.width, frame.height)
                faceGuide.background.setTint(
                    when {
                        liveness.passed && quality.accepted && stable -> Color.GREEN
                        quality.accepted -> Color.rgb(30, 94, 255)
                        else -> Color.YELLOW
                    }
                )
                tvInstruction.text = if (liveness.passed) quality.guidance else liveness.guidance
                voiceGuidance.guide(
                    if (liveness.passed) quality.guidance else liveness.guidance,
                    if (liveness.passed) {
                        "verification_quality:${quality.guidance}"
                    } else {
                        "verification_liveness:${liveness.guidance}"
                    }
                )
                tvBlinkWarning.text = if (liveness.passed) {
                    "Live face confirmed"
                } else {
                    "Complete the liveness instructions"
                }
            }

            if (
                liveness.passed &&
                quality.accepted &&
                stable &&
                now - faceStableStart >= LOCK_DURATION_MS
            ) {
                isVerifying = true
                val embedding = faceEngine.embedding(frame, face)
                runOnUiThread { verifyEmbedding(embedding) }
            }
        } catch (error: Exception) {
            Log.e(TAG, "YuNet/SFace verification failed", error)
            runOnUiThread { tvInstruction.text = "Face verification unavailable — retrying" }
        } finally {
            frame?.recycle()
            imageProxy.close()
        }
    }

    private fun isStable(face: YuNetFace): Boolean {
        val previous = previousFace ?: return false
        val tolerance = face.bounds.width() * 0.045f
        return abs(face.bounds.centerX() - previous.bounds.centerX()) < tolerance &&
            abs(face.bounds.centerY() - previous.bounds.centerY()) < tolerance
    }

    private fun verifyEmbedding(faceEmbedding: FloatArray) {
        val target = targetEmbedding ?: return
        val similarity = YuNetSFaceEngine.cosineSimilarity(target, faceEmbedding)
        Log.d(TAG, "SFace cosine similarity=$similarity")

        if (similarity >= com.digitaledu.selfieattendance.ml.FaceDetectionConfig.recognitionCosineThreshold) {
            Toast.makeText(this, "Face verified successfully", Toast.LENGTH_SHORT).show()
            val spokenName = VoiceGuidance.speakableName(studentName)
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra("VERIFIED_STUDENT_ID", studentId)
            )
            voiceGuidance.announceThen(
                message = "Thank you, $spokenName. Verified.",
                key = "verification_success"
            ) {
                if (!isFinishing) finish()
            }
            return
        }

        attemptCount++
        resetAttempt()
        if (attemptCount >= MAX_ATTEMPTS) {
            Toast.makeText(this, "Face verification failed after 3 attempts", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            voiceGuidance.announceThen(
                message = "Verification failed.",
                key = "verification_final_failure"
            ) {
                if (!isFinishing) finish()
            }
        } else {
            Toast.makeText(this, "Face not matched. Complete liveness and try again.", Toast.LENGTH_SHORT).show()
            voiceGuidance.announce(
                "No match. Try again.",
                "verification_failure_$attemptCount"
            )
            updateAttemptUi()
        }
    }

    private fun resetAttempt() {
        isVerifying = false
        faceStableStart = 0L
        previousFace = null
        livenessVerifier.reset()
        voiceGuidance.resetGuidance()
        landmarkOverlay.clear()
    }

    private fun updateAttemptUi() {
        tvAttempts.text = "Attempt ${attemptCount + 1} of $MAX_ATTEMPTS"
    }

    private fun cameraPermissionGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        if (cameraPermissionGranted()) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required for face verification", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun imageProxyToBitmapUpright(image: ImageProxy): Bitmap {
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val stream = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 95, stream)
        val raw = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
        val rotation = image.imageInfo.rotationDegrees.toFloat()
        if (rotation == 0f) return raw
        val matrix = Matrix().apply { postRotate(rotation) }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        raw.recycle()
        return upright
    }

    private fun mirrorBitmap(source: Bitmap): Bitmap {
        val matrix = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer.duplicate()
        val uBuffer = image.planes[1].buffer.duplicate()
        val vBuffer = image.planes[2].buffer.duplicate()
        val output = ByteArray(image.width * image.height * 3 / 2)
        val yRow = ByteArray(image.planes[0].rowStride)
        var offset = 0
        for (row in 0 until image.height) {
            val length = minOf(yBuffer.remaining(), image.planes[0].rowStride)
            yBuffer.get(yRow, 0, length)
            for (column in 0 until image.width) {
                output[offset++] = yRow[minOf(column * image.planes[0].pixelStride, length - 1)]
            }
        }
        val uRow = ByteArray(image.planes[1].rowStride)
        val vRow = ByteArray(image.planes[2].rowStride)
        for (row in 0 until image.height / 2) {
            val uLength = minOf(uBuffer.remaining(), image.planes[1].rowStride)
            val vLength = minOf(vBuffer.remaining(), image.planes[2].rowStride)
            uBuffer.get(uRow, 0, uLength)
            vBuffer.get(vRow, 0, vLength)
            for (column in 0 until image.width / 2) {
                output[offset++] = vRow[minOf(column * image.planes[2].pixelStride, vLength - 1)]
                output[offset++] = uRow[minOf(column * image.planes[1].pixelStride, uLength - 1)]
            }
        }
        return output
    }

    override fun onDestroy() {
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraExecutor.shutdown()
        faceEngine.close()
        livenessVerifier.close()
        voiceGuidance.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FaceVerification"
        private const val CAMERA_REQUEST = 101
        private const val MAX_ATTEMPTS = 3
        private const val FRAME_INTERVAL_MS = 160L
        private const val LOCK_DURATION_MS = 700L
    }
}
