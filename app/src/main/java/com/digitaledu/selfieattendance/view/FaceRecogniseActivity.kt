package com.digitaledu.selfieattendance.view

import android.Manifest
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
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.Class
import com.digitaledu.selfieattendance.ml.ActiveLivenessVerifier
import com.digitaledu.selfieattendance.ml.YuNetFace
import com.digitaledu.selfieattendance.ml.YuNetSFaceEngine
import com.digitaledu.selfieattendance.utility.VoiceGuidance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class FaceRecogniseActivity : AppCompatActivity() {
    private lateinit var viewFinder: PreviewView
    private lateinit var landmarkOverlay: FaceLandmarkOverlay
    private lateinit var tvMatchStatus: TextView
    private lateinit var tvUserName: TextView
    private lateinit var tvUserRole: TextView
    private lateinit var tvExtraInfo: TextView
    private lateinit var faceGuide: View
    private lateinit var tvScreenHint: TextView
    private lateinit var tvBottomHint: TextView
    private lateinit var tvSelectUserType: TextView
    private lateinit var tvSelectClass: TextView
    private lateinit var layoutClassSelector: View
    private lateinit var engine: YuNetSFaceEngine
    private lateinit var livenessVerifier: ActiveLivenessVerifier
    private lateinit var voiceGuidance: VoiceGuidance
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null

    data class CachedUser(
        val id: String,
        val name: String,
        val role: String,
        val classId: String?,
        val embedding: FloatArray
    )

    private var classList = listOf<Class>()
    private val classNames = mutableListOf("All Classes")
    @Volatile private var cachedUsersToMatch = listOf<CachedUser>()
    @Volatile private var isVerifying = false
    @Volatile private var scanningEnabled = false
    private var stableSince = 0L
    private var lastFace: YuNetFace? = null
    private var lastProcessTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_recognise)
        viewFinder = findViewById(R.id.viewFinder)
        landmarkOverlay = findViewById(R.id.landmarkOverlay)
        faceGuide = findViewById(R.id.faceGuide)
        tvMatchStatus = findViewById(R.id.tvMatchStatus)
        tvUserName = findViewById(R.id.tvUserName)
        tvUserRole = findViewById(R.id.tvUserRole)
        tvExtraInfo = findViewById(R.id.tvExtraInfo)
        tvScreenHint = findViewById(R.id.tvScreenHint)
        tvBottomHint = findViewById(R.id.tvBottomHint)
        tvSelectUserType = findViewById(R.id.tvSelectUserType)
        tvSelectClass = findViewById(R.id.tvSelectClass)
        layoutClassSelector = findViewById(R.id.layoutClassSelector)

        engine = YuNetSFaceEngine(applicationContext)
        livenessVerifier = ActiveLivenessVerifier()
        voiceGuidance = VoiceGuidance(applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setPausedUi()
        voiceGuidance.guide(
            "Select user type",
            "recognition_select_type"
        )
        setupSelectors()
        loadClasses()
        if (cameraPermissionGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis = analysis
            analysis.setAnalyzer(cameraExecutor, ::processFrame)
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (!scanningEnabled || isVerifying) {
            imageProxy.close()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < FRAME_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastProcessTime = now

        var frameToRecycle: Bitmap? = null
        try {
            val brightness = averageBrightness(imageProxy)
            val upright = imageProxyToBitmapUpright(imageProxy)
            val frame = mirrorBitmap(upright)
            frameToRecycle = frame
            upright.recycle()
            val liveness = livenessVerifier.update(frame)
            val face = engine.detect(frame).maxByOrNull { it.bounds.width() * it.bounds.height() }

            if (face == null) {
                resetTracking()
                runOnUiThread {
                    landmarkOverlay.clear()
                    faceGuide.background.setTint(Color.YELLOW)
                    tvMatchStatus.text = "Face Not Detected"
                    tvScreenHint.text = "Place your face inside the oval"
                    tvBottomHint.text = if (brightness < 45) "Improve lighting" else "YuNet is scanning"
                    voiceGuidance.guide(
                        "Place your face inside the oval",
                        "recognition_no_face"
                    )
                }
                return
            }

            val quality = engine.assessQuality(frame, face, strict = false)
            val stable = isStable(face)
            if (!quality.accepted || !stable || !liveness.passed) stableSince = 0L
            else if (stableSince == 0L) stableSince = now
            lastFace = face
            runOnUiThread {
                landmarkOverlay.show(face.landmarks, frame.width, frame.height)
                faceGuide.background.setTint(
                    when {
                        liveness.passed && quality.accepted && stable -> Color.rgb(38, 190, 96)
                        quality.accepted -> Color.rgb(30, 94, 255)
                        else -> Color.YELLOW
                    }
                )
                tvMatchStatus.text = if (quality.accepted) "Face Detected" else "Adjust Position"
                tvScreenHint.text = if (liveness.passed) quality.guidance else liveness.guidance
                voiceGuidance.guide(
                    if (liveness.passed) quality.guidance else liveness.guidance,
                    if (liveness.passed) {
                        "recognition_quality:${quality.guidance}"
                    } else {
                        "recognition_liveness:${liveness.guidance}"
                    }
                )
                tvBottomHint.text = "5 landmarks • ${(face.confidence * 100).toInt()}% detection"
                tvExtraInfo.text = if (brightness < 45) "Low light — improve lighting" else ""
            }

            if (quality.accepted && stable && liveness.passed && now - stableSince >= LOCK_DURATION_MS) {
                isVerifying = true
                val embedding = engine.embedding(frame, face)
                runOnUiThread {
                    faceGuide.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    verifyFace(embedding)
                }
            }
        } catch (error: Exception) {
            Log.e("FaceRecognise", "YuNet/SFace frame processing failed", error)
            runOnUiThread {
                tvMatchStatus.text = "Recognition unavailable"
                tvExtraInfo.text = "Please reopen the scanner"
                voiceGuidance.announce(
                    "Scanner unavailable.",
                    "recognition_unavailable"
                )
                resetAfterDelay()
            }
        } finally {
            frameToRecycle?.recycle()
            imageProxy.close()
        }
    }

    private fun isStable(face: YuNetFace): Boolean {
        val previous = lastFace ?: return false
        val tolerance = face.bounds.width() * 0.045f
        return abs(face.bounds.centerX() - previous.bounds.centerX()) < tolerance &&
            abs(face.bounds.centerY() - previous.bounds.centerY()) < tolerance
    }

    private fun verifyFace(faceEmbedding: FloatArray) {
        val gallery = cachedUsersToMatch
        lifecycleScope.launch(Dispatchers.Default) {
            val match = gallery.asSequence()
                .map { it to YuNetSFaceEngine.cosineSimilarity(it.embedding, faceEmbedding) }
                .maxByOrNull { it.second }
            withContext(Dispatchers.Main) {
                if (match == null || match.second < com.digitaledu.selfieattendance.ml.FaceDetectionConfig.cosineThreshold) {
                    showUnrecognized(match?.second)
                    return@withContext
                }
                val user = match.first
                val classInfo = if (user.role == "Student") {
                    val name = classList.firstOrNull { it.classId == user.classId }?.classShortName
                    "Class: ${name ?: user.classId ?: "--"}"
                } else ""
                showMatched(user, classInfo, match.second)
            }
        }
    }

    private fun showMatched(user: CachedUser, extra: String, similarity: Float) {
        faceGuide.background.setTint(Color.GREEN)
        tvMatchStatus.text = "Face Matched"
        tvScreenHint.text = "Identity verified"
        tvBottomHint.text = "SFace similarity ${(similarity * 100).toInt()}%"
        tvUserName.text = user.name
        tvUserRole.text = user.role
        tvExtraInfo.text = extra
        val spokenName = VoiceGuidance.speakableName(user.name)
        voiceGuidance.announce(
            "$spokenName verified.",
            "recognition_success:${user.id}"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Face Matched")
            .setMessage(buildString {
                append("Name: ${user.name}\nRole: ${user.role}")
                if (extra.isNotEmpty()) append("\n$extra")
            })
            .setCancelable(false)
            .setPositiveButton("Continue") { dialog, _ ->
                dialog.dismiss()
                resetScanner()
            }
            .show()
    }

    private fun showUnrecognized(similarity: Float?) {
        faceGuide.background.setTint(Color.RED)
        tvMatchStatus.text = "Face Not Recognized"
        tvScreenHint.text = "No matching SFace template"
        tvBottomHint.text = similarity?.let {
            "Best face match ${(it * 100).toInt()}%"
        } ?: "No registered faces found for this selection"
        tvUserName.text = ""
        tvUserRole.text = ""
        tvExtraInfo.text = ""
        if (cachedUsersToMatch.isEmpty()) {
            voiceGuidance.announce(
                "No registered faces.",
                "recognition_no_registered_faces"
            )
        } else {
            voiceGuidance.announce(
                "No match. Try again.",
                "recognition_not_matched"
            )
        }
        resetAfterDelay()
    }

    private fun resetAfterDelay() {
        lifecycleScope.launch {
            delay(1_800)
            resetScanner()
        }
    }

    private fun resetScanner() {
        stableSince = 0L
        lastFace = null
        isVerifying = false
        livenessVerifier.reset()
        voiceGuidance.resetGuidance()
        landmarkOverlay.clear()
        faceGuide.background.setTint(Color.YELLOW)
        tvMatchStatus.text = "Face Not Detected"
        tvScreenHint.text = "Place your face inside the oval"
        tvBottomHint.text = "YuNet is scanning"
        tvUserName.text = ""
        tvUserRole.text = ""
        tvExtraInfo.text = ""
    }

    private fun resetTracking() {
        stableSince = 0L
        lastFace = null
    }

    private fun setupSelectors() {
        val types = listOf("Students", "Teachers")
        tvSelectUserType.setOnClickListener { anchor ->
            ListPopupWindow(this).apply {
                anchorView = anchor
                setAdapter(ArrayAdapter(this@FaceRecogniseActivity, android.R.layout.simple_list_item_1, types))
                width = (200 * resources.displayMetrics.density).toInt()
                height = ListPopupWindow.WRAP_CONTENT
                setOnItemClickListener { _, _, position, _ ->
                    tvSelectUserType.text = types[position]
                    scanningEnabled = true
                    layoutClassSelector.visibility = if (types[position] == "Teachers") View.GONE else View.VISIBLE
                    resetScanner()
                    voiceGuidance.guide(
                        "${types[position]} selected.",
                        "recognition_type:${types[position]}"
                    )
                    updateMatchingCache()
                    dismiss()
                }
                show()
            }
        }
        tvSelectClass.setOnClickListener { anchor ->
            ListPopupWindow(this).apply {
                anchorView = anchor
                setAdapter(ArrayAdapter(this@FaceRecogniseActivity, android.R.layout.simple_list_item_1, classNames))
                width = (300 * resources.displayMetrics.density).toInt()
                horizontalOffset = -(width - anchor.width)
                height = (300 * resources.displayMetrics.density).toInt()
                setOnItemClickListener { _, _, position, _ ->
                    tvSelectClass.text = classNames[position]
                    updateMatchingCache()
                    voiceGuidance.announce(
                        "Class selected.",
                        "recognition_class:${classNames[position]}"
                    )
                    dismiss()
                }
                show()
            }
        }
    }

    private fun loadClasses() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@FaceRecogniseActivity)
                classList = db.classDao().getAllClasses()
                withContext(Dispatchers.Main) {
                    classNames.clear()
                    classNames += "All Classes"
                    classNames += classList.map { it.classShortName }
                }
            } catch (error: Exception) {
                android.util.Log.e("FaceRecognise", "Unable to load classes", error)
            }
        }
    }

    private fun updateMatchingCache() {
        val type = tvSelectUserType.text.toString()
        if (type == SELECT_USER_TYPE) {
            scanningEnabled = false
            cachedUsersToMatch = emptyList()
            return
        }
        scanningEnabled = false
        val selectedClass = tvSelectClass.text.toString()
        val classId = if (selectedClass == "All Classes" || type == "Teachers") null
        else classList.firstOrNull { it.classShortName == selectedClass }?.classId
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@FaceRecogniseActivity)
                val gallery = mutableListOf<CachedUser>()
                if (type == "Teachers") {
                    db.teachersDao().getAllTeachers().forEach { teacher ->
                        parseEmbedding(teacher.embedding)?.let {
                            gallery += CachedUser(teacher.staffId, teacher.staffName, "Teacher", null, it)
                        }
                    }
                } else {
                    val students = if (classId == null) db.studentsDao().getAllStudents()
                    else db.studentsDao().getStudentsByClass(classId)
                    students.forEach { student ->
                        parseEmbedding(student.embedding)?.let {
                            gallery += CachedUser(student.studentId, student.studentName, "Student", student.classId, it)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    cachedUsersToMatch = gallery
                    if (gallery.isEmpty()) {
                        scanningEnabled = false
                        isVerifying = false
                        livenessVerifier.reset()
                        voiceGuidance.resetGuidance()
                        landmarkOverlay.clear()
                        tvMatchStatus.text = "No Registered Faces"
                        tvScreenHint.text = "Choose another user type or class"
                        tvBottomHint.text = "No registered faces found for this selection"
                        voiceGuidance.guide(
                            "No registered faces",
                            "recognition_cache_empty:${type}:${selectedClass}"
                        )
                    } else {
                        scanningEnabled = true
                        resetScanner()
                        voiceGuidance.guide(
                            "Ready",
                            "recognition_cache_ready:${type}:${selectedClass}"
                        )
                    }
                    android.util.Log.d("FaceRecognise", "SFace gallery size=${gallery.size}")
                }
            } catch (error: Exception) {
                android.util.Log.e("FaceRecognise", "Unable to build gallery", error)
                withContext(Dispatchers.Main) {
                    scanningEnabled = false
                    voiceGuidance.announce(
                        "Load failed. Try again.",
                        "recognition_cache_error"
                    )
                }
            }
        }
    }

    private fun parseEmbedding(value: String?): FloatArray? {
        if (value.isNullOrBlank()) return null
        val parsed = value.split(",").mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
        return parsed.takeIf { it.size == YuNetSFaceEngine.SFACE_DIMENSIONS }
            ?.let(YuNetSFaceEngine::l2Normalize)
    }

    private fun setPausedUi() {
        tvScreenHint.text = "Select a user type to begin"
        tvBottomHint.text = "Recognition is paused"
        tvMatchStatus.text = "Select User Type"
        faceGuide.background.setTint(Color.YELLOW)
    }

    private fun cameraPermissionGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun averageBrightness(imageProxy: ImageProxy): Long {
        val buffer = imageProxy.planes[0].buffer.duplicate()
        var sum = 0L
        var samples = 0
        while (buffer.hasRemaining()) {
            sum += buffer.get().toInt() and 0xFF
            samples++
        }
        return if (samples == 0) 0 else sum / samples
    }

    private fun imageProxyToBitmapUpright(imageProxy: ImageProxy): Bitmap {
        val nv21 = yuv420ToNv21(imageProxy)
        val yuv = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val stream = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 95, stream)
        val raw = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            com.digitaledu.selfieattendance.utility.PermissionUtils.showSettingsDialog(
                this,
                "Camera permission is required for face recognition. Please enable it in app settings."
            ) { finish() }
        } else {
            Toast.makeText(this, "Camera permission is required for recognition", Toast.LENGTH_LONG).show()
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
        private const val SELECT_USER_TYPE = "Select User Type"
        private const val CAMERA_REQUEST = 101
        private const val FRAME_INTERVAL_MS = 160L
        private const val LOCK_DURATION_MS = 550L
    }
}
