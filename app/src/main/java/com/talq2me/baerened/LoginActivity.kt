package com.talq2me.baerened

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Login activity for profile selection (AM or BM)
 * This is the entry point when cloud storage is enabled
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var cloudStorageManager: CloudStorageManager
    private lateinit var profileAMButton: Button
    private lateinit var profileBMButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        
        cloudStorageManager = CloudStorageManager(this)
        
        profileAMButton = findViewById(R.id.btnProfileAM)
        profileBMButton = findViewById(R.id.btnProfileBM)
        statusText = findViewById(R.id.statusText)
        
        // Check if cloud storage is enabled
        if (!cloudStorageManager.isCloudStorageEnabled()) {
            // Cloud storage not enabled, go directly to MainActivity
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        
        // Check if Supabase is configured
        if (!cloudStorageManager.isConfigured()) {
            statusText.text = "Cloud storage not configured. Please configure Supabase credentials in build settings."
            profileAMButton.isEnabled = false
            profileBMButton.isEnabled = false
            return
        }
        
        profileAMButton.setOnClickListener {
            loginProfile("AM")
        }
        
        profileBMButton.setOnClickListener {
            loginProfile("BM")
        }
        
        // Show current status
        updateStatus()
    }
    
    private fun loginProfile(profile: String) {
        statusText.text = "Logging in as $profile..."
        profileAMButton.isEnabled = false
        profileBMButton.isEnabled = false
        
        lifecycleScope.launch {
            try {
                // Set current profile (cloud uses AM/BM, local uses A/B)
                cloudStorageManager.setCurrentProfile(profile)
                val localProfile = if (profile == "AM") "A" else "B"
                SettingsManager.writeProfile(this@LoginActivity, localProfile)
                
                // Download data from cloud
                val result = cloudStorageManager.downloadFromCloud(profile)
                
                when {
                    result.isSuccess -> {
                        val userData = result.getOrNull()
                        if (userData != null) {
                            Log.d("LoginActivity", "Successfully loaded profile $profile from cloud")
                            Toast.makeText(this@LoginActivity, "Welcome $profile!", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.d("LoginActivity", "No cloud data found for $profile, using local data")
                            Toast.makeText(this@LoginActivity, "Welcome $profile! (New profile)", Toast.LENGTH_SHORT).show()
                        }
                        
                        // Navigate to MainActivity
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    }
                    else -> {
                        val error = result.exceptionOrNull()?.message ?: "Unknown error"
                        statusText.text = "Error: $error"
                        Toast.makeText(this@LoginActivity, "Failed to load profile: $error", Toast.LENGTH_LONG).show()
                        profileAMButton.isEnabled = true
                        profileBMButton.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                Log.e("LoginActivity", "Error logging in", e)
                statusText.text = "Error: ${e.message}"
                Toast.makeText(this@LoginActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                profileAMButton.isEnabled = true
                profileBMButton.isEnabled = true
            }
        }
    }
    
    private fun updateStatus() {
        val lastSync = cloudStorageManager.getLastSyncTimestamp()
        if (lastSync > 0) {
            val date = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(lastSync))
            statusText.text = "Last synced: $date"
        } else {
            statusText.text = "Select a profile to continue"
        }
    }
}


