package com.talq2me.baerened

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.TimeUnit

class FrenchBookReaderActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "FrenchBookReader"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
        private const val CAMERA_REQUEST_CODE = 101
        private const val STORAGE_DIR = "french_book_pages"
    }
    
    private lateinit var cameraButton: Button
    private lateinit var clearAllButton: Button
    private lateinit var finishedButton: Button
    private lateinit var imagesContainer: LinearLayout
    
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val capturedImages = mutableListOf<ImageData>()
    private val translationMap = mutableMapOf<String, String>()
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    data class ImageData(
        val filePath: String,
        val timestamp: Long
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_french_book_reader)
        
        cameraButton = findViewById(R.id.cameraButton)
        clearAllButton = findViewById(R.id.clearAllButton)
        finishedButton = findViewById(R.id.finishedButton)
        imagesContainer = findViewById(R.id.imagesContainer)
        
        // Load translation dictionary
        loadTranslationDictionary()
        
        // Load saved images
        loadSavedImages()
        
        // Setup button listeners
        cameraButton.setOnClickListener {
            if (checkCameraPermission()) {
                launchCamera()
            } else {
                requestCameraPermission()
            }
        }
        
        clearAllButton.setOnClickListener {
            showClearAllConfirmation()
        }
        
        finishedButton.setOnClickListener {
            finish()
        }
        
        // Update UI based on loaded images
        updateUI()
    }
    
    private fun loadTranslationDictionary() {
        try {
            val inputStream = assets.open("data/translation.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val translations = Gson().fromJson(jsonString, Array<TranslationItem>::class.java)
            
            // Build word-to-word translation map from sentence translations
            translations.forEach { item ->
                val frenchText = item.prompt?.text?.lowercase() ?: ""
                val englishText = item.correctChoices?.firstOrNull()?.text?.lowercase() ?: ""
                
                // Extract individual words and create mappings
                val frenchWords = frenchText.split(Regex("[\\s.,!?;:()]+")).filter { it.isNotBlank() }
                val englishWords = englishText.split(Regex("[\\s.,!?;:()]+")).filter { it.isNotBlank() }
                
                // Simple word mapping (can be enhanced with better NLP)
                frenchWords.forEachIndexed { index, frenchWord ->
                    val cleanFrench = frenchWord.trim().lowercase().removeSuffix("s").removeSuffix("e")
                    val englishWord = if (index < englishWords.size) {
                        englishWords[index].trim().lowercase()
                    } else {
                        // Try to find a matching word
                        englishWords.firstOrNull() ?: ""
                    }
                    if (cleanFrench.isNotBlank() && englishWord.isNotBlank()) {
                        translationMap[cleanFrench] = englishWord
                    }
                }
            }
            
            Log.d(TAG, "Loaded ${translationMap.size} word translations")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading translation dictionary", e)
        }
    }
    
    private fun loadSavedImages() {
        try {
            val storageDir = File(filesDir, STORAGE_DIR)
            if (!storageDir.exists()) {
                storageDir.mkdirs()
                return
            }
            
            val imageFiles = storageDir.listFiles { _, name ->
                name.endsWith(".jpg") || name.endsWith(".png")
            }?.sortedBy { it.name } ?: emptyList()
            
            capturedImages.clear()
            imageFiles.forEach { file ->
                capturedImages.add(ImageData(file.absolutePath, file.lastModified()))
            }
            
            Log.d(TAG, "Loaded ${capturedImages.size} saved images")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved images", e)
        }
    }
    
    private fun saveImage(bitmap: Bitmap): String? {
        return try {
            val storageDir = File(filesDir, STORAGE_DIR)
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }
            
            val timestamp = System.currentTimeMillis()
            val imageFile = File(storageDir, "page_$timestamp.jpg")
            
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            
            imageFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error saving image", e)
            null
        }
    }
    
    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
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
                Toast.makeText(this, "Camera permission is required to take pictures", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun launchCamera() {
        val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, CAMERA_REQUEST_CODE)
        } else {
            Toast.makeText(this, "No camera app available", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            val imageBitmap = data?.extras?.get("data") as? Bitmap
            if (imageBitmap != null) {
                // Save the image
                val savedPath = saveImage(imageBitmap)
                if (savedPath != null) {
                    capturedImages.add(ImageData(savedPath, System.currentTimeMillis()))
                    addImageToContainer(imageBitmap, savedPath)
                    updateUI()
                } else {
                    Toast.makeText(this, "Error saving image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun addImageToContainer(bitmap: Bitmap, filePath: String) {
        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 32)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setImageBitmap(bitmap)
        }
        
        // Make image clickable for word translation
        imageView.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val clickX = event.x
                val clickY = event.y
                performOCRAndTranslate(bitmap, clickX, clickY, view as ImageView)
                true
            } else {
                false
            }
        }
        
        imagesContainer.addView(imageView)
    }
    
    private fun performOCRAndTranslate(bitmap: Bitmap, clickX: Float, clickY: Float, imageView: ImageView) {
        scope.launch {
            try {
                // Show loading
                Toast.makeText(this@FrenchBookReaderActivity, "Processing...", Toast.LENGTH_SHORT).show()
                
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                
                // Perform OCR
                val result = withContext(Dispatchers.IO) {
                    val task = textRecognizer.process(inputImage)
                    Tasks.await(task, 30, TimeUnit.SECONDS)
                }
                
                // Find the word at the click position
                val clickedWord = findWordAtPosition(result, clickX, clickY, imageView)
                
                if (clickedWord != null) {
                    // Look up translation
                    val translation = findTranslation(clickedWord)
                    showTranslationDialog(clickedWord, translation)
                } else {
                    Toast.makeText(this@FrenchBookReaderActivity, "No word found at that location", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing OCR", e)
                Toast.makeText(this@FrenchBookReaderActivity, "Error processing image: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun findWordAtPosition(
        result: com.google.mlkit.vision.text.Text,
        clickX: Float,
        clickY: Float,
        imageView: ImageView
    ): String? {
        // Get image dimensions
        val imageWidth = imageView.drawable?.intrinsicWidth ?: return null
        val imageHeight = imageView.drawable?.intrinsicHeight ?: return null
        
        // Calculate scale factors
        val scaleX = imageWidth.toFloat() / imageView.width
        val scaleY = imageHeight.toFloat() / imageView.height
        
        // Convert click coordinates to image coordinates
        val imageX = clickX * scaleX
        val imageY = clickY * scaleY
        
        // Find the text block/line/word at this position
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val boundingBox = element.boundingBox
                    if (boundingBox != null) {
                        if (imageX >= boundingBox.left && imageX <= boundingBox.right &&
                            imageY >= boundingBox.top && imageY <= boundingBox.bottom) {
                            return element.text.trim()
                        }
                    }
                }
            }
        }
        
        return null
    }
    
    private fun findTranslation(word: String): String? {
        val cleanWord = word.lowercase()
            .trim()
            .removeSuffix("s") // Remove plural
            .removeSuffix("e") // Remove feminine
            .removeSuffix("es") // Remove plural feminine
            .removeSuffix("é")
            .removeSuffix("ée")
            .removeSuffix("ées")
            .removeSuffix("er")
            .removeSuffix("ir")
            .removeSuffix("re")
        
        // Try exact match first
        translationMap[cleanWord]?.let { return it }
        
        // Try with the original word
        translationMap[word.lowercase()]?.let { return it }
        
        // Try partial matches
        for ((french, english) in translationMap) {
            if (cleanWord.contains(french) || french.contains(cleanWord)) {
                return english
            }
        }
        
        return null
    }
    
    private fun showTranslationDialog(frenchWord: String, translation: String?) {
        val message = if (translation != null) {
            "$frenchWord = $translation"
        } else {
            "Translation not found for: $frenchWord"
        }
        
        AlertDialog.Builder(this)
            .setTitle("Translation")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showClearAllConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Images")
            .setMessage("Are you sure you want to delete all captured images? This cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                clearAllImages()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun clearAllImages() {
        try {
            // Delete all image files
            val storageDir = File(filesDir, STORAGE_DIR)
            storageDir.listFiles()?.forEach { it.delete() }
            
            // Clear the list
            capturedImages.clear()
            
            // Clear the UI
            imagesContainer.removeAllViews()
            
            // Update UI
            updateUI()
            
            Toast.makeText(this, "All images cleared", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing images", e)
            Toast.makeText(this, "Error clearing images", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateUI() {
        // Show/hide Clear All button
        clearAllButton.visibility = if (capturedImages.isNotEmpty()) View.VISIBLE else View.GONE
        
        // Show/hide Finished button
        finishedButton.visibility = if (capturedImages.isNotEmpty()) View.VISIBLE else View.GONE
        
        // Reload images if container is empty but we have saved images
        if (imagesContainer.childCount == 0 && capturedImages.isNotEmpty()) {
            loadImagesIntoContainer()
        }
    }
    
    private fun loadImagesIntoContainer() {
        imagesContainer.removeAllViews()
        
        capturedImages.forEach { imageData ->
            try {
                val imageFile = File(imageData.filePath)
                if (imageFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                    if (bitmap != null) {
                        addImageToContainer(bitmap, imageData.filePath)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image: ${imageData.filePath}", e)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        textRecognizer.close()
    }
    
    // Data classes for JSON parsing
    private data class TranslationItem(
        val prompt: Prompt?,
        val correctChoices: List<Choice>?
    )
    
    private data class Prompt(
        val text: String?
    )
    
    private data class Choice(
        val text: String?
    )
}

