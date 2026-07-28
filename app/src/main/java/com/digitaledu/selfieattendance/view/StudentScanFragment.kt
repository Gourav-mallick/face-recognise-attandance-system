package com.digitaledu.selfieattendance.view

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.camera.view.PreviewView
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.Student
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

class StudentScanFragment : Fragment() {

    // Camera UI components
    private var _viewFinder: PreviewView? = null
    private val viewFinder get() = requireNotNull(_viewFinder)
    private var _faceGuide: View? = null
    private val faceGuide get() = requireNotNull(_faceGuide)
    private var _landmarkOverlay: FaceLandmarkOverlay? = null
    private val landmarkOverlay get() = requireNotNull(_landmarkOverlay)
    private var _tvLightWarning: TextView? = null
    private val tvLightWarning get() = requireNotNull(_tvLightWarning)

    // Info UI components
    private var _tvTeacherName: TextView? = null
    private val tvTeacherName get() = requireNotNull(_tvTeacherName)
    private var _tvPresentCount: TextView? = null
    private val tvPresentCount get() = requireNotNull(_tvPresentCount)
    private var _tvLastStudent: TextView? = null
    private val tvLastStudent get() = requireNotNull(_tvLastStudent)
    private var _tvInstruction: TextView? = null
    private val tvInstruction get() = requireNotNull(_tvInstruction)
    private var _tvLatestCardTapStudentLabel: TextView? = null
    private val tvLatestCardTapStudentLabel get() = requireNotNull(_tvLatestCardTapStudentLabel)
    private var prevFace: YuNetFace? = null


    // Args
    private var teacherNameArg = ""
    private var sessionIdArg = ""

    // Camera Vars
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var isVerifying = false
    private var scanningPausedForDialog = false
    private var faceStableStart = 0L
    private var lastProcessTime = 0L

    private lateinit var faceEngine: YuNetSFaceEngine
    private lateinit var livenessVerifier: ActiveLivenessVerifier
    private lateinit var voiceGuidance: VoiceGuidance
    private val MIRROR_FRONT = true

    private var studentFailCount = 0
    private val MAX_STUDENT_FAILS = 5   // or 4 if you want


    //Add a preloaded cache
    private val cachedStudentEmbeddings = mutableListOf<Triple<String, String, FloatArray>>()
    private val cachedTeacherEmbeddings = mutableListOf<Triple<String, String, FloatArray>>()
    private var cacheLoaded = false


