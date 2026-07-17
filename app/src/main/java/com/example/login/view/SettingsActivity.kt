package com.example.login.view

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.login.R
import com.example.login.db.dao.AppDatabase
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        findViewById<LinearLayout>(R.id.btnContactSupport).setOnClickListener {
            Toast.makeText(this, "Support Contact: support@university.edu", Toast.LENGTH_LONG).show()
        }

        findViewById<LinearLayout>(R.id.btnTerms).setOnClickListener {
            Toast.makeText(this, "Terms of Service opened", Toast.LENGTH_SHORT).show()
        }

        findViewById<LinearLayout>(R.id.btnPrivacy).setOnClickListener {
            Toast.makeText(this, "Privacy Policy opened", Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageView>(R.id.btnEditProfile).setOnClickListener {
            Toast.makeText(this, "Profile editing is managed by Administrator", Toast.LENGTH_LONG).show()
        }

        findViewById<LinearLayout>(R.id.btnLogout).setOnClickListener {
            showLogoutDialog()
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        lifecycleScope.launch {
            // Clear preferences
            getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
                .edit().clear().apply()

            getSharedPreferences("APP_STATE", Context.MODE_PRIVATE)
                .edit().clear().apply()

            Toast.makeText(this@SettingsActivity, "Logged out", Toast.LENGTH_SHORT).show()

            // Go to Login screen
            val intent = Intent(this@SettingsActivity, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }
}
