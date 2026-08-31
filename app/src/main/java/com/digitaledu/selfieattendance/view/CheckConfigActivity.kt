package com.digitaledu.selfieattendance.view

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.utility.AuthSessionManager
import com.digitaledu.selfieattendance.utility.CheckNetworkAndInternetUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CheckConfigActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var layoutContainer: ConstraintLayout
    private val navigationHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "CheckConfig"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_check_config)

        progressBar = findViewById(R.id.progressBarCheck)
        statusText = findViewById(R.id.textStatus)
        layoutContainer = findViewById(R.id.layoutCheckContainer)

        // Initial message
        statusText.text = "Checking configuration..."
        progressBar.visibility = View.VISIBLE

        checkAppConfiguration()
    }

    private fun checkAppConfiguration() {
        // ──────────────────────────────────────────────────────────────
        // STEP 1: Fresh-install detection via noBackupFilesDir marker.
        //
        // noBackupFilesDir is NEVER backed up by Android Auto Backup,
        // Google Cloud Backup, device-to-device transfer, or any known
        // OEM backup tool (Samsung Smart Switch, Xiaomi Cloud, etc.).
        // It is deleted on uninstall. So if the marker is missing, we
        // need to distinguish two cases:
        //
        //  A) Marker missing + credentials exist → This means either:
        //     - Existing user updating the app (first run of new code)
        //       → Just create the marker and continue normally.
        //     - Credentials were restored from backup after reinstall
        //       → Backup rules now prevent this, but as a safety net
        //         the server validation in STEP 3 will catch invalid
        //         credentials anyway.
        //
        //  B) Marker missing + NO credentials → Genuine fresh install.
        //     Any partially-restored data (e.g. app_prefs) is cleaned.
        // ──────────────────────────────────────────────────────────────
        if (AuthSessionManager.isFreshInstall(this)) {
            if (AuthSessionManager.hasLocalCredentials(this)) {
                // Case A: Existing user updating OR credentials restored.
                // Create the marker and let server validation (STEP 3)
                // determine if the credentials are actually valid.
                Log.d(TAG, "No install marker but credentials exist — likely app update, creating marker")
                AuthSessionManager.createInstallMarker(this)
                // Fall through to STEP 2/3 for normal validation
            } else {
                // Case B: Genuine fresh install with no valid credentials.
                // Wipe any partial/restored data and send to Login.
                Log.w(TAG, "Fresh install detected (no marker, no credentials) — cleaning up")
                statusText.text = "Fresh installation detected. Cleaning up..."

                AuthSessionManager.clearAllAuthState(this)
                AuthSessionManager.createInstallMarker(this)

                navigationHandler.postDelayed({
                    navigateToLogin("Please log in to continue.")
                }, 800)
                return
            }
        }

        // ──────────────────────────────────────────────────────────────
        // STEP 2: Check whether local credentials exist.
        // ──────────────────────────────────────────────────────────────
        if (!AuthSessionManager.hasLocalCredentials(this)) {
            Log.d(TAG, "No local credentials found — redirecting to Login")
            statusText.text = "Configuration missing. Redirecting to Login..."
            progressBar.visibility = View.VISIBLE
            navigationHandler.postDelayed({
                navigateToLogin()
            }, 800)
            return
        }

        // ──────────────────────────────────────────────────────────────
        // STEP 3: Validate credentials with the server (if online).
        //
        // If the device is offline we allow access with the existing
        // local credentials (graceful offline fallback). When online
        // we make a lightweight auth call to confirm the session is
        // still valid on the backend.
        // ──────────────────────────────────────────────────────────────
        statusText.text = "Validating Configuration..."

        if (!CheckNetworkAndInternetUtils.isNetworkAvailable(this)) {
            Log.d(TAG, "Offline — allowing access with existing local credentials")
            statusText.text = "Offline mode. Loading..."
            progressBar.visibility = View.VISIBLE
            navigationHandler.postDelayed({
                navigateToHome()
            }, 800)
            return
        }

        // Online → validate with server on background thread
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AuthSessionManager.validateSessionWithServer(this@CheckConfigActivity)

            withContext(Dispatchers.Main) {
                when (result) {
                    is AuthSessionManager.ValidationResult.Valid -> {
                        Log.d(TAG, "Server validated credentials — proceeding to Home")
                        statusText.text = "Session valid! Redirecting..."
                        progressBar.visibility = View.VISIBLE
                        navigationHandler.postDelayed({
                            navigateToHome()
                        }, 500)
                    }

                    is AuthSessionManager.ValidationResult.Invalid -> {
                        Log.w(TAG, "Server rejected credentials — clearing auth state")
                        statusText.text = "configuration expired. Please log in again."
                        AuthSessionManager.clearAllAuthState(this@CheckConfigActivity)
                        Toast.makeText(
                            this@CheckConfigActivity,
                            "Your configuration has expired. Please log in again.",
                            Toast.LENGTH_LONG
                        ).show()
                        navigationHandler.postDelayed({
                            navigateToLogin()
                        }, 1000)
                    }

                    is AuthSessionManager.ValidationResult.NetworkError -> {
                        // Network was detected but the actual API call
                        // failed (timeout, DNS, etc.). Fall back to
                        // offline-like behaviour rather than locking out
                        // the user.
                        Log.w(TAG, "Network error during validation — falling back to offline access")
                        statusText.text = "Connection issue. Loading offline..."
                        progressBar.visibility = View.VISIBLE
                        navigationHandler.postDelayed({
                            navigateToHome()
                        }, 800)
                    }
                }
            }
        }
    }

    // ─── Navigation helpers ──────────────────────────────────────────

    private fun navigateToHome() {
        startActivity(Intent(this, AttendanceActivity::class.java))
        finish()
    }

    private fun navigateToLogin(message: String? = null) {
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        navigationHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
