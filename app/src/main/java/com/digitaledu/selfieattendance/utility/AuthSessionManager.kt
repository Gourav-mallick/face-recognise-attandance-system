package com.digitaledu.selfieattendance.utility

import android.content.Context
import android.util.Log
import com.digitaledu.selfieattendance.api.ApiClient
import com.digitaledu.selfieattendance.api.ApiService
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import org.json.JSONObject
import java.io.File

/**
 * Centralised helper for detecting fresh installs (post-uninstall/reinstall)
 * and validating saved authentication credentials at startup.
 *
 * ## How fresh-install detection works
 * A zero-byte marker file is stored inside [Context.getNoBackupFilesDir].
 * This directory is **never** backed up by Android Auto Backup, Google
 * cloud backup, device-to-device transfer, or any known OEM backup tool
 * (Samsung Smart Switch, Xiaomi Cloud, Oppo Clone Phone, etc.).
 *
 * When the app is uninstalled the entire `noBackupFilesDir` is deleted.
 * On a subsequent reinstall the marker file will be absent, which tells
 * us that any SharedPreferences / Room data that *is* present was
 * restored from a backup and must be wiped.
 */
object AuthSessionManager {

    private const val TAG = "AuthSessionManager"
    private const val INSTALL_MARKER_FILE = ".install_marker"

    // All SharedPreferences files used by the app
    private val AUTH_PREF_FILES = listOf(
        "LoginPrefs",
        "APP_STATE",
        "AttendancePrefs",
        "SyncPrefs",
        "app_prefs"
    )

    // ------------------------------------------------------------------ //
    //  Fresh-install detection
    // ------------------------------------------------------------------ //

    /**
     * Returns `true` when the current launch is the first launch after a
     * fresh install (or reinstall). On the very first call after install
     * the marker file does not exist yet; we create it and return `true`.
     */
    fun isFreshInstall(context: Context): Boolean {
        val markerFile = File(context.noBackupFilesDir, INSTALL_MARKER_FILE)
        return !markerFile.exists()
    }

    /**
     * Creates the installation marker so that subsequent launches are no
     * longer treated as fresh installs.
     */
    fun createInstallMarker(context: Context) {
        try {
            val markerFile = File(context.noBackupFilesDir, INSTALL_MARKER_FILE)
            markerFile.parentFile?.mkdirs()
            markerFile.createNewFile()
            Log.d(TAG, "Install marker created at: ${markerFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create install marker", e)
        }
    }

    // ------------------------------------------------------------------ //
    //  Session cleanup
    // ------------------------------------------------------------------ //

    /**
     * Wipes **all** authentication-related SharedPreferences and clears
     * the local Room database. Call this when a fresh install is detected
     * or when server-side validation fails.
     */
    fun clearAllAuthState(context: Context) {
        // 1. Clear all SharedPreferences files
        for (prefName in AUTH_PREF_FILES) {
            context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                .edit().clear().apply()
            Log.d(TAG, "Cleared SharedPreferences: $prefName")
        }

        // 2. Clear Room database
        try {
            val db = AppDatabase.getDatabase(context)
            db.clearAllTables()
            Log.d(TAG, "Room database cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing Room database", e)
        }
    }

    // ------------------------------------------------------------------ //
    //  Credential presence check
    // ------------------------------------------------------------------ //

    /**
     * Returns `true` when the minimum set of credentials needed for an
     * authenticated session are present in `LoginPrefs`.
     */
    fun hasLocalCredentials(context: Context): Boolean {
        val prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", null)
        val username = prefs.getString("username", null)
        val password = prefs.getString("password", null)
        val instituteId = prefs.getString("selectedInstituteIds", null)
        return !baseUrl.isNullOrEmpty()
                && !username.isNullOrEmpty()
                && !password.isNullOrEmpty()
                && !instituteId.isNullOrEmpty()
    }

    // ------------------------------------------------------------------ //
    //  Server-side session validation
    // ------------------------------------------------------------------ //

    /**
     * Result of a server-side credential validation attempt.
     */
    sealed class ValidationResult {
        /** Credentials are valid on the server. */
        data object Valid : ValidationResult()
        /** Server explicitly rejected the credentials. */
        data object Invalid : ValidationResult()
        /** Could not reach the server (no network, timeout, etc.). */
        data object NetworkError : ValidationResult()
    }

    /**
     * Validates the locally stored username + password against the server
     * by calling the `authenticateStaff` API.
     *
     * **Must be called on a background thread / coroutine.**
     */
    suspend fun validateSessionWithServer(context: Context): ValidationResult {
        return try {
            val prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString("baseUrl", null) ?: return ValidationResult.Invalid
            val username = prefs.getString("username", null) ?: return ValidationResult.Invalid
            val password = prefs.getString("password", null) ?: return ValidationResult.Invalid
            val hash = prefs.getString("hash", null)

            val retrofit = ApiClient.getClient(baseUrl, hash)
            val service = retrofit.create(ApiService::class.java)

            val authData = "{\"username\":\"$username\",\"password\":\"$password\"}"
            val response = service.authenticateStaff(data = authData)

            if (!response.isSuccessful || response.body() == null) {
                Log.w(TAG, "Server validation failed: HTTP ${response.code()}")
                return ValidationResult.Invalid
            }

            val authJson = JSONObject(response.body()!!.string())
            val authCollection = authJson.optJSONObject("collection")
            val authResponseObj = authCollection?.optJSONObject("response")
            val status = authResponseObj?.optString("statusMsg", "FAIL") ?: "FAIL"

            if (status == "SUCCESS") {
                Log.d(TAG, "Server validation: credentials are valid")
                ValidationResult.Valid
            } else {
                Log.w(TAG, "Server validation: credentials rejected (status=$status)")
                ValidationResult.Invalid
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server validation: network/exception error", e)
            ValidationResult.NetworkError
        }
    }
}
