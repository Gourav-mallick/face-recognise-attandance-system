package com.example.login.view

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.login.R
import com.example.login.api.ApiClient
import com.example.login.api.ApiService
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.Student
import com.example.login.db.entity.Teacher
import com.example.login.utility.CheckNetworkAndInternetUtils
import com.example.login.ml.YuNetSFaceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONObject
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.login.utility.AutoSyncWorker
import java.util.concurrent.TimeUnit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import kotlinx.coroutines.delay
import androidx.core.widget.addTextChangedListener


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
    private val MATCH_THRESHOLD = YuNetSFaceEngine.COSINE_THRESHOLD

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
                    com.example.login.utility.PermissionUtils.showSettingsDialog(this, "Camera permission is required to capture photos for registration. Please enable it in the app settings.")
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
                        s.studentName.lowercase().contains(query) ||
                                s.studentId.lowercase().contains(query)
                    }.map { "${it.studentName} (${it.studentId})" }

                } else {

                    allTeachers.filter { t ->
                        t.staffName.lowercase().contains(query) ||
                                t.staffId.lowercase().contains(query)
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
            if (!com.example.login.utility.PermissionUtils.hasCameraPermission(this)) {
                com.example.login.utility.PermissionUtils.requestCameraPermission(this, CAMERA_PERMISSION_REQUEST_CODE)
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

}
