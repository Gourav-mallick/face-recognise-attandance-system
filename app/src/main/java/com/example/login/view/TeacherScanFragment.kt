package com.example.login.view

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.example.login.R
import com.example.login.db.dao.AppDatabase
import com.example.login.ml.ActiveLivenessVerifier
import com.example.login.ml.YuNetFace
import com.example.login.ml.YuNetSFaceEngine
import com.example.login.utility.VoiceGuidance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min


class TeacherScanFragment : Fragment() {

    private var _viewFinder: androidx.camera.view.PreviewView? = null
    private val viewFinder get() = requireNotNull(_viewFinder)
    private var _faceGuide: View? = null
    private val faceGuide get() = requireNotNull(_faceGuide)
    private var _landmarkOverlay: FaceLandmarkOverlay? = null
    private val landmarkOverlay get() = requireNotNull(_landmarkOverlay)
    private var _tvLightWarning: TextView? = null
    private val tvLightWarning get() = requireNotNull(_tvLightWarning)
    private var _tvClassCard: TextView? = null
    private val tvClassCard get() = requireNotNull(_tvClassCard)
    private var _tvStart: TextView? = null
    private val tvStart get() = requireNotNull(_tvStart)
    private var _progress: ProgressBar? = null
    private val progress get() = requireNotNull(_progress)

    private lateinit var faceEngine: YuNetSFaceEngine
    private lateinit var livenessVerifier: ActiveLivenessVerifier
    private lateinit var voiceGuidance: VoiceGuidance
    private var cameraExecutor: ExecutorService? = null
    private var imageAnalysis: ImageAnalysis? = null

    private var faceStableStart = 0L
    private var isVerifying = false
    private var lastProcessTime = 0L
    private var prevFace: YuNetFace? = null


    private var sessionTeacherId: String? = null
    private var sessionTeacherName: String? = null
    private var sessionDialogShown = false
    private var scanningPaused = false


    private val MIRROR_FRONT = true

    private var sessionCreated = false


    private var failCount = 0          // count failed attempts
    private val MAX_FAILS = 3          // set how many tries allowed


    companion object {
        private const val ARG_CLASSID  = "arg_classid"
        fun newInstance(classId: String) = TeacherScanFragment().apply {
            arguments = Bundle().apply { putString(ARG_CLASSID , classId) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?)
            = inflater.inflate(R.layout.fragment_teacher_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _viewFinder = view.findViewById(R.id.viewFinder)
        _faceGuide = view.findViewById(R.id.faceGuide)
        _landmarkOverlay = view.findViewById(R.id.landmarkOverlay)
        _tvLightWarning = view.findViewById(R.id.tvLightWarning)
        _tvClassCard = view.findViewById(R.id.tvClassCard)
        _tvStart = view.findViewById(R.id.tvStart)
        _progress = ProgressBar(requireContext()).apply { visibility = View.GONE }
        (view as ViewGroup).addView(progress)

        tvClassCard.text = "Class-Room : ${arguments?.getString(ARG_CLASSID) ?: "-"}"

        faceEngine = YuNetSFaceEngine(requireContext().applicationContext)
        livenessVerifier = ActiveLivenessVerifier()
        voiceGuidance = VoiceGuidance(requireContext().applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1001)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { ia ->
                    ia.setAnalyzer(cameraExecutor!!) { imageProxy -> processFrame(imageProxy) }
                }
            imageAnalysis = analysis

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (scanningPaused) {
            imageProxy.close()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 130) {
            imageProxy.close(); return
        }
        lastProcessTime = now

        var prepared: Bitmap? = null
        try {
            val yBuffer: ByteBuffer = imageProxy.planes[0].buffer.duplicate()
            var sum = 0L
            val count = yBuffer.remaining()
            while (yBuffer.hasRemaining()) sum += yBuffer.get().toInt() and 0xFF
            val brightness = if (count > 0) sum / count else 0L

            val rotated = imageProxyToBitmapUpright(imageProxy)
            prepared = if (MIRROR_FRONT) mirrorBitmap(rotated) else rotated
            if (prepared !== rotated) rotated.recycle()
            val liveness = livenessVerifier.update(prepared)
            val face = faceEngine.detect(prepared)
                .maxByOrNull { it.bounds.width() * it.bounds.height() }

            if (face == null) {
                faceStableStart = 0L
                prevFace = null
                runOnViewThread {
                    landmarkOverlay.clear()
                    faceGuide.background.setTint(Color.YELLOW)
                    tvLightWarning.visibility = if (brightness < 40) View.VISIBLE else View.GONE
                    tvStart.text = "Awaiting teacher face verification"
                    voiceGuidance.guide(
                        "Face in oval",
                        "teacher_no_face"
                    )
                }
                return
            }

            val quality = faceEngine.assessQuality(prepared, face, strict = false)
            val stable = isStable(face)
            if (!quality.accepted || !stable || !liveness.passed) faceStableStart = 0L
            else if (faceStableStart == 0L) faceStableStart = now
            prevFace = face

            runOnViewThread {
                landmarkOverlay.show(face.landmarks, prepared.width, prepared.height)
                faceGuide.background.setTint(
                    when {
                        liveness.passed && quality.accepted && stable -> Color.GREEN
                        quality.accepted -> Color.rgb(30, 94, 255)
                        else -> Color.YELLOW
                    }
                )
                tvLightWarning.visibility = if (brightness < 40) View.VISIBLE else View.GONE
                tvStart.text = if (liveness.passed) quality.guidance else liveness.guidance
                voiceGuidance.guide(
                    if (liveness.passed) quality.guidance else liveness.guidance,
                    if (liveness.passed) {
                        "teacher_quality:${quality.guidance}"
                    } else {
                        "teacher_liveness:${liveness.guidance}"
                    }
                )
            }

            if (
                quality.accepted &&
                stable &&
                liveness.passed &&
                now - faceStableStart >= 700 &&
                !isVerifying
            ) {
                isVerifying = true
                runOnViewThread { progress.visibility = View.VISIBLE }
                val embedding = faceEngine.embedding(prepared, face)
                recognizeTeacher(embedding)
                faceStableStart = 0L
            }
        } catch (error: Exception) {
            Log.e("TeacherScan", "YuNet/SFace processing failed", error)
            faceStableStart = 0L
            runOnViewThread {
                landmarkOverlay.clear()
                tvStart.text = "Face scanner unavailable — retrying"
            }
        } finally {
            prepared?.recycle()
            imageProxy.close()
        }
    }

    private fun isStable(face: YuNetFace): Boolean {
        val previous = prevFace ?: return false
        val tolerance = face.bounds.width() * 0.045f
        return kotlin.math.abs(face.bounds.centerX() - previous.bounds.centerX()) < tolerance &&
            kotlin.math.abs(face.bounds.centerY() - previous.bounds.centerY()) < tolerance
    }

    private fun runOnViewThread(action: () -> Unit) {
        val host = activity ?: return
        host.runOnUiThread {
            if (_viewFinder != null) action()
        }
    }

    private fun recognizeTeacher(embedding: FloatArray) {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            val teachers = db.teachersDao().getAllTeachers() // has embedding String? field
            if (teachers.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "No registered teachers found", Toast.LENGTH_SHORT).show()
                    voiceGuidance.announce(
                        "No teachers registered.",
                        "no_registered_teachers"
                    )
                    progress.visibility = View.GONE; isVerifying = false
                }
                return@launch
            }

            var bestId: String? = null
            var bestName: String? = null
            var bestSimilarity = -1f

            for (t in teachers) {
                val embStr = t.embedding ?: continue
                val emb = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (emb.size != YuNetSFaceEngine.SFACE_DIMENSIONS) continue
                val similarity = YuNetSFaceEngine.cosineSimilarity(emb, embedding)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestId = t.staffId
                    bestName = t.staffName
                }
            }

            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                isVerifying = false
                livenessVerifier.reset()

