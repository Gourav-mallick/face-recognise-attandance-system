package com.digitaledu.selfieattendance.view

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.api.ApiClient
import com.digitaledu.selfieattendance.api.ApiService
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.Student
import com.digitaledu.selfieattendance.db.entity.Teacher
import com.digitaledu.selfieattendance.utility.CheckNetworkAndInternetUtils
import com.digitaledu.selfieattendance.ml.YuNetSFaceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONObject
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.digitaledu.selfieattendance.utility.AutoSyncWorker
import java.util.concurrent.TimeUnit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import kotlinx.coroutines.delay
import androidx.core.widget.addTextChangedListener
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.File
import okhttp3.MultipartBody


class FaceRegistrationActivity : AppCompatActivity() {

    private data class RegisteredFaceMatch(
        val role: String,
        val name: String,
        val userId: String,
        val similarity: Float
    )

    private lateinit var radioUserType: RadioGroup
    private lateinit var radioActionType: RadioGroup

    private lateinit var editSearchId: EditText

    private lateinit var editName: EditText
    private lateinit var btnEnrollFace: Button
    private var selectedStudent: Student? = null
    private var selectedTeacher: Teacher? = null
    private val MATCH_THRESHOLD get() = com.digitaledu.selfieattendance.ml.FaceDetectionConfig.registrationCosineThreshold

    private lateinit var listUsers: ListView
    private lateinit var adapter: ArrayAdapter<String>

    private var allStudents = listOf<Student>()
    private var allTeachers = listOf<Teacher>()
    private var filteredNames = mutableListOf<String>()

    private val CAMERA_PERMISSION_REQUEST_CODE = 1002


