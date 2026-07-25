package com.example.login.view

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.login.R
import com.example.login.db.dao.AppDatabase
import com.example.login.utility.FaceNetHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.view.View
import com.example.login.db.entity.Class
import com.example.login.db.entity.Student
import com.example.login.db.entity.Teacher

class FaceRecogniseActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvMatchStatus: TextView
    private lateinit var tvUserName: TextView
    private lateinit var tvUserRole: TextView
    private lateinit var tvExtraInfo: TextView
    private lateinit var faceGuide: android.view.View
    private lateinit var tvScreenHint: TextView
    private lateinit var tvBottomHint: TextView

    private lateinit var tvSelectUserType: TextView
    private lateinit var tvSelectClass: TextView
    private lateinit var layoutClassSelector: View

    data class CachedUser(
        val id: String,
        val name: String,
        val role: String,
        val classId: String?,
        val embedding: FloatArray
    )

    private var classList = listOf<Class>()
    @Volatile
    private var cachedUsersToMatch = listOf<CachedUser>()

    private lateinit var faceNet: FaceNetHelper
    private lateinit var cameraExecutor: java.util.concurrent.ExecutorService

    private val distThreshold: Float get() = com.example.login.utility.ThresholdManager.getThreshold(this)
    private val CROP_SCALE = 1.1f

    private var lastProcessTime = 0L
    private var faceStableStart = 0L
    private var isVerifying = false

    // LIVENESS VARIABLES (copied from StudentScanFragment)
    private var prevFace: Face? = null
    private var lastLeftProb = -1f
    private var lastRightProb = -1f
    private var blinkDetected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_recognise)

        viewFinder = findViewById(R.id.viewFinder)
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

        // Set initial pause instructions
        tvScreenHint.text = "Please select User Type from the dropdown"
        tvBottomHint.text = "Selection required to start recognition"
        tvMatchStatus.text = "Scanning Paused: Select User Type"

        setupSpinners()
        loadInitialFiltersAndCache()

        faceNet = FaceNetHelper(this)
        cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        android.util.Log.d("PERM_DEBUG", "onRequestPermissionsResult: code=$requestCode, size=${grantResults.size}")
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("PERM_DEBUG", "Permission GRANTED")
                startCamera()
            } else {
                val showRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
                android.util.Log.d("PERM_DEBUG", "Permission DENIED. showRationale=$showRationale")
                if (!showRationale) {
                    android.util.Log.d("PERM_DEBUG", "Showing settings dialog")
                    com.example.login.utility.PermissionUtils.showSettingsDialog(this, "Camera permission is required for face recognition. Please enable it in the app settings.") {
                        finish()
                    }
                } else {
                    android.util.Log.d("PERM_DEBUG", "Showing standard rationale toast")
                    Toast.makeText(this, "Camera permission is required for face recognition", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // -----------------------------------------------------------
    // CAMERA SETUP
    // -----------------------------------------------------------
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

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )

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

    // -----------------------------------------------------------
    // FRAME PROCESSING
    // -----------------------------------------------------------
    private fun processFrame(imageProxy: ImageProxy) {
        if (tvSelectUserType.text.toString() == "Select User Type") {
            runOnUiThread {
                tvScreenHint.text = "Please select User Type from the dropdown"
                tvBottomHint.text = "Selection required to start recognition"
                tvMatchStatus.text = "Scanning Paused: Select User Type"
                faceGuide.background.setTint(Color.RED)
            }
            imageProxy.close()
            return
        } else {
            runOnUiThread {
                if (tvScreenHint.text == "Please select User Type from the dropdown") {
                    tvScreenHint.text = "Align your face inside the circle"
                }
                if (tvBottomHint.text == "Selection required to start recognition") {
                    tvBottomHint.text = "Good lighting improves accuracy"
                }
                if (tvMatchStatus.text == "Scanning Paused: Select User Type") {
                    tvMatchStatus.text = "Face Not Detected"
                }
            }
        }
        if (isVerifying) {
            imageProxy.close()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 140) {
            imageProxy.close()
            return
        }
        lastProcessTime = now

        try {


            // ------- BRIGHTNESS CHECK (same as Teacher fragment) -------
            val yBuffer = imageProxy.planes[0].buffer.duplicate()
            var sum = 0L
            val total = yBuffer.remaining()

            while (yBuffer.hasRemaining()) {
                sum += (yBuffer.get().toInt() and 0xFF)
            }

            val brightness = if (total > 0) sum / total else 0L

            runOnUiThread {
                if (brightness < 40) {
                    tvExtraInfo.text = "Low Light! Improve lighting."
                } else {
                    tvExtraInfo.text = ""
                }
            }


            val bmp = imageProxyToBitmapUpright(imageProxy)
            val mirrored = mirrorBitmap(bmp)
            val image = InputImage.fromBitmap(mirrored, 0)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        faceGuide.background.setTint(Color.RED)
                        faceStableStart = 0L
                        prevFace = null

                    } else {
                        val face = faces[0]

                        // LIVENESS CHECK (same as StudentScanFragment)
                        if (!isLiveFace(face, prevFace)) {
                            faceGuide.background.setTint(Color.RED)
                            faceStableStart = 0L
                            imageProxy.close()
                            prevFace = face
                            return@addOnSuccessListener
                        }

                        val rect = face.boundingBox

                        val centered = kotlin.math.abs(rect.centerX() - mirrored.width / 2) < rect.width() * 0.3
                                && kotlin.math.abs(rect.centerY() - mirrored.height / 2) < rect.height() * 0.3

                        if (centered) {
                            if (faceStableStart == 0L) faceStableStart = System.currentTimeMillis()

                            val elapsed = System.currentTimeMillis() - faceStableStart
                            faceGuide.background.setTint(if (elapsed >= 300) Color.GREEN else Color.YELLOW)

                            if (elapsed >= 1000 && !isVerifying) {
                                isVerifying = true

                                lifecycleScope.launch(Dispatchers.Default) {
                                    val crop = cropWithScale(mirrored, face.boundingBox, CROP_SCALE)
                                    val emb = faceNet.getFaceEmbedding(crop)

                                    withContext(Dispatchers.Main) {
                                        verifyFace(emb)
                                        faceStableStart = 0L
                                    }
                                }
                            }
                        } else {
                            faceGuide.background.setTint(Color.RED)
                            faceStableStart = 0L
                        }

                        prevFace = face
                    }
                }
                .addOnCompleteListener { imageProxy.close() }

        } catch (e: Exception) {
            imageProxy.close()
        }
    }

    // -----------------------------------------------------------
    // LIVENESS (copied from StudentScanFragment)
    // -----------------------------------------------------------
    private fun isLiveFace(face: Face, prevFace: Face?): Boolean {
        val left = face.leftEyeOpenProbability ?: -1f
        val right = face.rightEyeOpenProbability ?: -1f

        // ------------ 1) REAL BLINK DETECTION ------------
        if (left >= 0 && right >= 0) {
            val eyesWereOpen = lastLeftProb > 0.5f && lastRightProb > 0.5f
            val eyesNowClosed = left < 0.4f && right < 0.4f

            // detect blink: open → closed
            if (eyesWereOpen && eyesNowClosed) {
                blinkDetected = true
            }

            lastLeftProb = left
            lastRightProb = right
        }

        // require 1 blink before considering liveness
        if (!blinkDetected) {
            return false
        }

        // ------------ 2) MOTION LIVENESS ------------
        if (prevFace != null) {
            val moveX = kotlin.math.abs(face.boundingBox.centerX() - prevFace.boundingBox.centerX())
            val moveY = kotlin.math.abs(face.boundingBox.centerY() - prevFace.boundingBox.centerY())

            if (moveX < 1 && moveY < 1) {
                return false // still → printed photo
            }
        }

        return true
    }

    // -----------------------------------------------------------
    // MATCH LOGIC (real-time DB comparison)
    // -----------------------------------------------------------
    private var classNames = mutableListOf("All Classes")

    private fun setupSpinners() {
        val types = listOf("Students", "Teachers")

        tvSelectUserType.setOnClickListener { anchorView ->
            val listPopupWindow = ListPopupWindow(this)
            listPopupWindow.anchorView = anchorView
            listPopupWindow.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, types))
            
            val width = (200 * resources.displayMetrics.density).toInt()
            listPopupWindow.width = width
            listPopupWindow.height = ListPopupWindow.WRAP_CONTENT
            listPopupWindow.setOnItemClickListener { _, _, position, _ ->
                val selectedType = types[position]
                tvSelectUserType.text = selectedType
                
                tvScreenHint.text = "Align your face inside the circle"
                tvBottomHint.text = "Good lighting improves accuracy"
                tvMatchStatus.text = "Face Not Detected"
                
                if (selectedType == "Teachers") {
                    layoutClassSelector.visibility = View.GONE
                } else {
                    layoutClassSelector.visibility = View.VISIBLE
                }
                
                updateMatchingCache()
                listPopupWindow.dismiss()
            }
            listPopupWindow.show()
            listPopupWindow.listView?.let { listView ->
                listView.divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#E0E0E0"))
                listView.dividerHeight = (1 * resources.displayMetrics.density).toInt()
            }
        }

        tvSelectClass.setOnClickListener { anchorView ->
            val listPopupWindow = ListPopupWindow(this)
            listPopupWindow.anchorView = anchorView
            listPopupWindow.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, classNames))
            
            val width = (300 * resources.displayMetrics.density).toInt()
            listPopupWindow.width = width
            listPopupWindow.horizontalOffset = -(width - anchorView.width)
            
            // Limit height to 300dp (approx 900 pixels on high-density screens)
            listPopupWindow.height = (300 * resources.displayMetrics.density).toInt()
            listPopupWindow.setOnItemClickListener { _, _, position, _ ->
                val selectedClass = classNames[position]
                tvSelectClass.text = selectedClass
                
                updateMatchingCache()
                listPopupWindow.dismiss()
            }
            listPopupWindow.show()
            listPopupWindow.listView?.let { listView ->
                listView.divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#E0E0E0"))
                listView.dividerHeight = (1 * resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun loadInitialFiltersAndCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@FaceRecogniseActivity)
                classList = db.classDao().getAllClasses()
                
                val names = mutableListOf("All Classes")
                names.addAll(classList.map { it.classShortName })

                withContext(Dispatchers.Main) {
                    classNames.clear()
                    classNames.addAll(names)
                }
            } catch (e: Exception) {
                android.util.Log.e("FaceRecognise", "loadInitialFiltersAndCache error", e)
            }
        }
    }

    private fun updateMatchingCache() {
        val selectedType = tvSelectUserType.text.toString()
        val selectedClassShort = tvSelectClass.text.toString()

        if (selectedType == "Select User Type") {
            cachedUsersToMatch = emptyList()
            return
        }

        // Resolve class ID
        val selectedClassId = if (selectedClassShort == "All Classes" || selectedType == "Teachers") null else {
            classList.firstOrNull { it.classShortName == selectedClassShort }?.classId
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@FaceRecogniseActivity)
                val list = mutableListOf<CachedUser>()

                // 1. Load Teachers
                if (selectedType == "Teachers") {
                    val teachers = db.teachersDao().getAllTeachers()
                    for (t in teachers) {
                        val embStr = t.embedding ?: continue
                        val emb = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                        if (emb.size == 512 || emb.size == 128) {
                            list.add(CachedUser(t.staffId, t.staffName, "Teacher", null, emb))
                        }
                    }
                }

                // 2. Load Students
                if (selectedType == "Students") {
                    val students = if (selectedClassId != null) {
                        db.studentsDao().getStudentsByClass(selectedClassId)
                    } else {
                        db.studentsDao().getAllStudents()
                    }

                    for (s in students) {
                        val embStr = s.embedding ?: continue
                        val emb = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                        if (emb.size == 512 || emb.size == 128) {
                            list.add(CachedUser(s.studentId, s.studentName, "Student", s.classId, emb))
                        }
                    }
                }

                cachedUsersToMatch = list
                android.util.Log.d("FaceRecognise", "Cache updated! Match pool size: ${list.size}")
            } catch (e: Exception) {
                android.util.Log.e("FaceRecognise", "updateMatchingCache error", e)
            }
        }
    }

    private fun verifyFace(faceEmbedding: FloatArray) {
        val currentCache = cachedUsersToMatch

        lifecycleScope.launch(Dispatchers.Default) {
            var bestDist = Float.MAX_VALUE
            var bestUser: CachedUser? = null

            for (user in currentCache) {
                val dist = faceNet.calculateDistance(user.embedding, faceEmbedding)
                if (dist < bestDist) {
                    bestDist = dist
                    bestUser = user
                }
            }

            withContext(Dispatchers.Main) {
                if (bestDist >= distThreshold || bestUser == null) {
                    showUnrecognizedResult()
                    return@withContext
                }

                val extraInfo = if (bestUser.role == "Student") {
                    val classShort = classList.firstOrNull { it.classId == bestUser.classId }?.classShortName
                    "Class: ${classShort ?: bestUser.classId ?: "--"}"
                } else {
                    ""
                }

                showResultDialog("Face Matched", bestUser.name, bestUser.role, extraInfo)
            }
        }
    }

    private fun showUnrecognizedResult() {
        tvMatchStatus.text = "Face Not Recognized"
        tvUserName.text = ""
        tvUserRole.text = ""
        tvExtraInfo.text = ""

        // Reset scanning automatically after 2 seconds (no popup dialog)
        tvMatchStatus.postDelayed({
            tvMatchStatus.text = "Face Not Detected"
            tvScreenHint.text = "Align your face inside the circle"
            tvBottomHint.text = "Good lighting improves accuracy"
            blinkDetected = false
            lastLeftProb = -1f
            lastRightProb = -1f
            prevFace = null
            faceStableStart = 0L
            isVerifying = false
            faceGuide.background.setTint(Color.RED)
        }, 2000)
    }

    private fun showResultDialog(title: String, name: String, role: String, extra: String) {
        tvMatchStatus.text = "Face Matched"
        tvUserName.text = name
        tvUserRole.text = role
        tvExtraInfo.text = extra

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle(title)
        
        val message = buildString {
            append("Name: $name\n")
            append("Role: $role\n")
            if (extra.isNotEmpty()) append("$extra\n")
        }
        builder.setMessage(message)
        builder.setCancelable(false)
        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
            
            // Reset state to allow next scan
            blinkDetected = false
            lastLeftProb = -1f
            lastRightProb = -1f
            prevFace = null
            faceStableStart = 0L
            isVerifying = false
            faceGuide.background.setTint(Color.RED)
            
            // Reset text views
            tvMatchStatus.text = "Face Not Detected"
            tvScreenHint.text = "Align your face inside the circle"
            tvBottomHint.text = "Good lighting improves accuracy"
            tvUserName.text = ""
            tvUserRole.text = ""
            tvExtraInfo.text = ""
        }
        
        val dialog = builder.create()
        dialog.show()
    }

    // -----------------------------------------------------------
    // UTILS
    // -----------------------------------------------------------
    private fun toBitmap(imageProxy: ImageProxy): Bitmap {
        val nv21 = yuvToNv21(imageProxy)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun yuvToNv21(image: ImageProxy): ByteArray {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer

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


    private fun imageProxyToBitmapUpright(imageProxy: ImageProxy): Bitmap {
        val nv21 = yuvToNv21(imageProxy)
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


}