    companion object {
        private const val ARG_TEACHER = "arg_teacher"
        private const val ARG_SESSION_ID = "arg_session_id"

        fun newInstance(teacherName: String, sessionId: String) =
            StudentScanFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TEACHER, teacherName)
                    putString(ARG_SESSION_ID, sessionId)
                }
            }

        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_student_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        // Bind UI
        _viewFinder = view.findViewById(R.id.viewFinder)
        _faceGuide = view.findViewById(R.id.faceGuide)
        _landmarkOverlay = view.findViewById(R.id.landmarkOverlay)
        _tvLightWarning = view.findViewById(R.id.tvLightWarning)

        _tvTeacherName = view.findViewById(R.id.tvTeacherName)
        _tvPresentCount = view.findViewById(R.id.tvPresentCount)
        _tvLastStudent = view.findViewById(R.id.tvLastStudent)
        _tvInstruction = view.findViewById(R.id.tvInstruction)
        _tvLatestCardTapStudentLabel = view.findViewById(R.id.tvLatestCardTapStudentLabel)

        teacherNameArg = arguments?.getString(ARG_TEACHER, "") ?: ""
        sessionIdArg = arguments?.getString(ARG_SESSION_ID, "") ?: ""

        tvTeacherName.text = teacherNameArg
        tvInstruction.text = "Scan Student Face"

        faceEngine = YuNetSFaceEngine(requireContext().applicationContext)
        livenessVerifier = ActiveLivenessVerifier()
        voiceGuidance = VoiceGuidance(requireContext().applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // ---------- LOAD CACHE ONCE ----------
        loadFaceEmbeddingCache()


        if (allPermissionsGranted()) startCamera()
        else requestPermissions(REQUIRED_PERMISSIONS, 101)

        updatePresentCountUI()
    }

    override fun onDestroyView() {
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraExecutor.shutdown()
        faceEngine.close()
        livenessVerifier.close()
        voiceGuidance.close()
        _viewFinder = null
        _faceGuide = null
        _landmarkOverlay = null
        _tvLightWarning = null
        _tvTeacherName = null
        _tvPresentCount = null
        _tvLastStudent = null
        _tvInstruction = null
        _tvLatestCardTapStudentLabel = null
        super.onDestroyView()
    }

    // -----------------------------------------------------------------------
    // CAMERA LOGIC
    // -----------------------------------------------------------------------

    private fun startCamera() {
        if (scanningPausedForDialog || !isAdded) return
        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener({
            if (scanningPausedForDialog || !isAdded || view == null) return@addListener
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis = analysis

            analysis.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) }

            provider.unbindAll()
            provider.bindToLifecycle(
                viewLifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (scanningPausedForDialog) {
            imageProxy.close()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 160 || isVerifying) {
            imageProxy.close()
            return
        }
        lastProcessTime = now

        var displayBitmap: Bitmap? = null
        try {
            val yBuffer = imageProxy.planes[0].buffer.duplicate()
            var brightnessSum = 0L
            val brightnessSamples = yBuffer.remaining()
            while (yBuffer.hasRemaining()) brightnessSum += yBuffer.get().toInt() and 0xFF
            val brightness = if (brightnessSamples > 0) brightnessSum / brightnessSamples else 0L

            val bmp = imageProxyToBitmapUpright(imageProxy)
            val frame = if (MIRROR_FRONT) mirrorBitmap(bmp) else bmp
            displayBitmap = frame
            if (frame !== bmp) bmp.recycle()
            val liveness = livenessVerifier.update(frame)
            val face = faceEngine.detect(frame)
                .maxByOrNull { it.bounds.width() * it.bounds.height() }

            if (face == null) {
                faceStableStart = 0L
                prevFace = null
                runOnViewThread {
                    landmarkOverlay.clear()
                    faceGuide.background.setTint(Color.YELLOW)
                    tvInstruction.text = "Place student face inside the oval"
                    tvLightWarning.visibility = if (brightness < 40) View.VISIBLE else View.GONE
                    voiceGuidance.guide(
                        "Face in oval",
                        "student_no_face"
                    )
                }
                return
            }

            val quality = faceEngine.assessQuality(frame, face, strict = false)
            val stable = isStable(face)
            if (!quality.accepted || !stable || !liveness.passed) faceStableStart = 0L
            else if (faceStableStart == 0L) faceStableStart = now
            prevFace = face

            runOnViewThread {
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
                        "student_quality:${quality.guidance}"
                    } else {
                        "student_liveness:${liveness.guidance}"
                    }
                )
                tvLightWarning.visibility = if (brightness < 40) View.VISIBLE else View.GONE
            }

            if (
                quality.accepted &&
                stable &&
                liveness.passed &&
                now - faceStableStart >= 700 &&
                !isVerifying
            ) {
                isVerifying = true
                val embedding = faceEngine.embedding(frame, face)
                verifyFace(embedding)
                faceStableStart = 0L
            }
        } catch (e: Exception) {
            Log.e("StudentScan", "YuNet/SFace processing failed", e)
            faceStableStart = 0L
            runOnViewThread {
                landmarkOverlay.clear()
                tvInstruction.text = "Face scanner unavailable — retrying"
            }
        } finally {
            displayBitmap?.recycle()
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

    // -----------------------------------------------------------------------
    // FACE MATCHING LOGIC ( use teachers.embedding + students.embedding)
    // -----------------------------------------------------------------------
    private fun verifyFace(faceEmbedding: FloatArray) {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())

            val session = db.sessionDao().getSessionById(sessionIdArg)
            if (session == null) {
                done()
                toast("Unable to continue. Please restart the attendance process.")
                return@launch
            }

            // 1. Get teacher from session
            val teacherId = session.teacherId


            var bestMatchName = "Unknown"
            var bestMatchId: String? = null
            var bestIsTeacher = false
            var bestSimilarity = -1f

            Log.d("SFACE_MATCH", "=== Starting SFace comparison against ${cachedTeacherEmbeddings.size} teacher(s) and ${cachedStudentEmbeddings.size} student(s) (Threshold: ${com.digitaledu.selfieattendance.ml.FaceDetectionConfig.cosineThreshold}) ===")

            // Compare faceEmbedding with teachers
            for ((id, name, emb) in cachedTeacherEmbeddings) {
                val similarity = YuNetSFaceEngine.cosineSimilarity(emb, faceEmbedding)
                Log.d("SFACE_MATCH", "  Candidate Teacher $name (ID: $id): cosine similarity = ${String.format(java.util.Locale.US, "%.4f", similarity)}")
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestMatchName = name
                    bestMatchId = id
                    bestIsTeacher = true
                }
            }


            // Compare faceEmbedding with students
            for ((id, name, emb) in cachedStudentEmbeddings) {
                val similarity = YuNetSFaceEngine.cosineSimilarity(emb, faceEmbedding)
                Log.d("SFACE_MATCH", "  Candidate Student $name (ID: $id): cosine similarity = ${String.format(java.util.Locale.US, "%.4f", similarity)}")
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestMatchName = name
                    bestMatchId = id
                    bestIsTeacher = false
                }
            }


            // Evaluate result
            withContext(Dispatchers.Main) {

                //If no match OR match is too far

                if (!bestIsTeacher && (
                        bestMatchId == null ||
                            bestSimilarity < com.digitaledu.selfieattendance.ml.FaceDetectionConfig.cosineThreshold
                        )
                ) {

                    studentFailCount++

                    if (studentFailCount >= MAX_STUDENT_FAILS) {

                        Toast.makeText(
                            requireContext(),
                            "You are not enrolled for this class.\nPlease contact the administration to complete your enrollment.",
                            Toast.LENGTH_LONG
                        ).show()
                        voiceGuidance.announce(
                            "Student not registered.",
                            "student_not_enrolled"
                        )

                        // Stop verification temporarily to prevent spam scanning
                        isVerifying = true

                        // Reset after 3 seconds so next student can try
                        viewLifecycleOwner.lifecycleScope.launch {
                            delay(1_000)
                            isVerifying = false
                            studentFailCount = 0
                        }

                        done()
                        return@withContext
                    }

                    toast("Face not matched. Adjust your face and try again.")
                    voiceGuidance.announce(
                        "No match. Try again.",
                        "student_match_failed_$studentFailCount"
                    )
                    done()
                    return@withContext
                }


                // 1) If the best match is a teacher
                if (bestIsTeacher) {
                    if (bestMatchId == teacherId) {
                        pauseCameraForDialog()
                        // Check if there are any students marked present in this session
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val db = AppDatabase.getDatabase(requireContext())
                                val attendanceCount =
                                    db.attendanceDao().getAttendancesForSession(sessionIdArg).size

                                withContext(Dispatchers.Main) {
                                    if (attendanceCount == 0) {
                                    voiceGuidance.announce(
                                        "Teacher verified. Choose action.",
                                        "teacher_end_empty_session"
                                    )
                                    AlertDialog.Builder(requireContext())
                                        .setTitle("Empty Session")
                                        .setMessage("No students were scanned in this session.")
                                        .setCancelable(false)
                                        .setPositiveButton("Mass Bunk") { dialog, _ ->
                                            dialog.dismiss()
                                            (requireActivity() as AttendanceActivity).startMassBunkFlow()
                                        }
                                        .setNeutralButton("Start Cycle") { dialog, _ ->
                                            dialog.dismiss()
                                            resumeCameraAfterDialog()
                                        }
                                        .setNegativeButton("Discard Cycle") { dialog, _ ->
                                            dialog.dismiss()
                                            AlertDialog.Builder(requireContext())
                                                .setTitle("Discard Session?")
                                                .setMessage("Are you sure you want to discard this session? All logs will be deleted.")
                                                .setCancelable(false)
                                                .setPositiveButton("Discard") { confDialog, _ ->
                                                     confDialog.dismiss()
                                                     discardSessionAndExit(sessionIdArg)
                                                }
                                                .setNegativeButton("Cancel") { confDialog, _ ->
                                                     confDialog.dismiss()
                                                     resumeCameraAfterDialog()
                                                }
                                                .show()
                                        }
                                        .show()
                                    } else {
                                        voiceGuidance.announce(
                                            "Teacher verified. Complete session.",
                                            "teacher_end_active_session"
                                        )
                                        AlertDialog.Builder(requireContext())
                                            .setTitle("Session Completed")
                                            .setMessage("Students have been scanned in this session.\n\nChoose 'Proceed' to save and select periods, or 'Mistakenly Started' to discard this session.")
                                            .setCancelable(false)
                                            .setPositiveButton("Proceed") { dialog, _ ->
                                                dialog.dismiss()
                                                (requireActivity() as AttendanceActivity)
                                                    .showEndClassDialogForVisibleClass {
                                                        resumeCameraAfterDialog()
                                                    }
                                            }
                                            .setNegativeButton("Mistakenly Started") { dialog, _ ->
                                                dialog.dismiss()
                                                AlertDialog.Builder(requireContext())
                                                    .setTitle("Discard Session?")
                                                    .setMessage("Are you sure you want to discard this session? All marked attendance will be deleted.")
                                                    .setCancelable(false)
                                                    .setPositiveButton("Discard") { confDialog, _ ->
                                                         confDialog.dismiss()
                                                         discardSessionAndExit(sessionIdArg)
                                                    }
                                                    .setNegativeButton("Cancel") { confDialog, _ ->
                                                         confDialog.dismiss()
                                                         resumeCameraAfterDialog()
                                                    }
                                                    .show()
                                            }
                                            .show()
                                    }
                                }
                            } catch (error: Exception) {
                                Log.e("StudentScan", "Unable to prepare session dialog", error)
                                withContext(Dispatchers.Main) {
                                    toast("Unable to load the session. Please try again.")
                                    resumeCameraAfterDialog()
                                }
                            }
                        }
                    } else {
                        toast("This face belongs to a different teacher.")
                        voiceGuidance.announce(
                            "Wrong teacher.",
                            "different_teacher"
                        )
                    }

                    if (!scanningPausedForDialog) done()
                    return@withContext
                }


                // 2) If best match is a student
                val matchedStudent = db.studentsDao().getStudentById(bestMatchId!!)
                if (matchedStudent == null) {
                    toast("Unable to identify the student. Please try again.")
                    voiceGuidance.announce(
                        "Student not found.",
                        "student_lookup_failed"
                    )
                    done()
                    return@withContext
                }

                Log.d("STUDENT_CLASS_CHECK", "Student ${matchedStudent.studentName} class = ${matchedStudent.classId}")


                // Mark attendance through AttendanceActivity logic (preserve everything)
                (requireActivity() as AttendanceActivity).simulateStudentScan(matchedStudent) { result ->
                    val spokenName = VoiceGuidance.speakableName(matchedStudent.studentName)
                    when (result) {
                        AttendanceActivity.StudentAttendanceResult.MARKED ->
                            voiceGuidance.announce(
                                "$spokenName, attendance marked.",
                                "student_marked:${matchedStudent.studentId}"
                            )

                        AttendanceActivity.StudentAttendanceResult.ALREADY_MARKED ->
                            voiceGuidance.announce(
                                "$spokenName, already marked.",
                                "student_already_marked:${matchedStudent.studentId}"
                            )

                        AttendanceActivity.StudentAttendanceResult.ACTIVE_IN_ANOTHER_CLASS ->
                            voiceGuidance.announce(
                                "Already marked in another class.",
                                "student_other_class:${matchedStudent.studentId}"
                            )

                        AttendanceActivity.StudentAttendanceResult.NO_ACTIVE_SESSION ->
                            voiceGuidance.announce(
                                "No active session.",
                                "student_no_session"
                            )
                    }
                    done()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // UI UPDATE HELPERS
    // -----------------------------------------------------------------------
     fun addStudentUI(student: Student) {
        tvLastStudent.text = student.studentName
        tvLatestCardTapStudentLabel.text = "Latest Student"
        updatePresentCountUI()
    }

    private fun updatePresentCountUI() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
            // Total count of students marked
            val count = db.attendanceDao().getAttendancesForSession(sessionIdArg).size

            // Last student marked (from new DAO query)
            val lastStudent = db.attendanceDao().getLastMarkedStudentNameForSession(sessionIdArg)

            withContext(Dispatchers.Main) {
                tvPresentCount.text = count.toString()
                tvLastStudent.text = lastStudent ?: ""
                tvLatestCardTapStudentLabel.text = if (lastStudent != null) "Latest Student" else ""
            }
        }
    }



    // -----------------------------------------------------------------------
    //  UTILITIES for Reset eye probabilities when face changes
    // -----------------------------------------------------------------------
    private fun done() {
        isVerifying = scanningPausedForDialog
        livenessVerifier.reset()
        voiceGuidance.resetGuidance()
        prevFace = null           //  reset motion reference
    }

    private fun pauseCameraForDialog() {
        scanningPausedForDialog = true
        isVerifying = true
        faceStableStart = 0L
        prevFace = null
        landmarkOverlay.clear()
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
    }

    private fun resumeCameraAfterDialog() {
        if (!isAdded || view == null) return
        scanningPausedForDialog = false
        done()
        startCamera()
    }


    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun allPermissionsGranted() =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

    // -----------------------------------------------------------------------
    // IMAGE HELPERS (same as TeacherScanFragment)
    // -----------------------------------------------------------------------
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

    private fun loadFaceEmbeddingCache() {
        if (cacheLoaded) return      // prevents double load
        cacheLoaded = true

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())

            // Load teachers
            db.teachersDao().getAllTeachers().forEach { t ->
                val embStr = t.embedding ?: return@forEach
                val emb = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (emb.size == YuNetSFaceEngine.SFACE_DIMENSIONS) {
                    cachedTeacherEmbeddings.add(
                        Triple(t.staffId, t.staffName, YuNetSFaceEngine.l2Normalize(emb))
                    )
                }
            }

            // Load students of allowed classes ONLY
            val session = db.sessionDao().getSessionById(sessionIdArg) ?: return@launch
            val allowedClassIds = db.teacherClassMapDao().getClassesForTeacher(session.teacherId)

            db.studentsDao().getStudentsByClasses(allowedClassIds).forEach { s ->
                val embStr = s.embedding ?: return@forEach
                val emb = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (emb.size == YuNetSFaceEngine.SFACE_DIMENSIONS) {
                    cachedStudentEmbeddings.add(
                        Triple(s.studentId, s.studentName, YuNetSFaceEngine.l2Normalize(emb))
                    )
                }
            }

            Log.d("EMB_CACHE", "Loaded ${cachedTeacherEmbeddings.size} teachers + ${cachedStudentEmbeddings.size} students into cache")
        }



    }

    private fun discardSessionAndExit(sessionId: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
            db.attendanceDao().deleteAttendanceForSession(sessionId)
            db.sessionDao().deleteSessionById(sessionId)
            db.activeClassCycleDao().getAll()
                .find { it.sessionId == sessionId }
                ?.let { db.activeClassCycleDao().delete(it) }

            withContext(Dispatchers.Main) {
                val prefs1 = requireContext().getSharedPreferences("APP_STATE", Context.MODE_PRIVATE)
                prefs1.edit().clear().apply()

                val prefs2 = requireContext().getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE)
                prefs2.edit().clear().apply()

                Toast.makeText(
                    requireContext(),
                    "Session discarded. Returning to main screen.",
                    Toast.LENGTH_SHORT
                ).show()

                val intent = android.content.Intent(requireContext(), AttendanceActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                requireActivity().finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted() && !scanningPausedForDialog) {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    com.digitaledu.selfieattendance.utility.PermissionUtils.showSettingsDialog(this, "Camera permission is required for student face scanning. Please enable it in the app settings.") {
                        parentFragmentManager.popBackStack()
                    }
                } else {
                    Toast.makeText(requireContext(), "Camera permission is required for scanning", Toast.LENGTH_LONG).show()
                    parentFragmentManager.popBackStack()
                }
            }
        }
    }
}
