package com.example.selfieAttendance.view

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.selfieAttendance.R
import com.example.selfieAttendance.ml.ActiveLivenessVerifier
import com.example.selfieAttendance.ml.YuNetFace
import com.example.selfieAttendance.ml.YuNetSFaceEngine
import com.example.selfieAttendance.utility.VoiceGuidance
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Zero-click registration capture. YuNet tracks the five landmarks and automatically
 * collects three mutually consistent high-quality frontal SFace observations, averages
 * them, and returns one normalized template to the existing registration workflow.
 */
class CameraCaptureActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var faceGuide: View
    private lateinit var landmarkOverlay: FaceLandmarkOverlay
    private lateinit var tvStep: TextView
    private lateinit var tvClass: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var engine: YuNetSFaceEngine
    private lateinit var livenessVerifier: ActiveLivenessVerifier
    private lateinit var voiceGuidance: VoiceGuidance

    private var stableSince = 0L
    private var lastFace: YuNetFace? = null
    private val enrollmentSamples = ArrayList<FloatArray>(REQUIRED_SAMPLE_COUNT)
    private var lastSampleAt = 0L
    private var faceMissingSince = 0L
    private var feedbackUntil = 0L
    @Volatile private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_capture)

        previewView = findViewById(R.id.previewView)
        faceGuide = findViewById(R.id.faceGuide)
        landmarkOverlay = findViewById(R.id.landmarkOverlay)
        tvStep = findViewById(R.id.tvStep)
        tvClass = findViewById(R.id.tvClass)
        cameraExecutor = Executors.newSingleThreadExecutor()
        engine = YuNetSFaceEngine(applicationContext)
        livenessVerifier = ActiveLivenessVerifier()
        voiceGuidance = VoiceGuidance(applicationContext)
        voiceGuidance.guide("Look at camera", "registration_start")

        val name = intent.getStringExtra("user_name").orEmpty()
        val id = intent.getStringExtra("user_id").orEmpty()
        findViewById<TextView>(R.id.tvUserInfo).text = "$name ($id)"
        tvStep.text = "Look straight at the camera"
        tvClass.text = "Searching for five facial landmarks…"

        if (com.example.selfieAttendance.utility.PermissionUtils.hasCameraPermission(this)) startCamera()
        else com.example.selfieAttendance.utility.PermissionUtils.requestCameraPermission(this, CAMERA_PERMISSION_REQUEST_CODE)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis = analysis
            analysis.setAnalyzer(cameraExecutor, ::analyze)
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(imageProxy: ImageProxy) {
        if (completed) {
            imageProxy.close()
            return
        }
        var frameToRecycle: Bitmap? = null
        try {
            val upright = imageProxyToBitmapUpright(imageProxy)
            val frame = mirrorBitmap(upright)
            frameToRecycle = frame
            if (upright !== frame) upright.recycle()
            val liveness = livenessVerifier.update(frame)
            val faces = engine.detect(frame)
            val face = faces.maxByOrNull { it.bounds.width() * it.bounds.height() }

            if (face == null) {
                stableSince = 0L
                lastFace = null
                val now = System.currentTimeMillis()
                if (faceMissingSince == 0L) faceMissingSince = now
                if (now - faceMissingSince >= MAX_FACE_MISSING_MS) {
                    enrollmentSamples.clear()
                    lastSampleAt = 0L
                }
                runOnUiThread {
                    landmarkOverlay.clear()
                    setGuide(Color.YELLOW, "Place your face inside the oval", "Searching for face…")
                    voiceGuidance.guide("Face in oval", "registration_no_face")
                }
                return
            }
            faceMissingSince = 0L

            val quality = engine.assessQuality(frame, face, strict = true)
            val stable = isStable(face)
            if (!quality.accepted || !stable || !liveness.passed) stableSince = 0L
            else if (stableSince == 0L) stableSince = System.currentTimeMillis()
            lastFace = face

            val sampleCount = enrollmentSamples.size
            val showStandardGuidance = System.currentTimeMillis() >= feedbackUntil
            runOnUiThread {
                landmarkOverlay.show(face.landmarks, frame.width, frame.height)
                if (showStandardGuidance) {
                    val color = when {
                        quality.accepted && stable -> Color.rgb(38, 190, 96)
                        quality.accepted -> Color.rgb(30, 94, 255)
                        else -> Color.YELLOW
                    }
                    val instruction = if (!liveness.passed) {
                        liveness.guidance
                    } else if (sampleCount > 0 && quality.accepted) {
                        "Hold still — capturing quality samples"
                    } else {
                        quality.guidance
                    }
                    val detail = if (sampleCount > 0) {
                        "High-quality sample $sampleCount of $REQUIRED_SAMPLE_COUNT"
                    } else {
                        "YuNet: 5 landmarks • Sharpness ${quality.sharpness.toInt()}"
                    }
                    setGuide(color, instruction, detail)
                    voiceGuidance.guide(
                        instruction,
                        if (liveness.passed) {
                            "registration_quality:$instruction"
                        } else {
                            "registration_liveness:${liveness.guidance}"
                        }
                    )
                }
            }

            val now = System.currentTimeMillis()
            if (
                quality.accepted &&
                stable &&
                liveness.passed &&
                now - stableSince >= LOCK_DURATION_MS &&
                now - lastSampleAt >= SAMPLE_INTERVAL_MS
            ) {
                captureEnrollmentObservation(frame, face, now)
            }
        } catch (error: Exception) {
            Log.e("CameraCapture", "YuNet/SFace frame processing failed", error)
            runOnUiThread {
                tvClass.text = "Face scanner unavailable — please try again"
            }
        } finally {
            frameToRecycle?.recycle()
            imageProxy.close()
        }
    }

    private fun isStable(face: YuNetFace): Boolean {
        val previous = lastFace ?: return false
        val tolerance = face.bounds.width() * 0.035f
        return abs(face.bounds.centerX() - previous.bounds.centerX()) < tolerance &&
            abs(face.bounds.centerY() - previous.bounds.centerY()) < tolerance
    }

    private fun captureEnrollmentObservation(frame: Bitmap, face: YuNetFace, capturedAt: Long) {
        val observation = engine.embedding(frame, face)
        val minimumSimilarity = enrollmentSamples.minOfOrNull {
            YuNetSFaceEngine.cosineSimilarity(it, observation)
        }

        if (minimumSimilarity != null && minimumSimilarity < SAMPLE_CONSISTENCY_THRESHOLD) {
            // Reject the inconsistent set. Keep the current high-quality observation as
            // sample one of a fresh set so the user can recover without leaving the screen.
            enrollmentSamples.clear()
            enrollmentSamples += observation
            lastSampleAt = capturedAt
            stableSince = capturedAt
            feedbackUntil = capturedAt + CONSISTENCY_WARNING_MS
            runOnUiThread {
                faceGuide.performHapticFeedback(HapticFeedbackConstants.REJECT)
                setGuide(
                    Color.RED,
                    "Samples did not agree — keep the same face still",
                    "Capture restarted • 1 of $REQUIRED_SAMPLE_COUNT"
                )
                voiceGuidance.announce("Samples differ. Try again.", "registration_samples_differ")
            }
            return
        }

        enrollmentSamples += observation
        lastSampleAt = capturedAt
        val capturedCount = enrollmentSamples.size

        if (capturedCount < REQUIRED_SAMPLE_COUNT) {
            runOnUiThread {
                faceGuide.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                setGuide(
                    Color.rgb(38, 190, 96),
                    "Hold still — capturing quality samples",
                    "High-quality sample $capturedCount of $REQUIRED_SAMPLE_COUNT"
                )
            }
            return
        }

        val averagedEmbedding = averageAndNormalize(enrollmentSamples)
        completed = true
        runOnUiThread {
            faceGuide.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            setGuide(
                Color.GREEN,
                "Face captured automatically",
                "$REQUIRED_SAMPLE_COUNT consistent SFace samples combined"
            )
            voiceGuidance.announceThen(
                message = "Face saved.",
                key = "registration_success",
                timeoutMs = 1_200L
            ) {
                val reply = intent.putExtra(EXTRA_FACE_EMBEDDING, averagedEmbedding)
                setResult(RESULT_OK, reply)
                finish()
            }
        }
    }

    private fun averageAndNormalize(samples: List<FloatArray>): FloatArray {
        require(samples.size == REQUIRED_SAMPLE_COUNT) {
            "Exactly $REQUIRED_SAMPLE_COUNT enrollment samples are required"
        }
        val average = FloatArray(YuNetSFaceEngine.SFACE_DIMENSIONS)
        samples.forEach { sample ->
            require(sample.size == average.size) { "Invalid SFace observation size" }
            for (index in average.indices) average[index] += sample[index]
        }
        for (index in average.indices) average[index] /= samples.size.toFloat()
        return YuNetSFaceEngine.l2Normalize(average)
    }

    private fun setGuide(color: Int, instruction: String, detail: String) {
        faceGuide.background.setTint(color)
        tvStep.text = instruction
        tvClass.text = detail
    }

    private fun imageProxyToBitmapUpright(imageProxy: ImageProxy): Bitmap {
        val nv21 = yuv420ToNv21(imageProxy)
        val yuv = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val stream = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 95, stream)
        val bytes = stream.toByteArray()
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
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
        val chromaHeight = image.height / 2
        val chromaWidth = image.width / 2
        val uRow = ByteArray(image.planes[1].rowStride)
        val vRow = ByteArray(image.planes[2].rowStride)
        for (row in 0 until chromaHeight) {
            val uLength = minOf(uBuffer.remaining(), image.planes[1].rowStride)
            val vLength = minOf(vBuffer.remaining(), image.planes[2].rowStride)
            uBuffer.get(uRow, 0, uLength)
            vBuffer.get(vRow, 0, vLength)
            for (column in 0 until chromaWidth) {
                val uIndex = minOf(column * image.planes[1].pixelStride, uLength - 1)
                val vIndex = minOf(column * image.planes[2].pixelStride, vLength - 1)
                if (offset + 1 < output.size) {
                    output[offset++] = vRow[vIndex]
                    output[offset++] = uRow[uIndex]
                }
            }
        }
        return output
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST_CODE) return
        if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.CAMERA)) {
            com.example.selfieAttendance.utility.PermissionUtils.showSettingsDialog(
                this,
                "Camera permission is required to register a face. Please enable it in app settings."
            ) { finish() }
        } else {
            Toast.makeText(this, "Camera permission is required for registration", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraExecutor.shutdown()
        engine.close()
        livenessVerifier.close()
        voiceGuidance.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FACE_EMBEDDING = "face_embedding_sface"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1002
        private const val LOCK_DURATION_MS = 700L
        private const val REQUIRED_SAMPLE_COUNT = 3
        private const val SAMPLE_INTERVAL_MS = 350L
        private const val MAX_FACE_MISSING_MS = 600L
        private const val CONSISTENCY_WARNING_MS = 1_000L
        private const val SAMPLE_CONSISTENCY_THRESHOLD = 0.55f
    }
}
