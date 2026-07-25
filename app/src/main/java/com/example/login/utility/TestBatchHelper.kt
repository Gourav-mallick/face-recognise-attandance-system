package com.example.login.utility

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TestBatchHelper {
    private const val TAG = "TestBatchHelper"

    fun getRegistrationPicsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "RegistrationPics")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getVerificationPicsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "VerificationPics")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getRegistrationLogFile(context: Context): File {
        return File(context.getExternalFilesDir(null), "registration_test_log.txt")
    }

    fun getVerificationLogFile(context: Context): File {
        return File(context.getExternalFilesDir(null), "verification_test_log.txt")
    }

    fun writeHeader(file: File, headerInfo: String) {
        try {
            val writer = FileWriter(file, false) // Overwrite for new test run
            writer.write("$headerInfo\n")
            writer.write("--------------------------------------------------------------------------------\n")
            writer.close()
            Log.d(TAG, "Wrote log header to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing log header: ${e.message}", e)
        }
    }

    fun appendLine(file: File, line: String) {
        try {
            val writer = FileWriter(file, true) // Append line
            writer.write("$line\n")
            writer.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error appending to log file: ${e.message}", e)
        }
    }

    fun formatLogLine(
        idx: Int,
        accuracyRating: Float,
        fileName: String,
        studentId: String
    ): String {
        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return "$idx ##$$ ${String.format(Locale.US, "%.4f", accuracyRating)} ##$$ $fileName ##$$ $studentId ##$$ $timeStamp"
    }

    fun loadBitmap(file: File): Bitmap? {
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from ${file.name}: ${e.message}")
            null
        }
    }

    suspend fun uploadLogFile(context: Context, logFile: File): Pair<Boolean, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            if (!logFile.exists() || logFile.length() == 0L) {
                Log.e(TAG, "Log file does not exist or is empty: ${logFile.absolutePath}")
                return@withContext Pair(false, "File missing or empty")
            }

            val prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString("baseUrl", "") ?: ""
            val hash = prefs.getString("hash", null)

            if (baseUrl.isBlank()) {
                Log.e(TAG, "Base URL missing in SharedPreferences")
                return@withContext Pair(false, "Base URL missing in LoginPrefs")
            }

            val normalizedBaseUrl = if (baseUrl.endsWith("/")) {
                baseUrl.removeSuffix("/") + "///"
            } else {
                "$baseUrl///"
            }

            val apiService = com.example.login.api.ApiClient.getClient(normalizedBaseUrl, hash)
                .create(com.example.login.api.ApiService::class.java)

            val folderYearBody = okhttp3.RequestBody.create(okhttp3.MediaType.parse("text/plain"), "2026")
            val fileReqBody = okhttp3.RequestBody.create(okhttp3.MediaType.parse("text/plain"), logFile)
            val filePart = okhttp3.MultipartBody.Part.createFormData("userDocumentFileName", logFile.name, fileReqBody)

            val rParam = "api/v1/FileUpload/UploadStudentPhotos"
            Log.d(TAG, "Uploading log file ${logFile.name} to $rParam...")
            val response = apiService.uploadStudentPhotos(rParam, folderYearBody, filePart)

            if (response.isSuccessful && response.body() != null) {
                val respStr = response.body()!!.string()
                Log.i(TAG, "Log File Upload Response: $respStr")
                val json = org.json.JSONObject(respStr)
                val respObj = json.optJSONObject("collection")?.optJSONObject("response")
                val statusMsg = respObj?.optString("statusMsg", "") ?: ""
                val retFileName = respObj?.optString("retStoredDocFileName", "") ?: ""

                if (statusMsg.equals("SUCCESS", ignoreCase = true)) {
                    Log.i(TAG, "Successfully uploaded log file as $retFileName")
                    return@withContext Pair(true, retFileName)
                }
            } else {
                Log.e(TAG, "Upload API failed with HTTP code ${response.code()}")
            }
            Pair(false, "Server returned error")
        } catch (e: Exception) {
            Log.e(TAG, "Exception uploading log file: ${e.message}", e)
            Pair(false, e.localizedMessage ?: "Upload failed")
        }
    }
}
