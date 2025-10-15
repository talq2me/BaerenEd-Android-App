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
            null -> Log.w(TAG, "Header button action is null")
            else -> Log.d(TAG, "Unknown header button action: $action")
        }
    }

    fun openPokemonCollection() {
        Log.d(TAG, "Opening Pokemon collection")
        val intent = Intent(this, PokemonActivity::class.java)
        startActivity(intent)
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
    val videoSequence: String? = null, // "exact" or "sequential"
    val video: String? = null // specific video name for "exact" mode
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
    val totalQuestions: Int? = null
)