package com.talq2me.baerened

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.*

// Import GameData for JSON parsing
import android.content.Intent
import com.talq2me.baerened.GameData

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var loadingProgressBar: ProgressBar
    lateinit var contentUpdateService: ContentUpdateService
    private lateinit var layout: Layout
    private var currentMainContent: MainContent? = null

    // UI elements for structured layout
    lateinit var headerLayout: LinearLayout
    lateinit var titleText: TextView
    lateinit var progressLayout: LinearLayout
    lateinit var progressText: TextView
    lateinit var progressBar: ProgressBar
    lateinit var sectionsContainer: LinearLayout
    lateinit var rewardLayout: LinearLayout
    lateinit var rewardTitle: TextView
    lateinit var rewardDescription: TextView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize TTS
        tts = TextToSpeech(this, this)

        // Initialize loading progress bar
        loadingProgressBar = findViewById(R.id.loadingProgressBar)

        // Initialize UI elements for structured layout
        headerLayout = findViewById(R.id.headerLayout)
        titleText = findViewById(R.id.titleText)
        progressLayout = findViewById(R.id.progressLayout)
        progressText = findViewById(R.id.progressText)
        progressBar = findViewById(R.id.progressBar)
        sectionsContainer = findViewById(R.id.sectionsContainer)
        rewardLayout = findViewById(R.id.rewardLayout)
        rewardTitle = findViewById(R.id.rewardTitle)
        rewardDescription = findViewById(R.id.rewardDescription)

        // Initialize content update service
        contentUpdateService = ContentUpdateService()

        // Initialize layout manager
        layout = Layout(this)

        // Check for child profile selection
        checkChildProfile()

        // Clean up any stale reward data on app start
        cleanupStaleRewardData()

        // Reset reward data for new day if needed
        resetRewardDataForNewDay()
    }

    private fun cleanupStaleRewardData() {
        try {
            val progressManager = DailyProgressManager(this)
            val pendingReward = progressManager.getPendingRewardData()

            if (pendingReward != null) {
                val (minutes, timestamp) = pendingReward
                val currentTime = System.currentTimeMillis()
                val oneHourInMillis = 60 * 60 * 1000L

                // Clear reward data if it's older than 1 hour
                if (currentTime - timestamp > oneHourInMillis) {
                    progressManager.clearPendingRewardData()
                    android.util.Log.d("MainActivity", "Cleaned up stale reward data: $minutes minutes from ${java.util.Date(timestamp)}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error cleaning up stale reward data", e)
        }
    }

    private fun resetRewardDataForNewDay() {
        try {
            val progressManager = DailyProgressManager(this)

            // Check if we need to reset for a new day
            val lastResetDate = progressManager.getLastResetDate()
            val currentDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())

            if (lastResetDate != currentDate) {
                // Clear any existing reward data for the new day
                progressManager.clearPendingRewardData()
                android.util.Log.d("MainActivity", "Reset reward data for new day: $currentDate")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error resetting reward data for new day", e)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh progress display and sections when returning to main screen
        layout.refreshProgressDisplay()
        layout.refreshHeaderButtons()
        // Also refresh the sections to update completion states
        currentMainContent?.let { content ->
            layout.displayContent(content)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    private fun checkChildProfile() {
        val prefs = getSharedPreferences("child_profile", MODE_PRIVATE)
        val child = prefs.getString("profile", null)

        if (child == null) {
            Handler(Looper.getMainLooper()).post {
                AlertDialog.Builder(this)
                    .setTitle("Who's using this tablet?")
                    .setMessage("Please select the child assigned to this device.")
                    .setPositiveButton("Child A") { _, _ ->
                        prefs.edit().putString("profile", "A").apply()
                        loadMainContent()
                    }
                    .setNegativeButton("Child B") { _, _ ->
                        prefs.edit().putString("profile", "B").apply()
                        loadMainContent()
                    }
                    .setCancelable(false)
                    .show()
            }
        } else {
            loadMainContent()
        }
    }


    private fun loadMainContent() {
        loadingProgressBar.visibility = View.VISIBLE
        titleText.text = "Loading..."
        headerLayout.visibility = View.GONE
        progressLayout.visibility = View.GONE
        sectionsContainer.visibility = View.GONE
        rewardLayout.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val content = contentUpdateService.fetchMainContent(this@MainActivity)
                if (content != null) {
                    withContext(Dispatchers.Main) {
                        displayContent(content)
                        loadingProgressBar.visibility = View.GONE
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        titleText.text = "Failed to load content. Please check your connection."
                        loadingProgressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading main content", e)
                withContext(Dispatchers.Main) {
                    titleText.text = "Error loading content: ${e.message}"
                    loadingProgressBar.visibility = View.GONE
                }
            }
        }
    }


    private fun displayContent(content: MainContent) {
        currentMainContent = content
        layout.displayContent(content)
    }

    fun getCurrentMainContent(): MainContent? {
        return currentMainContent
    }

    fun handleHeaderButtonClick(action: String?) {
        when (action) {
            "goBack" -> finish()
            "goHome" -> {
                // Reset to main screen
                val prefs = getSharedPreferences("child_profile", MODE_PRIVATE)
                prefs.edit().remove("profile").apply()
                loadMainContent()
            }
            "refreshPage" -> loadMainContent()
            "settings" -> openSettings()
            null -> Log.w(TAG, "Header button action is null")
            else -> Log.d(TAG, "Unknown header button action: $action")
        }
    }

    fun openPokemonCollection() {
        Log.d(TAG, "Opening Pokemon collection")
        val intent = Intent(this, PokemonActivity::class.java)
        startActivity(intent)
    }

    fun openSettings() {
        Log.d(TAG, "Opening settings")

        // Create a simple settings dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        // Initialize the progress manager
        val progressManager = DailyProgressManager(this)

        val currentPinEditText = dialogView.findViewById<EditText>(R.id.currentPinInput)
        val newPinEditText = dialogView.findViewById<EditText>(R.id.newPinInput)
        val confirmPinEditText = dialogView.findViewById<EditText>(R.id.confirmPinInput)
        val statusText = dialogView.findViewById<TextView>(R.id.settingsStatus)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val currentPin = currentPinEditText.text.toString()
                val newPin = newPinEditText.text.toString()
                val confirmPin = confirmPinEditText.text.toString()

                // Validate current PIN
                if (!progressManager.validateAdminPin(currentPin)) {
                    statusText.text = "Current PIN is incorrect!"
                    return@setPositiveButton
                }

                // Validate new PIN
                if (newPin.isEmpty()) {
                    statusText.text = "New PIN cannot be empty!"
                    return@setPositiveButton
                }

                if (newPin != confirmPin) {
                    statusText.text = "New PIN and confirmation don't match!"
                    return@setPositiveButton
                }

                if (newPin.length < 4) {
                    statusText.text = "PIN must be at least 4 digits!"
                    return@setPositiveButton
                }

                // Save new PIN
                progressManager.setAdminPin(newPin)
                statusText.text = "PIN updated successfully!"
                statusText.setTextColor(resources.getColor(android.R.color.holo_green_dark))

                // Clear inputs after successful save
                currentPinEditText.text.clear()
                newPinEditText.text.clear()
                confirmPinEditText.text.clear()
            }
            .setNegativeButton("Cancel") { _, _ ->
                // Do nothing
            }
            .create()

        dialog.show()
    }

    /**
     * Sends daily progress report via email
     */
    fun sendProgressReport(view: android.view.View) {
        try {
            val progressManager = DailyProgressManager(this)
            val timeTracker = TimeTracker(this)
            val reportGenerator = ReportGenerator(this)

            // Get main content for progress calculation (needed for progress calculation but not for task names)
            val mainContent = getCurrentMainContent() ?: MainContent()

            val progressReport = progressManager.getComprehensiveProgressReport(mainContent, timeTracker)

            // Get current child profile (A or B) and convert to AM or BM
            val currentKid = progressManager.getCurrentKid()
            val childName = if (currentKid == "A") "AM" else "BM"

            val report = reportGenerator.generateDailyReport(progressReport, childName, ReportGenerator.ReportFormat.TEXT)

            // Create email intent to Tiffany
            val emailIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf("tiffany.quinlan@gmail.com"))
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Daily Progress Report - $childName - ${progressReport.date}")
                putExtra(android.content.Intent.EXTRA_TEXT, report)
            }

            // Try to open Gmail directly using mailto URI (like the web app)
            val gmailIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("mailto:")
                putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf("tiffany.quinlan@gmail.com"))
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Daily Progress Report - $childName - ${progressReport.date}")
                putExtra(android.content.Intent.EXTRA_TEXT, report)
                setPackage("com.google.android.gm") // Gmail package name
            }

            try {
                // Try to open Gmail directly
                startActivity(gmailIntent)
            } catch (e: Exception) {
                // If Gmail isn't available, try other email apps using ACTION_SENDTO
                try {
                    val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                        data = android.net.Uri.parse("mailto:")
                        putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf("tiffany.quinlan@gmail.com"))
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Daily Progress Report - $childName - ${progressReport.date}")
                        putExtra(android.content.Intent.EXTRA_TEXT, report)
                    }
                    startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    // Final fallback - use chooser with generic share
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, report)
                    }
                    startActivity(android.content.Intent.createChooser(shareIntent, "Share Progress Report"))
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error sending progress report", e)
            Toast.makeText(this, "Error generating report", Toast.LENGTH_SHORT).show()
        }
    }








    private fun displayProfileSelection(content: MainContent) {
        layout.displayProfileSelection(content)
    }



    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    fun startGame(game: Game, gameContent: String? = null) {
        Log.d(TAG, "Starting game: ${game.title}")

        when (game.type) {
            "profile_selection" -> {
                selectProfile(game.id)
            }
            // Handle video sequences
            else -> {
                if (game.requiresRewardTime && !hasAvailableRewardTime()) {
                    showRewardTimeDialog()
                    return
                }

                // Check if this is a video sequence task by looking at the game object
                // We need to access the original task data, but for now we'll handle it in the calling code
                if (gameContent != null) {
                    launchGameActivity(game, gameContent)
                } else {
                    Toast.makeText(this, "${game.type} content not available", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun selectProfile(configUrl: String?) {
        if (configUrl == null) {
            Log.w(TAG, "Config URL is null")
            Toast.makeText(this, "Invalid profile configuration", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Selecting profile with config URL: $configUrl")

        val prefs = getSharedPreferences("child_profile", MODE_PRIVATE)

        when {
            configUrl.contains("AM_config.json") -> {
                prefs.edit().putString("profile", "A").apply()
                Toast.makeText(this, "Selected AM profile", Toast.LENGTH_SHORT).show()
            }
            configUrl.contains("BM_config.json") -> {
                prefs.edit().putString("profile", "B").apply()
                Toast.makeText(this, "Selected BM profile", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(this, "Unknown profile configuration", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Reload content with the selected profile
        loadMainContent()
    }

    private fun hasAvailableRewardTime(): Boolean {
        // This is a placeholder. In a real app, you'd check a database or file
        // for available reward time. For now, assume it's always available.
        return true
    }

    private fun showRewardTimeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reward Time Required")
            .setMessage("You need to earn more stars to unlock this game. Complete more tasks!")
            .setPositiveButton("OK") { _, _ ->
                // User clicked OK, do nothing or navigate back to main
            }
            .setCancelable(false)
            .show()
    }

    private fun startQuizGame(game: Game, gameContent: String?) {
        Log.d(TAG, "Starting quiz game: ${game.title}")
        if (gameContent != null) {
            launchGameActivity(game, gameContent)
        } else {
            Toast.makeText(this, "Quiz game content not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMemoryGame(game: Game, gameContent: String?) {
        Log.d(TAG, "Starting memory game: ${game.title}")
        if (gameContent != null) {
            launchGameActivity(game, gameContent)
        } else {
            Toast.makeText(this, "Memory game content not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startWordGame(game: Game, gameContent: String?) {
        Log.d(TAG, "Starting word game: ${game.title}")
        if (gameContent != null) {
            launchGameActivity(game, gameContent)
        } else {
            Toast.makeText(this, "Word game content not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMathGame(game: Game, gameContent: String?) {
        Log.d(TAG, "Starting math game: ${game.title}")
        if (gameContent != null) {
            launchGameActivity(game, gameContent)
        } else {
            Toast.makeText(this, "Math game content not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchGameActivity(game: Game, gameContent: String) {
        try {
            // Parse the content to get the actual number of questions
            val gson = Gson()
            val questions = gson.fromJson(gameContent, Array<GameData>::class.java)
            val totalQuestions = game.totalQuestions ?: questions.size

            // Determine if this is a required game
            val currentContent = getCurrentMainContent()
            val isRequired = currentContent?.sections?.any { section ->
                section.id == "required" && section.tasks?.any { it.launch == game.type } == true
            } ?: false

            val intent = Intent(this, com.talq2me.baerened.GameActivity::class.java).apply {
                putExtra("GAME_CONTENT", gameContent)
                putExtra("GAME_TYPE", game.type)
                putExtra("GAME_TITLE", game.title)
                putExtra("TOTAL_QUESTIONS", totalQuestions)
                putExtra("GAME_STARS", game.estimatedTime) // Use estimatedTime as stars
                putExtra("IS_REQUIRED_GAME", isRequired)
                putExtra("BLOCK_OUTLINES", game.blockOutlines)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing game content for ${game.type}", e)
            Toast.makeText(this, "Error loading game content", Toast.LENGTH_SHORT).show()
        }
    }
}

// Simple data class to hold the JSON content
data class MainContent(
    val version: String? = null,
    val title: String? = null,
    val header: Header? = null,
    val progress: Progress? = null,
    val sections: List<Section>? = null,
    val reward: Reward? = null,
    val buttons: List<ProfileButton>? = null
)

data class Header(
    val buttons: List<Button>? = null
)

data class Button(
    val label: String? = null,
    val action: String? = null
)

data class Progress(
    val starsEarned: Int? = null,
    val starsGoal: Int? = null,
    val message: String? = null
)

data class Section(
    val id: String? = null,
    val title: String? = null,
    val description: String? = null,
    val tasks: List<Task>? = null,
    val items: List<ChecklistItem>? = null
)

data class Task(
    val title: String? = null,
    val launch: String? = null,
    val stars: Int? = null,
    val totalQuestions: Int? = null,
    val videoSequence: String? = null, // "exact", "sequential", or "playlist"
    val video: String? = null, // specific video name for "exact" mode
    val playlistId: String? = null, // playlist ID for "playlist" mode
    val blockOutlines: Boolean? = null
)

data class ChecklistItem(
    val label: String? = null,
    val stars: Int? = null,
    val done: Boolean? = null,
    val id: String? = null
)

data class Reward(
    val title: String? = null,
    val description: String? = null,
    val unlocked15: Boolean? = null,
    val unlocked30: Boolean? = null,
    val unlocked45: Boolean? = null,
    val unlocked60: Boolean? = null,
    val action: String? = null
)

data class ProfileButton(
    val title: String? = null,
    val icon: String? = null,
    val configUrl: String? = null
)

data class Game(
    val id: String,
    val title: String,
    val description: String,
    val type: String,
    val iconUrl: String,
    val requiresRewardTime: Boolean,
    val difficulty: String,
    val estimatedTime: Int,
    val totalQuestions: Int? = null,
    val blockOutlines: Boolean = false
)