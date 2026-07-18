package com.example.login.view

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.login.R
import com.example.login.db.dao.AppDatabase
import com.example.login.utility.FaceNetHelper
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class FaceVerificationActivity : ComponentActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var faceGuide: View
    private lateinit var tvBlinkWarning: TextView
    private lateinit var tvStudentName: TextView
    private lateinit var tvAttempts: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var btnCancel: Button

    private lateinit var db: AppDatabase
    private lateinit var faceNet: FaceNetHelper
    private lateinit var cameraExecutor: ExecutorService

    private var studentId = ""
    private var studentName = ""
    private var targetEmbedding: FloatArray? = null

    private var isVerifying = false
    private var faceStableStart = 0L
    private var lastProcessTime = 0L

    private var attemptCount = 0
    private val MAX_ATTEMPTS = 3
    private val DIST_THRESHOLD = 0.60f
    private val CROP_SCALE = 1.1f
    private val MIRROR_FRONT = true

    // Blink detection
    private var prevFace: com.google.mlkit.vision.face.Face? = null
    private var lastLeftProb = -1f
    private var lastRightProb = -1f
    private var blinkDetected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_verification)

        viewFinder = findViewById(R.id.viewFinder)
        faceGuide = findViewById(R.id.faceGuide)
        tvBlinkWarning = findViewById(R.id.tvBlinkWarning)
        tvStudentName = findViewById(R.id.tvStudentName)
        tvAttempts = findViewById(R.id.tvAttempts)
        tvInstruction = findViewById(R.id.tvInstruction)
        btnCancel = findViewById(R.id.btnCancel)

        studentId = intent.getStringExtra("STUDENT_ID") ?: ""
        studentName = intent.getStringExtra("STUDENT_NAME") ?: ""

        tvStudentName.text = studentName
        updateAttemptUI()

        db = AppDatabase.getDatabase(this)
        faceNet = FaceNetHelper(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        loadTargetEmbedding()
    }

    private fun loadTargetEmbedding() {
        lifecycleScope.launch(Dispatchers.IO) {
            val student = db.studentsDao().getStudentById(studentId)
            val embStr = student?.embedding

            if (embStr.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@FaceVerificationActivity,
                        "No face embedding registered for this student",
                        Toast.LENGTH_LONG
                    ).show()
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
                return@launch
            }

            val parsed = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
            if (parsed.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@FaceVerificationActivity,
                        "Invalid face embedding format",
                        Toast.LENGTH_LONG
                    ).show()
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
                return@launch
            }

            targetEmbedding = parsed

            withContext(Dispatchers.Main) {
                if (allPermissionsGranted()) {
                    startCamera()
                } else {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
                }
            }
        }
    }

    private fun updateAttemptUI() {
        tvAttempts.text = "Attempt ${attemptCount + 1} of $MAX_ATTEMPTS"
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required for face verification", Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e("FACE_VERIFY", "Use case binding failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build()
        )
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 130) {
            imageProxy.close()
            return
        }
        lastProcessTime = now

        try {
            val bmp = imageProxyToBitmapUpright(imageProxy)
            val displayBmp = if (MIRROR_FRONT) mirrorBitmap(bmp) else bmp

            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(displayBmp, 0)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        faceGuide.background?.setTint(Color.RED)
                        faceStableStart = 0L
                    } else {
                        val face = faces[0]
                        if (!isLiveFace(face, prevFace)) {
                            faceGuide.background?.setTint(Color.RED)
                            faceStableStart = 0L
                            prevFace = face
                            return@addOnSuccessListener
                        }

                        val rect = face.boundingBox
                        val isCentered = kotlin.math.abs(rect.centerX() - displayBmp.width / 2) < rect.width() * 0.3 &&
                                kotlin.math.abs(rect.centerY() - displayBmp.height / 2) < rect.height() * 0.3

                        if (isCentered) {
                            if (faceStableStart == 0L) faceStableStart = System.currentTimeMillis()
                            val elapsed = System.currentTimeMillis() - faceStableStart
                            faceGuide.background?.setTint(if (elapsed >= 300) Color.GREEN else Color.WHITE)

                            if (elapsed >= 1000 && !isVerifying) {
                                isVerifying = true

                                lifecycleScope.launch(Dispatchers.Default) {
                                    val cropped = cropWithScale(displayBmp, face.boundingBox, CROP_SCALE)
                                    val embedding = faceNet.getFaceEmbedding(cropped)

                                    withContext(Dispatchers.Main) {
                                        verifyEmbedding(embedding)
                                        faceStableStart = 0L
                                        isVerifying = false
                                    }
                                }
                            }
                        } else {
                            faceGuide.background?.setTint(Color.RED)
                            faceStableStart = 0L
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }

        } catch (e: Exception) {
            imageProxy.close()
        }
    }

    private fun isLiveFace(
        face: com.google.mlkit.vision.face.Face,
        prevFace: com.google.mlkit.vision.face.Face?
    ): Boolean {
        val left = face.leftEyeOpenProbability ?: -1f
        val right = face.rightEyeOpenProbability ?: -1f

        if (left >= 0 && right >= 0) {
            val eyesWereOpen = lastLeftProb > 0.6f && lastRightProb > 0.6f
            val eyesNowClosed = left < 0.3f && right < 0.3f

            if (eyesWereOpen && eyesNowClosed) {
                blinkDetected = true
            }

            lastLeftProb = left
            lastRightProb = right
        }

        if (!blinkDetected) {
            return false
        }

        if (prevFace != null) {
            val moveX = kotlin.math.abs(face.boundingBox.centerX() - prevFace.boundingBox.centerX())
            val moveY = kotlin.math.abs(face.boundingBox.centerY() - prevFace.boundingBox.centerY())
            if (moveX > 80 || moveY > 80) {
                blinkDetected = false
                lastLeftProb = -1f
                lastRightProb = -1f
                return false
            }
        }
        return true
    }

    private fun verifyEmbedding(faceEmbedding: FloatArray) {
        val target = targetEmbedding ?: return
        val dist = faceNet.calculateDistance(target, faceEmbedding)

        Log.d("FACE_VERIFY", "Matching distance: $dist")

        if (dist < DIST_THRESHOLD) {
            Toast.makeText(this, "Face Match Success!", Toast.LENGTH_SHORT).show()
            val resultIntent = Intent().apply {
                putExtra("VERIFIED_STUDENT_ID", studentId)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } else {
            attemptCount++
            blinkDetected = false // reset liveness for next try
            lastLeftProb = -1f
            lastRightProb = -1f
            prevFace = null
            faceStableStart = 0L

            if (attemptCount >= MAX_ATTEMPTS) {
                Toast.makeText(this, "Face Match Failed. 3 attempts exceeded.", Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_CANCELED)
                finish()
            } else {
                Toast.makeText(this, "Face not matched. Try again.", Toast.LENGTH_SHORT).show()
                updateAttemptUI()
            }
        }
    }

    private fun imageProxyToBitmapUpright(imageProxy: ImageProxy): Bitmap {
        val nv21 = yuv420ToNv21(imageProxy)
        val yuv = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val bytes = out.toByteArray()
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
        if (rotation == 0f) return raw

        val m = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray {
        val y = imageProxy.planes[0].buffer
        val u = imageProxy.planes[1].buffer
        val v = imageProxy.planes[2].buffer

        val ySize = y.remaining()
        val uSize = u.remaining()
        val vSize = v.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        y.get(nv21, 0, ySize)
        v.get(nv21, ySize, vSize)
        u.get(nv21, ySize + vSize, uSize)

        return nv21
    }

    private fun mirrorBitmap(src: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun cropWithScale(bmp: Bitmap, rect: Rect, scale: Float): Bitmap {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val halfW = (rect.width() * scale / 2).toInt()
        val halfH = (rect.height() * scale / 2).toInt()

        val x = max(0, cx - halfW)
        val y = max(0, cy - halfH)
        val w = min(bmp.width - x, halfW * 2)
        val h = min(bmp.height - y, halfH * 2)

        return Bitmap.createBitmap(bmp, x, y, w, h)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
