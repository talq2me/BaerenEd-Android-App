package com.talq2me.baerened

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kid-facing photo chore: show today's chore, take a picture, upload to image_uploads,
 * then mark the photo chore complete (no berries/minutes/cash).
 */
class ChorePhotoActivity : AppCompatActivity() {

    private lateinit var titleView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var rewardView: TextView
    private lateinit var preview: ImageView
    private lateinit var cameraButton: Button
    private lateinit var submitButton: Button

    private var choreId: String = ""
    private var choreTitle: String = ""
    private var capturedBitmap: Bitmap? = null
    private var cameraImageFile: File? = null
    private var submitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chore_photo)

        choreId = intent.getStringExtra(EXTRA_CHORE_ID).orEmpty()
        choreTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        val rewardCash = intent.getDoubleExtra(EXTRA_REWARD_CASH, 0.0)

        titleView = findViewById(R.id.chorePhotoTitle)
        descriptionView = findViewById(R.id.chorePhotoDescription)
        rewardView = findViewById(R.id.chorePhotoReward)
        preview = findViewById(R.id.chorePhotoPreview)
        cameraButton = findViewById(R.id.chorePhotoCameraButton)
        submitButton = findViewById(R.id.chorePhotoSubmitButton)

        titleView.text = choreTitle.ifBlank { "Chore" }
        descriptionView.text = description.ifBlank { "Take a photo of the finished chore." }
        rewardView.text = "Reward: ${formatRewardCash(rewardCash)} (parent reviews)"

        cameraButton.setOnClickListener { requestCameraAndLaunch() }
        submitButton.setOnClickListener { submitPhoto() }
        findViewById<Button>(R.id.chorePhotoBackButton).setOnClickListener { finish() }

        if (choreId.isBlank()) {
            Toast.makeText(this, "This chore is missing an id.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun requestCameraAndLaunch() {
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                Toast.makeText(this, "Camera permission is required to take a chore photo.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchCamera() {
        try {
            val imageFile = File(
                getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),
                "chore_photo_${System.currentTimeMillis()}.jpg"
            )
            imageFile.parentFile?.mkdirs()
            cameraImageFile = imageFile
            val imageUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", imageFile)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val resInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    imageUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            startActivityForResult(intent, CAMERA_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "launchCamera failed", e)
            Toast.makeText(this, "Could not open camera.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAMERA_REQUEST_CODE) return
        val imageFile = cameraImageFile
        if (resultCode != RESULT_OK) {
            imageFile?.delete()
            cameraImageFile = null
            return
        }
        var bitmap: Bitmap? = null
        if (imageFile != null && imageFile.exists() && imageFile.length() > 0) {
            bitmap = decodeDownsampled(imageFile.absolutePath)
        }
        if (bitmap == null) {
            bitmap = data?.extras?.get("data") as? Bitmap
        }
        imageFile?.delete()
        cameraImageFile = null
        if (bitmap == null) {
            Toast.makeText(this, "Could not read the photo. Please try again.", Toast.LENGTH_LONG).show()
            return
        }
        capturedBitmap = bitmap
        preview.setImageBitmap(bitmap)
        preview.visibility = View.VISIBLE
        cameraButton.text = "Retake photo"
        submitButton.visibility = View.VISIBLE
        submitButton.isEnabled = true
    }

    private fun submitPhoto() {
        if (submitting) return
        val bitmap = capturedBitmap
        if (bitmap == null) {
            Toast.makeText(this, "Take a photo first.", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = SettingsManager.readProfile(this) ?: "AM"
        submitting = true
        submitButton.isEnabled = false
        cameraButton.isEnabled = false
        Toast.makeText(this, "Uploading photo...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val supabase = SupabaseInterface()
            if (!supabase.isConfigured()) {
                withContext(Dispatchers.Main) {
                    submitting = false
                    submitButton.isEnabled = true
                    cameraButton.isEnabled = true
                    Toast.makeText(this@ChorePhotoActivity, "Supabase is not configured.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val taskKey = imageTaskKey(choreId)
            val upload = supabase.invokeAfUpsertImageUpload(profile, taskKey, bitmapToBase64(bitmap))
            if (upload.isFailure) {
                withContext(Dispatchers.Main) {
                    submitting = false
                    submitButton.isEnabled = true
                    cameraButton.isEnabled = true
                    Toast.makeText(
                        this@ChorePhotoActivity,
                        "Could not save photo: ${upload.exceptionOrNull()?.message ?: "unknown error"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            val complete = supabase.invokeAfUpdatePhotoChore(profile, choreId)
            if (complete.isFailure) {
                withContext(Dispatchers.Main) {
                    submitting = false
                    submitButton.isEnabled = true
                    cameraButton.isEnabled = true
                    Toast.makeText(
                        this@ChorePhotoActivity,
                        "Photo saved but could not mark the chore done: ${complete.exceptionOrNull()?.message ?: "unknown error"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val result = Intent().apply {
                    putExtra(EXTRA_CHORE_ID, choreId)
                    putExtra(EXTRA_TITLE, choreTitle)
                    putExtra(EXTRA_ALREADY_COMPLETED, true)
                }
                setResult(RESULT_OK, result)
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "ChorePhotoActivity"
        const val EXTRA_CHORE_ID = "choreId"
        const val EXTRA_TITLE = "choreTitle"
        const val EXTRA_DESCRIPTION = "choreDescription"
        const val EXTRA_REWARD_CASH = "rewardCash"
        const val EXTRA_ALREADY_COMPLETED = "alreadyCompleted"
        const val REQUEST_CHORE_PHOTO = 1009
        private const val CAMERA_PERMISSION_REQUEST_CODE = 2101
        private const val CAMERA_REQUEST_CODE = 2102
        private const val MAX_IMAGE_EDGE = 1600

        fun formatRewardCash(amount: Double?): String {
            val value = amount ?: 0.0
            return if (kotlin.math.abs(value - value.toLong().toDouble()) < 0.001) {
                "$${value.toLong()}"
            } else {
                String.format(Locale.US, "$%.2f", value)
            }
        }

        fun imageTaskKey(choreId: String): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("America/Toronto")
            return "chore_${choreId}_${fmt.format(Date())}"
        }

        private fun decodeDownsampled(path: String): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            while (largest / sample > MAX_IMAGE_EDGE * 2 && sample < 32) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
            val w = decoded.width
            val h = decoded.height
            val edge = maxOf(w, h)
            if (edge <= MAX_IMAGE_EDGE) return decoded
            val scale = MAX_IMAGE_EDGE.toFloat() / edge
            return Bitmap.createScaledBitmap(decoded, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
        }

        private fun bitmapToBase64(bitmap: Bitmap): String {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        }
    }
}
