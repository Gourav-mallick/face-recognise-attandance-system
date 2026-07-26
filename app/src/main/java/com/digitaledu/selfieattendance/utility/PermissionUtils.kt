package com.digitaledu.selfieattendance.utility

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

object PermissionUtils {

    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestCameraPermission(activity: Activity, requestCode: Int) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.CAMERA),
            requestCode
        )
    }

    fun requestCameraPermission(fragment: Fragment, requestCode: Int) {
        fragment.requestPermissions(
            arrayOf(Manifest.permission.CAMERA),
            requestCode
        )
    }

    fun showSettingsDialog(
        activity: Activity,
        message: String = "Camera permission is required to use this feature. Please enable it in the app settings.",
        onCancel: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("Permission Required")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Settings") { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                }
                activity.startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                onCancel?.invoke()
            }
            .show()
    }

    fun showSettingsDialog(
        fragment: Fragment,
        message: String = "Camera permission is required to use this feature. Please enable it in the app settings.",
        onCancel: (() -> Unit)? = null
    ) {
        val context = fragment.context ?: return
        val activity = fragment.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(context)
            .setTitle("Permission Required")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Settings") { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                fragment.startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                onCancel?.invoke()
            }
            .show()
    }
}