    private val liveCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == RESULT_OK) {
                val embedding = result.data?.getFloatArrayExtra(CameraCaptureActivity.EXTRA_FACE_EMBEDDING)
                if (embedding == null || embedding.size != YuNetSFaceEngine.SFACE_DIMENSIONS) {
                    Toast.makeText(this, "Capture failed (invalid SFace template)", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }

                val id = selectedStudent?.studentId ?: selectedTeacher?.staffId
                if (id == null) {
                    Toast.makeText(this, "User ID missing", Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }

                // -----------------------------
                // 🔹 Save final averaged embedding
                // -----------------------------
                saveFace(id, embedding)
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Error during face capture: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("EnrollActivity", "Face capture error", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_registration)

        radioUserType = findViewById(R.id.radioUserType)
        radioActionType = findViewById(R.id.radioActionType)

        editSearchId = findViewById(R.id.editSearchId)
        val scrollView = findViewById<ScrollView>(R.id.scrollView)
        editSearchId.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                scrollView?.postDelayed({
                    scrollView.fullScroll(View.FOCUS_DOWN)
                }, 200)
            }
        }
        editName = findViewById(R.id.editName)
        btnEnrollFace = findViewById(R.id.btnEnrollFace)


        listUsers = findViewById(R.id.listUsers)

        listUsers.setOnTouchListener { v, event ->
            // Allow ListView to handle its own scrolling
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        setupSearchDropdown()
        loadLocalUsers()
        setupDropdownListeners()

        btnEnrollFace.setOnClickListener { handleActionClick() }

        val cardCounter = findViewById<View>(R.id.cardCounter)
        cardCounter?.setOnClickListener {
            val intent = Intent(this, UnregisteredUsersActivity::class.java)
            startActivity(intent)
        }

        val btnSimulateRegistration = findViewById<Button>(R.id.btnSimulateRegistration)
        btnSimulateRegistration?.setOnClickListener {
            val appFilesDir = getExternalFilesDir(null)
            val testImagesSubDir = File(appFilesDir, "TestImages")
            val localFiles = mutableListOf<File>()

            if (testImagesSubDir.exists() && testImagesSubDir.isDirectory) {
                testImagesSubDir.listFiles { _, name ->
                    val lower = name.lowercase()
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                }?.let { localFiles.addAll(it) }
            }

            if (localFiles.isEmpty() && appFilesDir != null && appFilesDir.exists()) {
                appFilesDir.listFiles { _, name ->
                    val lower = name.lowercase()
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                }?.let { localFiles.addAll(it) }
            }

            // Natural ascending sort (e.g. 1.png, 2.png, 3.png, 10.png)
            localFiles.sortWith(Comparator { f1, f2 ->
                val n1 = extractNumber(f1.nameWithoutExtension)
                val n2 = extractNumber(f2.nameWithoutExtension)
                if (n1 != Long.MAX_VALUE || n2 != Long.MAX_VALUE) {
                    n1.compareTo(n2)
                } else {
                    f1.name.compareTo(f2.name, ignoreCase = true)
                }
            })

            if (localFiles.isNotEmpty()) {
                val uris = localFiles.map { Uri.fromFile(it) }
                AlertDialog.Builder(this)
                    .setTitle("Batch Simulation Source")
                    .setMessage("Found ${localFiles.size} image(s) in TestImages folder:\nAndroid/data/com.digitaledu.selfieattendance/files/TestImages/\n\nDo you want to process these images in ascending order or select manually from gallery?")
                    .setPositiveButton("Process TestImages (${localFiles.size})") { _, _ ->
                        processBatchRegistrationSimulation(uris)
                    }
                    .setNegativeButton("Select from Gallery") { _, _ ->
                        batchImagesLauncher.launch("image/*")
                    }
                    .setNeutralButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(this, "No image found in TestImages folder!\nPath: Android/data/com.digitaledu.selfieattendance/files/TestImages/", Toast.LENGTH_LONG).show()
                batchImagesLauncher.launch("image/*")
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
                handleActionClick()
            } else {
                if (!androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.CAMERA)) {
                    com.digitaledu.selfieattendance.utility.PermissionUtils.showSettingsDialog(this, "Camera permission is required to capture photos for registration. Please enable it in the app settings.")
                } else {
                    Toast.makeText(this, "Camera permission is required to capture photos", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUserCounters()
    }

    private fun updateUserCounters() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@FaceRegistrationActivity)
                val totalStudents = db.studentsDao().getTotalStudentsCount()
                val totalTeachers = db.teachersDao().getTotalTeachersCount()
                val unregisteredStudents = db.studentsDao().getUnregisteredStudentsCount()
                val unregisteredTeachers = db.teachersDao().getUnregisteredTeachersCount()

                val totalUsers = totalStudents + totalTeachers
                val totalUnregistered = unregisteredStudents + unregisteredTeachers

                withContext(Dispatchers.Main) {
                    val tvTotalCount = findViewById<TextView>(R.id.tvTotalCount)
                    val tvUnregisteredCount = findViewById<TextView>(R.id.tvUnregisteredCount)
                    if (tvTotalCount != null) tvTotalCount.text = totalUsers.toString()
                    if (tvUnregisteredCount != null) tvUnregisteredCount.text = totalUnregistered.toString()
                }
            } catch (e: Exception) {
                Log.e("FaceRegistrationActivity", "Error updating counters", e)
            }
        }
    }


    private fun loadLocalUsers() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@FaceRegistrationActivity)
            allStudents = db.studentsDao().getAllStudents()
            allTeachers = db.teachersDao().getAllTeachers()
        }
    }


    private fun setupSearchDropdown() {

        adapter = HighlightAdapter(this, R.layout.list_item_user_dropdown, android.R.id.text1, filteredNames)

        listUsers.adapter = adapter

        editSearchId.addTextChangedListener {

            val query = it.toString().trim().lowercase()

            if (query.isEmpty()) {
                //  Clear selection
                editName.setText("")
                selectedStudent = null
                selectedTeacher = null

                //  Clear dropdown
                hideDropdown()
                filteredNames.clear()
                adapter.notifyDataSetChanged()
                return@addTextChangedListener
            }

            filteredNames.clear()

            val type = selectedUserType()
            filteredNames.addAll(
                if (type == "student") {

                    allStudents.filter { s ->
                        s.studentName.lowercase().contains(other = query) ||
                                s.studentId.lowercase().contains(other = query)
                    }.map { "${it.studentName} (${it.studentId})" }

                } else {

                    allTeachers.filter { t ->
                        t.staffName.lowercase().contains(other = query) ||
                                t.staffId.lowercase().contains(other = query)
                    }.map { "${it.staffName} (${it.staffId})" }
                }
            )


          //  listUsers.visibility = View.VISIBLE
            if (filteredNames.isNotEmpty()) {
                listUsers.visibility = View.VISIBLE
            } else {
                listUsers.visibility = View.GONE
            }
            adapter.notifyDataSetChanged()
        }
    }


    private fun setupDropdownListeners() {

        listUsers.setOnItemClickListener { _, _, position, _ ->
            val selected = filteredNames[position]
            val name = selected.substringBefore("(").trim()
            val id = selected.substringAfter("(").removeSuffix(")").trim()

            // editSearchId.setText("$name ($id)")
            editName.setText("$name ($id)")


            val type = selectedUserType()
            if (type == "student") {
                selectedStudent = allStudents.find { it.studentId == id }
                selectedTeacher = null
            } else {
                selectedTeacher = allTeachers.find { it.staffId == id }
                selectedStudent = null
            }

            // Check if already registered
            val existingEmbedding = selectedStudent?.embedding ?: selectedTeacher?.embedding
            if (!existingEmbedding.isNullOrEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Already Registered")
                    .setMessage("User $name ($id) is already registered.\n\nYou can choose to update, delete, or go to the Home screen and verify their identity.")
                    .setPositiveButton("OK", null)
                    .show()
            }

            hideDropdown()

            //  NEW: Hide keyboard
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(editSearchId.windowToken, 0)

            //  NEW: Remove focus from EditText so keyboard does NOT reopen
            editSearchId.clearFocus()
            editName.clearFocus()
        }


        // Hide list when clicking outside
        findViewById<FrameLayout>(R.id.rootLayout).setOnClickListener {
            hideDropdown()
        }
    }

    private fun hideDropdown() {
        listUsers.visibility = View.GONE
    }


    private fun selectedUserType(): String {
        return when (radioUserType.checkedRadioButtonId) {
            R.id.rbStudent -> "student"
            R.id.rbStaff -> "staff"
            else -> "none"
        }
    }

    private fun selectedActionType(): String {
        return when (radioActionType.checkedRadioButtonId) {
            R.id.rbAdd -> "add"
            R.id.rbUpdate -> "update"
            R.id.rbDelete -> "delete"
            else -> "none"
        }
    }

    private fun handleActionClick() {
        try {
            val userFound = selectedStudent != null || selectedTeacher != null
            if (!userFound) {
                Toast.makeText(this, "Search user first!", Toast.LENGTH_SHORT).show()
                return
            }

            val action = selectedActionType()

            if (action == "delete") {
                val id = selectedStudent?.studentId ?: selectedTeacher?.staffId
                if (id != null) showDeleteFaceRegisterAuthDialog(id)
                return
            }

            // Check camera permission
            if (!com.digitaledu.selfieattendance.utility.PermissionUtils.hasCameraPermission(this)) {
                com.digitaledu.selfieattendance.utility.PermissionUtils.requestCameraPermission(this, CAMERA_PERMISSION_REQUEST_CODE)
                return
            }

            // Step 1: Check basic network
            if (!CheckNetworkAndInternetUtils.isNetworkAvailable(this)) {
                Toast.makeText(this, "No network connection!", Toast.LENGTH_SHORT).show()
                return
            }

            lifecycleScope.launch(Dispatchers.Main) {
                // Step 2: Check internet
                val hasInternet = withContext(Dispatchers.IO) { CheckNetworkAndInternetUtils.hasInternetAccess() }
                if (!hasInternet) {
                    Toast.makeText(this@FaceRegistrationActivity, "No active Internet!", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Step 3: Show progress while syncing
                val dialog = android.app.AlertDialog.Builder(this@FaceRegistrationActivity)
                    .setTitle("Preparing for Registration")
                    .setMessage("Syncing with server, please wait...")
                    .setCancelable(false)
                    .create()
                dialog.show()

                // Step 4: Run pre-sync (wait until local DB updated)
                val synced = withContext(Dispatchers.IO) { runPreSyncBeforeEnrollment() }
                dialog.dismiss()

                if (!synced) {
                    Toast.makeText(
                        this@FaceRegistrationActivity,
                        "Sync failed or timed out. Try again later.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // Step 5: Proceed with face capture only if sync completed
                val intent = Intent(this@FaceRegistrationActivity, CameraCaptureActivity::class.java)

                val name = selectedStudent?.studentName ?: selectedTeacher?.staffName ?: ""
                val id = selectedStudent?.studentId ?: selectedTeacher?.staffId ?: ""

                intent.putExtra("user_name", name)
                intent.putExtra("user_id", id)
                liveCaptureLauncher.launch(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("EnrollActivity", "handleActionClick error", e)
        }
    }

    private fun saveFace(id: String, embedding: FloatArray?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // check connection before server call
                if (!CheckNetworkAndInternetUtils.isNetworkAvailable(this@FaceRegistrationActivity)) {
                    showMainToast("No network connection!")
                    return@launch
                }
                if (!CheckNetworkAndInternetUtils.hasInternetAccess()) {
                    showMainToast("No internet access!")
                    return@launch
                }

                val db = AppDatabase.getDatabase(this@FaceRegistrationActivity)
                val embedStr = embedding?.joinToString(",")
                val userType = selectedUserType()
                val action = selectedActionType()

                val student = if (userType == "student") db.studentsDao().getStudentById(id) else null
                val teacher = if (userType == "staff") db.teachersDao().getTeacherById(id) else null

                val existingEmbedding = student?.embedding ?: teacher?.embedding



                if (embedding != null && (action == "add" || action == "update")) {
                    val match = detectMatchingFace(embedding)
                    if (match != null) {
                        // 🔥 Update local cache before checking duplicate
                        allStudents = db.studentsDao().getAllStudents()
                        allTeachers = db.teachersDao().getAllTeachers()

                        Log.d("DUPLICATE_FOUND", "Matched with: $match")

                        val isSameUser = match.userId == id
                        if (!isSameUser || action == "add") {
                            showAlreadyRegisteredDialog(
                                match = match,
                                allowUpdateHint = isSameUser
                            )
                            return@launch
                        }
                    }
                }

                when (action) {
                    "add" -> {
                        if (!existingEmbedding.isNullOrEmpty()) {
                            val existingName = student?.studentName ?: teacher?.staffName ?: "Selected user"
                            showAlreadyRegisteredDialog(
                                RegisteredFaceMatch(
                                    role = if (userType == "student") "Student" else "Teacher",
                                    name = existingName,
                                    userId = id,
                                    similarity = 1f
                                ),
                                allowUpdateHint = true,
                                showSimilarity = false
                            )
                            return@launch
                        }
                        if (embedding == null) return@launch
                        sendFaceToServer(id, userType, embedStr)
                    }

                    "update" -> {
                        if (existingEmbedding.isNullOrEmpty()) {
                            showMainToast("No face registered yet. Please register first.");
                            return@launch
                        }
                        if (embedding == null) return@launch
                        // A legacy FaceNet template cannot be compared with SFace. An authorized
                        // Update intentionally replaces it; the cross-user duplicate gate above
                        // still protects already-migrated SFace users.
                        sendFaceToServer(id, userType, embedStr)
                    }

                    "delete" -> {
                        if (existingEmbedding.isNullOrEmpty()) {
                            showMainToast("No face registered for this user.")
                            return@launch
                        }

                        // 🔥 Call server to delete face → empty template
                        sendFaceToServer(id, userType, "")

                        showMainToast("Deleting Face... please wait")
                    }

                }

                val updated =
                    if (userType == "student") db.studentsDao().getStudentById(id)
                    else db.teachersDao().getTeacherById(id)

                Log.d("EnrollActivity", "Updated Record → $updated")

                val name = when {
                    userType == "student" -> (updated as? Student)?.studentName
                    else -> (updated as? Teacher)?.staffName
                } ?: "User"

                runOnUiThread {
                    AlertDialog.Builder(this@FaceRegistrationActivity)
                        .setTitle("Success")
                        .setMessage("Thank you $name, you are $action successfully.")
                        .setPositiveButton("OK", null)
                        .show()
                }

            } catch (e: Exception) {
                Log.e("EnrollActivity", "saveFace error", e)
                showMainToast(" some error occurred, please try again")
            }
        }
    }

    private suspend fun showMainToast(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@FaceRegistrationActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun detectMatchingFace(
        newEmbedding: FloatArray,
        threshold: Float = MATCH_THRESHOLD
    ): RegisteredFaceMatch? {
        return try {
            Log.d("FaceRegistrationActivity", "SFace Matching Config: inputSize=${com.digitaledu.selfieattendance.ml.FaceDetectionConfig.recognizerInputSize}, embeddingDimensions=${YuNetSFaceEngine.SFACE_DIMENSIONS}, cosineThreshold=$threshold")

            val db = AppDatabase.getDatabase(this@FaceRegistrationActivity)

            var bestSimilarity = -1f
            var matchType = ""
            var matchName = ""
            var matchId = ""

            val students = db.studentsDao().getAllStudents().filter { !it.embedding.isNullOrEmpty() }
            Log.e("MATCH_DEBUG", "Total students with embeddings = ${students.size}")
            for (s in students) {
                val stored = s.embedding!!.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (stored.size != YuNetSFaceEngine.SFACE_DIMENSIONS) continue
                val similarity = YuNetSFaceEngine.cosineSimilarity(stored, newEmbedding)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    matchType = "student"
                    matchName = s.studentName
                    matchId = s.studentId
                }
                Log.d("SFACE_MATCH", "${s.studentName}(${s.studentId}) cosine=$similarity")

            }

            val teachers = db.teachersDao().getAllTeachers().filter { !it.embedding.isNullOrEmpty() }
            Log.e("MATCH_DEBUG", "Total teachers with embeddings = ${teachers.size}")
            for (t in teachers) {
                val stored = t.embedding!!.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (stored.size != YuNetSFaceEngine.SFACE_DIMENSIONS) continue
                val similarity = YuNetSFaceEngine.cosineSimilarity(stored, newEmbedding)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    matchType = "teacher"
                    matchName = t.staffName
                    matchId = t.staffId
                }
                Log.d("SFACE_MATCH", "${t.staffName}(${t.staffId}) cosine=$similarity")

            }
            Log.d("SFACE_MATCH", "Best=$bestSimilarity threshold=$threshold match=$matchName ($matchId)")

            if (bestSimilarity >= threshold) {
                RegisteredFaceMatch(
                    role = matchType.replaceFirstChar { it.uppercase() },
                    name = matchName,
                    userId = matchId,
                    similarity = bestSimilarity
                )
            } else {
                null
            }


        } catch (e: Exception) {
            Log.e("EnrollActivity", "detectMatchingFace error", e)
            null
        }
    }

    private suspend fun showAlreadyRegisteredDialog(
        match: RegisteredFaceMatch,
        allowUpdateHint: Boolean,
        showSimilarity: Boolean = true
    ) {
        withContext(Dispatchers.Main) {
            val message = buildString {
                append("This captured face is already registered.\n\n")
                append("Name: ${match.name}\n")
                append("ID: ${match.userId}\n")
                append("Role: ${match.role}")
                if (showSimilarity) {
                    append("\nFace match: ${(match.similarity * 100).toInt()}%")
                }
                append(
                    if (allowUpdateHint) {
                        "\n\nSelect Update to replace this user's existing face."
                    } else {
                        ""
                    }
                )
            }
            AlertDialog.Builder(this@FaceRegistrationActivity)
                .setTitle("Face Already Registered")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private suspend fun sendFaceToServer(
        id: String,
        userType: String,
        embeddingStr: String?
    ) = withContext(Dispatchers.IO) {
        val json = """
        {
          "userRegParamData": {
            "userType": "$userType",
            "registrationType": "Biometric",
            "regParamData": [
              {
                "userId": "$id",
                "metricType": "faceSignature",
                "fingerType": "faceSignature",
                "template": "${embeddingStr ?: ""}"
              }
            ]
          }
        }
        """.trimIndent()

        try {
            val mediaType = MediaType.parse("application/json; charset=utf-8")
            val requestBody = RequestBody.create(mediaType, json)
            val baseUrl = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                .getString("baseUrl", "")!!
            val hash = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                .getString("hash", null)
            val api = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)
            val response = api.postUserRegistration(body = requestBody)
            Log.d("EnrollActivity", "Response: $response")

            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FaceRegistrationActivity, "HTTP error: ${response.code()}", Toast.LENGTH_LONG).show()
                }
                return@withContext
            }

            val bodyStr = response.body()?.string() ?: ""
            Log.d("EnrollActivity", "Response body: $bodyStr")

            val jsonObj = JSONObject(bodyStr)
            val collection = jsonObj.optJSONObject("collection")
            val resp = collection?.optJSONObject("response")
            val successStatus = resp?.optString("successStatus", "FALSE") ?: "FALSE"

            if (successStatus.equals("TRUE", ignoreCase = true)) {
                val db = AppDatabase.getDatabase(this@FaceRegistrationActivity)
                if (userType == "student") {
                    db.studentsDao().updateStudentEmbedding(id, embeddingStr ?: "")
                } else {
                    db.teachersDao().updateTeacherEmbedding(id, embeddingStr ?: "")
                }
                allStudents = db.studentsDao().getAllStudents()
                allTeachers = db.teachersDao().getAllTeachers()
                updateUserCounters()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FaceRegistrationActivity, "Face synced and stored locally!", Toast.LENGTH_LONG).show()

                    // 🔹 Trigger local DB sync automatically
                    val workRequest = OneTimeWorkRequestBuilder<AutoSyncWorker>()
                       .setInitialDelay(3, TimeUnit.SECONDS)
                        .build()

                    WorkManager.getInstance(this@FaceRegistrationActivity).enqueue(workRequest)
                }

                // Create local registration text file and upload to UploadStudentPhotos API
                try {
                    val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
                    val logFile = File(getExternalFilesDir(null), "registration_${id}_${System.currentTimeMillis()}.txt")
                    logFile.writeText("Face Registration Log\nUser ID: $id\nUser Type: $userType\nYear: $currentYear\nTimestamp: ${java.util.Date()}\nStatus: SUCCESS\n")
                    val uploadResult = uploadReportFileToServer(logFile)
                    Log.i("EnrollActivity", "Single registration report upload result: $uploadResult")
                } catch (e: Exception) {
                    Log.e("EnrollActivity", "Error uploading single registration log file", e)
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FaceRegistrationActivity, "Server rejected data!", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@FaceRegistrationActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            Log.e("EnrollActivity", "sendFaceToServer error", e)
        }
    }

/*
    private fun showDeleteFaceRegisterAuthDialog(userId: String) {
        val input = EditText(this)
        input.hint = "Enter admin PIN"
        input.setPadding(40, 40, 40, 40)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Authentication Required")
            .setMessage("Enter admin PIN to delete face data")
            .setView(input)
            .setCancelable(true)
            .setPositiveButton("Confirm") { _, _ ->
                val pin = input.text.toString().trim()

                // 🔐 Change this PIN as you want
                if (pin == "1234") {
                    // Auth Passed → delete face
                    saveFace(userId, null)
                } else {
                    Toast.makeText(this, "Invalid PIN!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

 */

    private fun showDeleteFaceRegisterAuthDialog(userId: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_auth_sync, null)
        val edtUsername = dialogView.findViewById<EditText>(R.id.edtUsername)
        val edtPassword = dialogView.findViewById<EditText>(R.id.edtPassword)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSubmit = dialogView.findViewById<Button>(R.id.btnSubmit)

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val enteredUser = edtUsername.text.toString().trim()
            val enteredPass = edtPassword.text.toString().trim()

            val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
            val savedUser = prefs.getString("username", "")
            val savedPass = prefs.getString("password", "")

            if (enteredUser == savedUser && enteredPass == savedPass) {
                dialog.dismiss()
                saveFace(userId, null)  // ← Same logic as before (Delete face)
            } else {
                Toast.makeText(this, "Invalid credentials!", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }


    private suspend fun runPreSyncBeforeEnrollment(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<AutoSyncWorker>()
                    .setConstraints(constraints)
                    .build()

                // Run unique sync job so we don't start duplicates
                WorkManager.getInstance(this@FaceRegistrationActivity)
                    .enqueueUniqueWork(
                        "PreEnrollSync",
                        ExistingWorkPolicy.KEEP,
                        workRequest
                    )

                val workId = workRequest.id
                var waited = 0
                while (waited < 30000) { // 30 sec timeout
                    val info = WorkManager.getInstance(this@FaceRegistrationActivity)
                        .getWorkInfoById(workId).get()
                    when (info?.state) {
                        WorkInfo.State.SUCCEEDED -> return@withContext true
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> return@withContext false
                        else -> {
                            delay(500)
                            waited += 500
                        }
                    }
                }
                false // timeout
            } catch (e: Exception) {
                Log.e("EnrollActivity", "PreSync error: ${e.message}")
                false
            }
        }
    }

    private inner class HighlightAdapter(
        context: android.content.Context,
        resource: Int,
        textViewResourceId: Int,
        objects: List<String>
    ) : ArrayAdapter<String>(context, resource, textViewResourceId, objects) {

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val textView = view.findViewById<TextView>(android.R.id.text1)
            val fullText = getItem(position) ?: ""
            val query = editSearchId.text.toString().trim()

            if (query.isNotEmpty() && fullText.isNotEmpty()) {
                val spannable = android.text.SpannableString(fullText)
                val queryLower = query.lowercase()
                val fullTextLower = fullText.lowercase()
                var startPos = fullTextLower.indexOf(queryLower)
                while (startPos >= 0) {
                    val endPos = startPos + queryLower.length
                    spannable.setSpan(
                        android.text.style.BackgroundColorSpan(android.graphics.Color.YELLOW),
                        startPos,
                        endPos,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    startPos = fullTextLower.indexOf(queryLower, endPos)
                }
                textView.text = spannable
            } else {
                textView.text = fullText
            }
            return view
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ⚡ BATCH REGISTRATION SIMULATION
    // ─────────────────────────────────────────────────────────────────────────

    private val batchImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) {
            Toast.makeText(this, "No images selected for simulation", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        processBatchRegistrationSimulation(uris)
    }

    private data class CandidateComparison(
        val name: String,
        val id: String,
        val source: String,
        val score: Float
    )

    private data class BatchSimulationResult(
        val index: Int,
        val fileName: String,
        val targetUserId: String,
        val targetUserName: String,
        val status: String, // "REGISTERED SUCCESS", "REJECTED: QUALITY FAILED", "ALREADY REGISTERED (DUPLICATE)", "NO FACE DETECTED"
        val rejectionReason: String = "",
        val eyeDistance: Float = 0f,
        val sharpness: Float = 0f,
        val matchedUserId: String = "",
        val matchedUserName: String = "",
        val matchedFileName: String = "",
        val similarityScore: Float = 0f,
        val embeddingStr: String = "",
        val embeddingVector: FloatArray? = null,
        val comparisons: List<CandidateComparison> = emptyList()
    )

    private fun processBatchRegistrationSimulation(uris: List<Uri>) {
        @Suppress("DEPRECATION")
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("Batch Registration Simulation")
            setMessage("Initializing face detection engine...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@FaceRegistrationActivity)
            val userType = if (radioUserType.checkedRadioButtonId == R.id.rbStaff) "staff" else "student"

            // 1. Fetch unregistered users sorted in ascending numerical/alphabetical order
            val unregisteredStudents = if (userType == "student") {
                db.studentsDao().getAllStudents()
                    .filter { it.embedding.isNullOrEmpty() }
                    .sortedWith(compareBy({ extractNumber(it.studentId) }, { it.studentId }))
            } else emptyList()

            val unregisteredTeachers = if (userType == "staff") {
                db.teachersDao().getAllTeachers()
                    .filter { it.embedding.isNullOrEmpty() }
                    .sortedWith(compareBy({ extractNumber(it.staffId) }, { it.staffId }))
            } else emptyList()

            // Sort input images in natural ascending order (e.g. 1.png, 2.png, 10.png)
            val sortedUris = uris.sortedWith(Comparator { u1, u2 ->
                val name1 = getUriFileName(u1) ?: ""
                val name2 = getUriFileName(u2) ?: ""
                val n1 = extractNumber(name1.substringBeforeLast('.'))
                val n2 = extractNumber(name2.substringBeforeLast('.'))
                if (n1 != Long.MAX_VALUE || n2 != Long.MAX_VALUE) {
                    n1.compareTo(n2)
                } else {
                    name1.compareTo(name2, ignoreCase = true)
                }
            })

            // 2. Fetch existing registered embeddings from DB
            val registeredStudents = db.studentsDao().getAllStudents().filter { !it.embedding.isNullOrEmpty() }
            val registeredTeachers = db.teachersDao().getAllTeachers().filter { !it.embedding.isNullOrEmpty() }

            val batchRegisteredList = mutableListOf<BatchSimulationResult>()
            val allResults = mutableListOf<BatchSimulationResult>()

            var successCount = 0
            var duplicateCount = 0
            var failedCount = 0

            val threshold = com.digitaledu.selfieattendance.ml.FaceDetectionConfig.registrationCosineThreshold
            val engine = YuNetSFaceEngine(applicationContext)

            try {
                for ((idx, uri) in sortedUris.withIndex()) {
                    val fileName = getUriFileName(uri) ?: "Image_${idx + 1}.jpg"

                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("Processing image ${idx + 1} of ${uris.size}:\n$fileName")
                    }

                    // Assign target user from unregistered list or generate batch ID
                    val targetUserId: String
                    val targetUserName: String

                    if (userType == "student") {
                        val student = unregisteredStudents.getOrNull(idx)
                        targetUserId = student?.studentId ?: "BATCH_STU_${idx + 1}"
                        targetUserName = student?.studentName ?: "Batch Student ${idx + 1}"
                    } else {
                        val teacher = unregisteredTeachers.getOrNull(idx)
                        targetUserId = teacher?.staffId ?: "BATCH_TCH_${idx + 1}"
                        targetUserName = teacher?.staffName ?: "Batch Teacher ${idx + 1}"
                    }

                    // Decode image to Bitmap
                    val bitmap = decodeUriToBitmap(uri)
                    if (bitmap == null) {
                        allResults.add(
                            BatchSimulationResult(
                                index = idx + 1,
                                fileName = fileName,
                                targetUserId = targetUserId,
                                targetUserName = targetUserName,
                                status = "NO FACE DETECTED (Invalid File)"
                            )
                        )
                        failedCount++
                        continue
                    }

                    // Detect face with detailed diagnostics
                    val diag = engine.detectWithDiagnostics(bitmap)
                    if (diag.faces.isEmpty()) {
                        allResults.add(
                            BatchSimulationResult(
                                index = idx + 1,
                                fileName = fileName,
                                targetUserId = targetUserId,
                                targetUserName = targetUserName,
                                status = "NO FACE DETECTED",
                                rejectionReason = diag.diagnosticReason
                            )
                        )
                        failedCount++
                        bitmap.recycle()
                        continue
                    }

                    val faces = diag.faces
                    // Get largest face
                    val primaryFace = faces.maxByOrNull { it.bounds.width() * it.bounds.height() }!!

                    // ── REGISTRATION QUALITY GATE ENFORCEMENT (Strict Mode) ──
                    // Enforces strict = true (Sharpness >= 90, Symmetry <= 0.16, EyeDistance >= 45px).
                    // Preventing blurry or unaligned photos from entering the DB is mandatory to prevent
                    // embedding collapse and false duplicate matches.
                    val quality = engine.assessQualityDetailed(bitmap, primaryFace, strict = true)
                    if (!quality.accepted) {
                        Log.w("BatchTest", "⚠️ Image #${idx + 1} ($fileName) QUALITY FAILED: ${quality.guidance}")
                        allResults.add(
                            BatchSimulationResult(
                                index = idx + 1,
                                fileName = fileName,
                                targetUserId = targetUserId,
                                targetUserName = targetUserName,
                                status = "REJECTED: QUALITY FAILED",
                                rejectionReason = quality.guidance,
                                eyeDistance = quality.eyeDistance,
                                sharpness = quality.sharpness
                            )
                        )
                        failedCount++
                        bitmap.recycle()
                        continue
                    }

                    val embedding = engine.embedding(bitmap, primaryFace)
                    val embeddingStr = embedding.joinToString(",")
                    bitmap.recycle()

                    // Duplicate Check against 1) DB Students, 2) DB Teachers, 3) Current Batch Session
                    var highestSim = -1f
                    var matchedId = ""
                    var matchedName = ""
                    var matchedFile = ""
                    val comparisonsList = mutableListOf<CandidateComparison>()

                    // Check DB students
                    for (s in registeredStudents) {
                        val vec = s.embedding?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toFloatArray() ?: continue
                        if (vec.size != YuNetSFaceEngine.SFACE_DIMENSIONS) continue
                        val sim = YuNetSFaceEngine.cosineSimilarity(vec, embedding)
                        comparisonsList.add(CandidateComparison(s.studentName, s.studentId, "Registered Student DB", sim))
                        if (sim > highestSim) {
                            highestSim = sim
                            matchedId = s.studentId
                            matchedName = "${s.studentName} (Student)"
                            matchedFile = "Registered DB"
                        }
                    }

                    // Check DB teachers
                    for (t in registeredTeachers) {
                        val vec = t.embedding?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toFloatArray() ?: continue
                        if (vec.size != YuNetSFaceEngine.SFACE_DIMENSIONS) continue
                        val sim = YuNetSFaceEngine.cosineSimilarity(vec, embedding)
                        comparisonsList.add(CandidateComparison(t.staffName, t.staffId, "Registered Teacher DB", sim))
                        if (sim > highestSim) {
                            highestSim = sim
                            matchedId = t.staffId
                            matchedName = "${t.staffName} (Teacher)"
                            matchedFile = "Registered DB"
                        }
                    }

                    // Check current batch session items
                    for (b in batchRegisteredList) {
                        val vec = b.embeddingVector ?: continue
                        val sim = YuNetSFaceEngine.cosineSimilarity(vec, embedding)
                        comparisonsList.add(CandidateComparison(b.targetUserName, b.targetUserId, b.fileName, sim))
                        if (sim > highestSim) {
                            highestSim = sim
                            matchedId = b.targetUserId
                            matchedName = b.targetUserName
                            matchedFile = b.fileName
                        }
                    }

                    val sortedComparisons = comparisonsList.sortedByDescending { it.score }

                    Log.d("BatchTest", "Image #${idx + 1} ($fileName): Pre-registration check against ${registeredStudents.size} registered students, ${registeredTeachers.size} registered teachers, and ${batchRegisteredList.size} batch items...")

                    if (highestSim >= threshold) {
                        // Duplicate / Already Registered detected
                        duplicateCount++
                        Log.w("BatchTest", "❌ Image #${idx + 1} ($fileName) ALREADY REGISTERED / DUPLICATE! Matched $matchedName (ID: $matchedId, File: $matchedFile) — Cosine Sim: $highestSim >= Threshold: $threshold")
                        allResults.add(
                            BatchSimulationResult(
                                index = idx + 1,
                                fileName = fileName,
                                targetUserId = targetUserId,
                                targetUserName = targetUserName,
                                status = "ALREADY REGISTERED (DUPLICATE)",
                                rejectionReason = "Face matches already registered user $matchedName (ID: $matchedId)",
                                eyeDistance = quality.eyeDistance,
                                sharpness = quality.sharpness,
                                matchedUserId = matchedId,
                                matchedUserName = matchedName,
                                matchedFileName = matchedFile,
                                similarityScore = highestSim,
                                comparisons = sortedComparisons
                            )
                        )
                    } else {
                        // Success -> Register
                        successCount++
                        Log.i("BatchTest", "✔ Image #${idx + 1} ($fileName) No registered match found (Max Sim: $highestSim < Threshold: $threshold). Registering as $targetUserName (ID: $targetUserId)")
                        val res = BatchSimulationResult(
                            index = idx + 1,
                            fileName = fileName,
                            targetUserId = targetUserId,
                            targetUserName = targetUserName,
                            status = "REGISTERED SUCCESS",
                            eyeDistance = quality.eyeDistance,
                            sharpness = quality.sharpness,
                            embeddingStr = embeddingStr,
                            embeddingVector = embedding,
                            comparisons = sortedComparisons
                        )
                        batchRegisteredList.add(res)
                        allResults.add(res)
                    }
                }

                // -------------------------------------------------------------
                // Bulk Upload to Server All At Once
                // -------------------------------------------------------------
                var serverUploadStatus = "NOT ATTEMPTED"
                if (batchRegisteredList.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("Uploading ${batchRegisteredList.size} registered faces to server in bulk...")
                    }

                    val regParamDataArray = JSONArray()
                    for (item in batchRegisteredList) {
                        val obj = JSONObject().apply {
                            put("userId", item.targetUserId)
                            put("metricType", "faceSignature")
                            put("fingerType", "faceSignature")
                            put("template", item.embeddingStr)
                        }
                        regParamDataArray.put(obj)
                    }

                    val bulkJson = JSONObject().apply {
                        put("userRegParamData", JSONObject().apply {
                            put("userType", userType)
                            put("registrationType", "Biometric")
                            put("regParamData", regParamDataArray)
                        })
                    }

                    try {
                        val mediaType = MediaType.parse("application/json; charset=utf-8")
                        val requestBody = RequestBody.create(mediaType, bulkJson.toString())
                        val baseUrl = getSharedPreferences("LoginPrefs", MODE_PRIVATE).getString("baseUrl", "")!!
                        val hash = getSharedPreferences("LoginPrefs", MODE_PRIVATE).getString("hash", null)
                        val api = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

                        val response = api.postUserRegistration(body = requestBody)
                        if (response.isSuccessful && response.body() != null) {
                            val bodyStr = response.body()!!.string()
                            val jsonObj = JSONObject(bodyStr)
                            val successStatus = jsonObj.optJSONObject("collection")
                                ?.optJSONObject("response")
                                ?.optString("successStatus", "FALSE") ?: "FALSE"

                            if (successStatus.equals("TRUE", ignoreCase = true)) {
                                serverUploadStatus = "SUCCESS (Bulk Uploaded)"
                                // Save embeddings in local Room DB
                                for (item in batchRegisteredList) {
                                    if (userType == "student") {
                                        db.studentsDao().updateStudentEmbedding(item.targetUserId, item.embeddingStr)
                                    } else {
                                        db.teachersDao().updateTeacherEmbedding(item.targetUserId, item.embeddingStr)
                                    }
                                }
                                allStudents = db.studentsDao().getAllStudents()
                                allTeachers = db.teachersDao().getAllTeachers()
                            } else {
                                serverUploadStatus = "FAILED (Server rejected bulk payload)"
                            }
                        } else {
                            serverUploadStatus = "FAILED (HTTP ${response.code()})"
                        }
                    } catch (e: Exception) {
                        serverUploadStatus = "FAILED (${e.message})"
                        Log.e("BatchSimulation", "Bulk upload error", e)
                    }
                }

                // -------------------------------------------------------------
                // Generate Detailed .txt Report File & Upload to Server
                // -------------------------------------------------------------
                val reportPath = writeBatchReportToFile(uris.size, successCount, duplicateCount, failedCount, threshold, serverUploadStatus, allResults)
                val reportFile = File(reportPath)
                val reportUploadStatus = uploadReportFileToServer(reportFile)
                Log.i("BatchSimulation", "Batch report file upload result: $reportUploadStatus")

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    updateUserCounters()

                    showBatchReportSummaryDialog(
                        total = uris.size,
                        success = successCount,
                        duplicates = duplicateCount,
                        failures = failedCount,
                        serverStatus = serverUploadStatus,
                        reportPath = reportPath
                    )
                }

            } finally {
                engine.close()
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                }
            }
        }
    }

    private fun getUriFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) name = cursor.getString(index)
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/') ?: -1
            if (cut != -1) name = name?.substring(cut + 1)
        }
        return name
    }

    private fun decodeUriToBitmap(uri: Uri): android.graphics.Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.e("BatchSimulation", "Error decoding uri $uri", e)
            null
        }
    }

    private fun writeBatchReportToFile(
        total: Int,
        success: Int,
        duplicates: Int,
        failures: Int,
        threshold: Float,
        serverStatus: String,
        results: List<BatchSimulationResult>
    ): String {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val sb = StringBuilder()
        sb.append("======================================================================\n")
        sb.append("                  BATCH REGISTRATION TEST REPORT                      \n")
        sb.append("======================================================================\n")
        sb.append("Timestamp:                $timestamp\n")
        sb.append("Threshold Used (Cosine):  $threshold\n")
        sb.append("Total Images Processed:   $total\n")
        sb.append("Successfully Registered:  $success\n")
        sb.append("Duplicate Rejections:     $duplicates\n")
        sb.append("Failed (No Face / Error): $failures\n")
        sb.append("Server Bulk Upload:       $serverStatus\n")
        sb.append("======================================================================\n")
        sb.append("ACTIVE DETECTION & RECOGNITION CONFIGURATION:\n")
        sb.append("  • Canvas Input Size:    ${com.digitaledu.selfieattendance.ml.FaceDetectionConfig.detectorInputSize} x ${com.digitaledu.selfieattendance.ml.FaceDetectionConfig.detectorInputSize}\n")
        sb.append("  • Score Threshold:      ${com.digitaledu.selfieattendance.ml.FaceDetectionConfig.detectionThreshold}\n")
        sb.append("  • NMS IoU Threshold:    ${com.digitaledu.selfieattendance.ml.FaceDetectionConfig.nmsThreshold}\n")
        sb.append("  • TopK Candidates:      ${com.digitaledu.selfieattendance.ml.FaceDetectionConfig.topK}\n")
        sb.append("  • Min Face Size:        ${com.digitaledu.selfieattendance.ml.FaceDetectionConfig.minFaceSize} px\n")
        sb.append("  • Max Face Size:        ${com.digitaledu.selfieattendance.ml.FaceDetectionConfig.maxFaceSize} px\n")
        sb.append("  • Cosine Threshold:     ${com.digitaledu.selfieattendance.ml.FaceDetectionConfig.cosineThreshold}\n")
        sb.append("  • Recognizer Input:     ${com.digitaledu.selfieattendance.ml.FaceDetectionConfig.recognizerInputSize} x ${com.digitaledu.selfieattendance.ml.FaceDetectionConfig.recognizerInputSize}\n")
        sb.append("  • Embedding Dimensions: ${YuNetSFaceEngine.SFACE_DIMENSIONS}\n")
        sb.append("======================================================================\n\n")

        for (item in results) {
            sb.append("[Item #${item.index}]\n")
            sb.append("  Image File:       ${item.fileName}\n")
            sb.append("  Target User:      ${item.targetUserName} (ID: ${item.targetUserId})\n")
            sb.append("  Status:           ${item.status}\n")
            if (item.rejectionReason.isNotEmpty()) {
                sb.append("  Rejection Cause:  ${item.rejectionReason}\n")
            }
            if (item.eyeDistance > 0f || item.sharpness > 0f) {
                sb.append("  Quality Metrics:  Eye Distance: ${String.format(java.util.Locale.US, "%.1f", item.eyeDistance)}px | Sharpness Score: ${String.format(java.util.Locale.US, "%.1f", item.sharpness)}\n")
            }
            if (item.matchedUserId.isNotEmpty()) {
                sb.append("  Matched With:     ${item.matchedUserName} (ID: ${item.matchedUserId})\n")
                sb.append("  Matched Source:   ${item.matchedFileName}\n")
                sb.append("  Cosine Score:     ${String.format(java.util.Locale.US, "%.4f", item.similarityScore)} (>= Threshold: ${String.format(java.util.Locale.US, "%.4f", threshold)})\n")
            }
            if (item.comparisons.isNotEmpty()) {
                sb.append("  Candidate Comparisons (Sorted by Cosine Score):\n")
                for (comp in item.comparisons) {
                    val matchTag = if (comp.score >= threshold) " [>= ${String.format(java.util.Locale.US, "%.4f", threshold)} MATCH]" else ""
                    sb.append("    • ${comp.name} (ID: ${comp.id} | Source: ${comp.source}): Cosine Score = ${String.format(java.util.Locale.US, "%.4f", comp.score)}$matchTag\n")
                }
            }
            sb.append("----------------------------------------------------------------------\n")
        }

        return try {
            val fileName = "batch_registration_report_${System.currentTimeMillis()}.txt"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val file = File(downloadsDir, fileName)
            file.writeText(sb.toString())

            val appFile = File(getExternalFilesDir(null), fileName)
            appFile.writeText(sb.toString())

            file.absolutePath
        } catch (e: Exception) {
            Log.e("BatchSimulation", "Failed to write report to Downloads", e)
            val fallbackFile = File(getExternalFilesDir(null), "batch_registration_report.txt")
            fallbackFile.writeText(sb.toString())
            fallbackFile.absolutePath
        }
    }

    private fun showBatchReportSummaryDialog(
        total: Int,
        success: Int,
        duplicates: Int,
        failures: Int,
        serverStatus: String,
        reportPath: String
    ) {
        val msg = """
            Batch Processing Complete!

            Total Processed: $total
            Registered: $success
            Duplicates Rejected: $duplicates
            Failed (No Face): $failures
            Server Bulk Upload: $serverStatus

            Report File Saved To:
            $reportPath
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Batch Simulation Completed")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private suspend fun uploadReportFileToServer(file: File): String = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getSharedPreferences("LoginPrefs", MODE_PRIVATE).getString("baseUrl", "")
            if (baseUrl.isNullOrBlank()) return@withContext "FAILED (Missing Base URL)"
            val hash = getSharedPreferences("LoginPrefs", MODE_PRIVATE).getString("hash", null)
            val api = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
            val folderYearBody = RequestBody.create(MediaType.parse("text/plain"), currentYear)

            val mediaType = MediaType.parse("text/plain")
            val fileReqBody = RequestBody.create(mediaType, file)
            val filePart = MultipartBody.Part.createFormData("userDocumentFileName", file.name, fileReqBody)

            Log.d("FileUpload", "Uploading document file '${file.name}' to UploadStudentPhotos API (Year: $currentYear)...")
            val response = api.uploadStudentPhotos(folderYear = folderYearBody, file = filePart)

            if (response.isSuccessful && response.body() != null) {
                val bodyStr = response.body()!!.string()
                Log.d("FileUpload", "Upload response: $bodyStr")
                val jsonObj = JSONObject(bodyStr)
                val retStoredDocFileName = jsonObj.optJSONObject("collection")
                    ?.optJSONObject("response")
                    ?.optString("retStoredDocFileName", "") ?: ""

                if (retStoredDocFileName.isNotEmpty()) {
                    Log.i("FileUpload", "✔ Document uploaded successfully: $retStoredDocFileName")
                    "SUCCESS ($retStoredDocFileName)"
                } else {
                    "SUCCESS"
                }
            } else {
                Log.w("FileUpload", "Document upload failed with HTTP ${response.code()}")
                "FAILED (HTTP ${response.code()})"
            }
        } catch (e: Exception) {
            Log.e("FileUpload", "Exception during document upload: ${e.message}", e)
            "FAILED (${e.message})"
        }
    }

    private fun extractNumber(name: String): Long {
        val digits = name.replace(Regex("[^0-9]"), "")
        return digits.toLongOrNull() ?: Long.MAX_VALUE
    }

}