                if (bestId != null && bestSimilarity >= YuNetSFaceEngine.COSINE_THRESHOLD) {
                    isVerifying = true

                    prevFace = null
                    faceStableStart = 0L
                    // 🔥 1) Check assigned classes
                    val hasClasses = withContext(Dispatchers.IO) { hasAssignedClasses(bestId!!) }


                    if (!hasClasses) {
                        //  Teacher NOT assigned to any class
                        val message = "$bestName,\nyou are not enroll any class. Please contact authority. and do setup first."

                        AlertDialog.Builder(requireContext())
                            .setTitle("Access Denied")
                            .setMessage(message)
                            .setCancelable(false)
                            .setPositiveButton("OK") { _, _ ->
                                // Clear app state
                                val prefs = requireContext().getSharedPreferences("APP_STATE", Context.MODE_PRIVATE)
                                prefs.edit().remove("CURRENT_SCREEN").apply()

                                // Go back to ClassroomScanFragment (Home Screen)
                                parentFragmentManager.popBackStack()
                                isVerifying = false
                            }
                            .show()
                        voiceGuidance.announce(
                            "No class assigned.",
                            "teacher_no_assigned_class"
                        )

                        return@withContext
                    }

                    // 🔥 CALL NEW FUNCTION HERE
                  //  logTeacherAssignedClasses(bestId!!)

//                    sessionCreated = true
//                    //  Valid teacher recognized
//                    Toast.makeText(requireContext(), "Welcome, $bestName", Toast.LENGTH_LONG).show()
//
//                    //  Wait 5 seconds, then navigate to StudentScanFragment
//                    view?.postDelayed({
//                        (requireActivity() as AttendanceActivity).simulateTeacherScan(bestId!!)
//                    }, 2000)

                    sessionCreated = true
                    sessionTeacherId = bestId
                    sessionTeacherName = bestName

// Prevent repeated dialogs
                    if (!sessionDialogShown) {
                        sessionDialogShown = true
                        scanningPaused = true  // stop analyzer while dialog is open

                        val spokenName = VoiceGuidance.speakableName(bestName!!)
                        voiceGuidance.announce(
                            "$spokenName verified.",
                            "teacher_verified:$bestId"
                        )
                        showStartStudentAttendanceDialog(bestId!!, bestName!!)
                    }

                } else {

                    failCount++

                    if (failCount >= MAX_FAILS) {

                        Toast.makeText(
                            requireContext(),
                            "Face not recognized.\nFace may not be registered or you may not be enrolled in any class.\nPlease contact the authorities.",
                            Toast.LENGTH_LONG
                        ).show();
                        voiceGuidance.announce(
                            "Teacher not recognized.",
                            "teacher_final_failure"
                        )


                        // OPTIONAL: stop scanning for 3 seconds
                        isVerifying = true
                        viewLifecycleOwner.lifecycleScope.launch {
                            delay(3_000)
                            isVerifying = false
                            failCount = 0   // reset so next teacher can try
                        }

                        return@withContext
                    }

                    // Normal fail toast
                    Toast.makeText(
                        requireContext(),
                        "Face not matched. Adjust your face and try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                    voiceGuidance.announce(
                        "No match. Try again.",
                        "teacher_match_failed_$failCount"
                    )
                }

            }
        }
    }


    private suspend fun hasAssignedClasses(teacherId: String): Boolean {
        return try {
            val db = AppDatabase.getDatabase(requireContext())
            val classIds = db.teacherClassMapDao().getClassesForTeacher(teacherId)
            classIds.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }


    // === helpers (same as activity) ===
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
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (upright !== raw) raw.recycle()
        return upright
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
        val cx = rect.centerX(); val cy = rect.centerY()
        val halfW = (rect.width() * scale / 2).toInt()
        val halfH = (rect.height() * scale / 2).toInt()
        val x = max(0, cx - halfW)
        val y = max(0, cy - halfH)
        val w = min(bmp.width - x, halfW * 2)
        val h = min(bmp.height - y, halfH * 2)
        return Bitmap.createBitmap(bmp, x, y, w, h)
    }

    override fun onDestroyView() {
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraExecutor?.shutdown()
        faceEngine.close()
        livenessVerifier.close()
        voiceGuidance.close()
        _viewFinder = null
        _faceGuide = null
        _landmarkOverlay = null
        _tvLightWarning = null
        _tvClassCard = null
        _tvStart = null
        _progress = null
        super.onDestroyView()
        if (!sessionCreated) {
            // Clear saved screen only if no session was started
            val prefs = requireContext().getSharedPreferences("APP_STATE", Context.MODE_PRIVATE)
            prefs.edit().remove("CURRENT_SCREEN").apply()
        }
    }

    override fun onResume() {
        super.onResume()

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (sessionCreated) {
                    Toast.makeText(
                        requireContext(),
                        "Cannot go back after session started",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // ✅ Clear saved screen if no session started
                    val prefs = requireContext().getSharedPreferences("APP_STATE", Context.MODE_PRIVATE)
                    prefs.edit().remove("CURRENT_SCREEN").apply()

                    // ✅ Navigate back to classroom
                    parentFragmentManager.popBackStack()
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    com.example.login.utility.PermissionUtils.showSettingsDialog(this, "Camera permission is required for face scanning. Please enable it in the app settings.") {
                        parentFragmentManager.popBackStack()
                    }
                } else {
                    Toast.makeText(requireContext(), "Camera permission is required for scanning", Toast.LENGTH_LONG).show()
                    parentFragmentManager.popBackStack()
                }
            }
        }
    }

    private fun logTeacherAssignedClasses(teacherId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(requireContext())

                // 1️⃣ Get classes mapped to this teacher
                val classIds = db.teacherClassMapDao().getClassesForTeacher(teacherId)

                if (classIds.isEmpty()) {
                    Log.d("TEACHER_CLASSES", "Teacher $teacherId has NO assigned classes.")
                    return@launch
                }

                // 2️⃣ Get class names
                val classNames = classIds.mapNotNull { id ->
                    db.classDao().getClassById(id)?.classShortName
                }

                // 3️⃣ Print in LOG
                Log.d(
                    "TEACHER_CLASSES",
                    "Teacher $teacherId Assigned Classes → IDs=$classIds NAMES=$classNames"
                )

            } catch (e: Exception) {
                Log.e("TEACHER_CLASSES", "Error fetching assigned classes: ${e.message}")
            }
        }
    }


    private fun showStartStudentAttendanceDialog(teacherId: String, teacherName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Session Started")
            .setMessage("Teacher: $teacherName\n\nStart student attendance capturing now?")
            .setCancelable(false)
            .setPositiveButton("Yes") { _, _ ->
                voiceGuidance.announceThen(
                    message = "Session started.",
                    key = "teacher_session_started"
                ) {
                    if (!isAdded) return@announceThen
                    (requireActivity() as AttendanceActivity).simulateTeacherScan(teacherId)
                    scanningPaused = false
                }
            }
//            .setNegativeButton("No") { _, _ ->
//                // Keep teacher screen active, allow scanning again if needed
//                scanningPaused = false
//                sessionDialogShown = false
//                isVerifying = false
//                faceStableStart = 0L
//            }
            .show()
    }


}
