package com.talq2me.baerened

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import org.json.JSONArray
import com.google.gson.Gson
import kotlinx.coroutines.*

class BattleHubActivity : AppCompatActivity() {

    private lateinit var cloudStorageManager: CloudStorageManager
    private var loadingDialog: android.app.ProgressDialog? = null

    data class ParsedPokemon(
        val prefix: Int,
        val pokenum: Int,
        val name: String,
        val filename: String,
        val shiny: Boolean
    )

    private lateinit var headerTitle: TextView
    private lateinit var coinCount: TextView
    private lateinit var berryCountDisplay: TextView
    private lateinit var minuteCount: TextView
    private lateinit var pokedexContent: HorizontalScrollView
    private lateinit var pokedexGrid: LinearLayout
    private lateinit var playerPokemonSprite: ImageView
    private lateinit var playerPokemonName: TextView
    private lateinit var playerHP: ProgressBar
    private lateinit var playerPower: ProgressBar
    private lateinit var bossPokemonSprite: ImageView
    private lateinit var bossPokemonName: TextView
    private lateinit var bossHP: ProgressBar
    private lateinit var bossPower: ProgressBar
    private lateinit var berryFill: LinearLayout
    private lateinit var berryCount: TextView
    private lateinit var battleButton: Button
    private lateinit var victoryOverlay: FrameLayout
    private lateinit var victoryPokemonSprite: ImageView
    private lateinit var victoryPokemonName: TextView
    private lateinit var victoryMessage: TextView
    
    private var isPokedexExpanded = false // Default to collapsed
    private var selectedPokemonId: Int? = null
    private var unlockedPokemonCount = 0
    private var earnedStars = 0
    private var totalStars = 0
    
    private var mainContentJson: String? = null
    
    // Battle state
    private var playerHealth = 100
    private var bossHealth = 100
    private var playerPowerValue = 0
    private var bossPowerValue = 100
    private var isBattling = false
    private var currentBossPokemon: ParsedPokemon? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("BattleHubActivity", "onCreate() CALLED")
        setContentView(R.layout.activity_battle_hub)
        
        // Initialize managers
        cloudStorageManager = CloudStorageManager(this)
        
        // Initialize views immediately so onResume() can safely update them
        initializeViews()
        
        val profile = SettingsManager.readProfile(this) ?: "AM"
        
        // Run daily_reset_process() and then cloud_sync() according to requirements
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Show loading spinner on main thread
                withContext(Dispatchers.Main) {
                    showLoadingSpinner("Syncing progress...")
                }
                
                // Perform daily reset process and cloud sync
                val resetAndSyncManager = DailyResetAndSyncManager(this@BattleHubActivity)
                resetAndSyncManager.dailyResetProcessAndSync(profile)
                
                android.util.Log.d("BattleHubActivity", "Daily reset and sync completed for profile: $profile")
                
                // Hide loading spinner and finish loading
                withContext(Dispatchers.Main) {
                    hideLoadingSpinner()
                    finishLoadingBattleHub()
                }
            } catch (e: Exception) {
                android.util.Log.e("BattleHubActivity", "Error during daily reset and sync", e)
                // Hide loading spinner even on error
                withContext(Dispatchers.Main) {
                    hideLoadingSpinner()
                    finishLoadingBattleHub()
                }
            }
        }
    }
    
    private fun showLoadingSpinner(message: String) {
        runOnUiThread {
            loadingDialog = android.app.ProgressDialog(this@BattleHubActivity).apply {
                setMessage(message)
                setCancelable(false)
                show()
            }
        }
    }
    
    private fun hideLoadingSpinner() {
        runOnUiThread {
            loadingDialog?.dismiss()
            loadingDialog = null
        }
    }
    
    private fun finishLoadingBattleHub() {
        // Get MainContent from Intent extra if available
        mainContentJson = intent.getStringExtra("mainContentJson")
        // Fallback to ContentUpdateService if not in Intent
        if (mainContentJson == null) {
            mainContentJson = ContentUpdateService().getCachedMainContent(this)
        }
        
        // Set arena background image
        window.setBackgroundDrawableResource(android.R.color.transparent)
        val root = window.decorView.rootView
        try {
            val bitmap = android.graphics.BitmapFactory.decodeStream(assets.open("images/arena.png"))
            val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap)
            root.background = drawable
        } catch (e: Exception) {
            // Fallback to gradient if image not found
            android.util.Log.e("BattleHubActivity", "Could not load arena.png", e)
            root.setBackgroundColor(0xFF667EEA.toInt())
        }
        
        // Views are already initialized in onCreate(), just finish setup
        updateTitle()
        setupAnimations()
        setupClickListeners()
        
        // Set Pokedex to collapsed initially
        val pokedexToggle: TextView = findViewById(R.id.pokedexToggle)
        pokedexContent.visibility = View.GONE
        pokedexContent.alpha = 0f
        pokedexToggle.text = "▶"
        pokedexToggle.rotation = -90f
        
        loadPokemonData()
        
        // Update berry meter after view is laid out (to get proper parent width)
        berryFill.post {
            updateBerryMeter()
            updateEarnButtonsState()
            updateCountsDisplay()
            updatePokedexTitle()
            setupHeaderButtons() // Setup header buttons after views are ready
        }
        
        // Also update on resume to refresh when returning from training map
        updateBerryMeter()
        updateEarnButtonsState()
        updateCountsDisplay()
        updatePokedexTitle()
        setupHeaderButtons()
    }
    
    private fun updateTitle() {
        val version = try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: packageInfo.versionCode.toString()
        } catch (e: Exception) {
            "?"
        }
        val profile = SettingsManager.readProfile(this) ?: "AM"
        val displayNames = SettingsManager.getProfileDisplayNames()
        val profileDisplay = displayNames[profile] ?: profile
        headerTitle.text = "Pokemon Battle Hub v$version - $profileDisplay"
    }
    
    private fun updatePokedexTitle() {
        val progressManager = DailyProgressManager(this)
        val unlockedCount = progressManager.getUnlockedPokemonCount()
        pokedexTitle.text = "📖 My Pokedex    Unlocked: $unlockedCount"
    }
    
    private fun updateCountsDisplay() {
        android.util.Log.d("BattleHubActivity", "updateCountsDisplay() CALLED")

        // Debug: Check what data is currently in SharedPreferences
        val progressPrefs = getSharedPreferences("daily_progress_prefs", MODE_PRIVATE)
        val completedTasksJson = progressPrefs.getString("completed_tasks", "{}")
        val completedTaskNamesJson = progressPrefs.getString("completed_task_names", "{}")
        android.util.Log.d("BattleHubActivity", "DEBUG: completed_tasks = $completedTasksJson")
        android.util.Log.d("BattleHubActivity", "DEBUG: completed_task_names = $completedTaskNamesJson")

        // Use EXACTLY the same method as Layout.kt updateProgressDisplay()
        val progressManager = DailyProgressManager(this)
        
        // Reload mainContentJson if it's null or empty
        if (mainContentJson == null || mainContentJson!!.isEmpty()) {
            android.util.Log.d("BattleHubActivity", "updateCountsDisplay: mainContentJson is null/empty, reloading from cache")
            mainContentJson = ContentUpdateService().getCachedMainContent(this)
            android.util.Log.d("BattleHubActivity", "updateCountsDisplay: Reloaded mainContentJson, is ${if (mainContentJson != null && mainContentJson!!.isNotEmpty()) "NOT NULL (length=${mainContentJson!!.length})" else "NULL or EMPTY"}")
        }
        
        val jsonString = mainContentJson ?: ContentUpdateService().getCachedMainContent(this)
        android.util.Log.d("BattleHubActivity", "updateCountsDisplay: jsonString is ${if (jsonString != null) "NOT NULL (length=${jsonString.length})" else "NULL"}")
        
        val currentContent = try {
            if (jsonString != null && jsonString.isNotEmpty()) {
                Gson().fromJson(jsonString, MainContent::class.java)
            } else {
                android.util.Log.w("BattleHubActivity", "updateCountsDisplay: jsonString is null or empty")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("BattleHubActivity", "Error loading MainContent in updateCountsDisplay", e)
            null
        }
        
        android.util.Log.d("BattleHubActivity", "updateCountsDisplay: currentContent is ${if (currentContent != null) "NOT NULL" else "NULL"}")
        
        if (currentContent != null) {
            // Get current progress with accurate calculation - EXACTLY like Layout.kt
            val progressData = progressManager.getCurrentProgressWithTotals(currentContent)
            val earnedCoins = progressData.first.first
            val totalCoins = progressData.first.second
            val earnedStars = progressData.second.first
            val totalStars = progressData.second.second
            
            // Get actual banked reward minutes (not recalculated from stars) - EXACTLY like Layout.kt
            val rewardMinutes = progressManager.getBankedRewardMinutes()
            
            // Debug logging
            val completedTasks = progressManager.getCompletedTasksMap()
            android.util.Log.d("BattleHubActivity", "updateCountsDisplay: earnedCoins=$earnedCoins, earnedStars=$earnedStars")
            android.util.Log.d("BattleHubActivity", "updateCountsDisplay: completedTasks keys=${completedTasks.keys}")
            android.util.Log.d("BattleHubActivity", "updateCountsDisplay: About to update UI with coins=$earnedCoins, berries=$earnedStars, minutes=$rewardMinutes")
            
            // Load icon config (same as Layout.kt)
            data class IconConfig(val starsIcon: String = "🍍", val coinsIcon: String = "🪙")
            val icons = try {
                val inputStream = assets.open("config/icon_config.json")
                val configJson = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()
                Gson().fromJson(configJson, IconConfig::class.java) ?: IconConfig()
            } catch (e: Exception) {
                IconConfig()
            }
            
            // Display coins and berries - coins from required tasks, berries show all earned
            coinCount.text = "$earnedCoins ${icons.coinsIcon}"
            // Simple: just get earned berries directly
            val allEarnedStars = progressManager.getEarnedBerries()
            android.util.Log.d("BattleHubActivity", "updateCountsDisplay: earnedBerries=$allEarnedStars")
            berryCountDisplay.text = "$allEarnedStars ${icons.starsIcon}"
            minuteCount.text = "$rewardMinutes ⏱️"
            android.util.Log.d("BattleHubActivity", "updateCountsDisplay: UI updated - coinCount.text=${coinCount.text}, berryCountDisplay.text=${berryCountDisplay.text}, minuteCount.text=${minuteCount.text}")
            android.util.Log.d("BattleHubActivity", "DEBUG: getAllEarnedStars returned $allEarnedStars for currentContent=${currentContent != null}")
            android.util.Log.d("BattleHubActivity", "DEBUG: progressManager.getCompletedTasksMap() = ${progressManager.getCompletedTasksMap()}")
        } else {
            // Fallback - use cached values but ensure they're calculated correctly - EXACTLY like Layout.kt
            android.util.Log.w("BattleHubActivity", "updateCountsDisplay: currentContent is NULL, using fallback")
            val progressData = progressManager.getCurrentProgressWithTotals()
            val earnedCoins = progressData.first.first
            val earnedStars = progressData.second.first
            val rewardMinutes = progressManager.getBankedRewardMinutes()
            
            android.util.Log.d("BattleHubActivity", "updateCountsDisplay (fallback): earnedCoins=$earnedCoins, earnedStars=$earnedStars, rewardMinutes=$rewardMinutes")
            
            coinCount.text = "$earnedCoins 🪙"
            berryCountDisplay.text = "$earnedStars 🍍"
            minuteCount.text = "$rewardMinutes ⏱️"
        }
    }
    
    
    private lateinit var headerButtonsLeft: LinearLayout
    private lateinit var headerButtonsRight: LinearLayout
    private lateinit var earnBerriesButton: Button
    private lateinit var earnExtraBerriesButton: Button
    private lateinit var bonusTrainingButton: Button
    private lateinit var choresButton: Button
    private lateinit var pokedexTitle: TextView
    
    private fun initializeViews() {
        headerTitle = findViewById(R.id.headerTitle)
        coinCount = findViewById(R.id.coinCount)
        berryCountDisplay = findViewById(R.id.berryCountDisplay)
        minuteCount = findViewById(R.id.minuteCount)
        headerButtonsLeft = findViewById(R.id.headerButtonsLeft)
        headerButtonsRight = findViewById(R.id.headerButtonsRight)
        pokedexTitle = findViewById(R.id.pokedexTitle)
        pokedexContent = findViewById(R.id.pokedexContent)
        pokedexGrid = findViewById(R.id.pokedexGrid)
        playerPokemonSprite = findViewById(R.id.playerPokemonSprite)
        playerPokemonName = findViewById(R.id.playerPokemonName)
        playerHP = findViewById(R.id.playerHP)
        playerPower = findViewById(R.id.playerPower)
        bossPokemonSprite = findViewById(R.id.bossPokemonSprite)
        bossPokemonName = findViewById(R.id.bossPokemonName)
        bossHP = findViewById(R.id.bossHP)
        bossPower = findViewById(R.id.bossPower)
        berryFill = findViewById(R.id.berryFill)
        berryCount = findViewById(R.id.berryCount)
        battleButton = findViewById(R.id.battleButton)
        earnBerriesButton = findViewById(R.id.earnBerriesButton)
        earnExtraBerriesButton = findViewById(R.id.earnExtraBerriesButton)
        bonusTrainingButton = findViewById(R.id.bonusTrainingButton)
        choresButton = findViewById(R.id.choresButton)
        victoryOverlay = findViewById(R.id.victoryOverlay)
        victoryPokemonSprite = findViewById(R.id.victoryPokemonSprite)
        victoryPokemonName = findViewById(R.id.victoryPokemonName)
        victoryMessage = findViewById(R.id.victoryMessage)
        
        // Hide victory overlay initially
        victoryOverlay.visibility = View.GONE
        
        // Ensure sprites are visible
        playerPokemonSprite.visibility = View.VISIBLE
        bossPokemonSprite.visibility = View.VISIBLE
        
        // Setup berry meter styling
        val density = resources.displayMetrics.density
        berryFill.background = GradientDrawable().apply {
            setColor(0xFFFF6B6B.toInt())
            cornerRadius = 18 * density // Match the height (40dp - 6dp padding = 34dp, radius about half)
        }
        
        // Initialize berryFill width to 0
        val params = berryFill.layoutParams as FrameLayout.LayoutParams
        params.width = 0
        berryFill.layoutParams = params
    }
    
    private fun setupAnimations() {
        // Bounce animation for header title
        val bounceAnimator = ObjectAnimator.ofFloat(headerTitle, "translationY", 0f, -20f, 0f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = BounceInterpolator()
        }
        bounceAnimator.start()
        
        // Rotating VS text
        val vsDivider: TextView = findViewById(R.id.vsDivider)
        val rotateAnimator = ObjectAnimator.ofFloat(vsDivider, "rotation", 0f, 360f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        rotateAnimator.start()
        
        // Float animation for Pokemon sprites (like HTML battle hub)
        val density = resources.displayMetrics.density
        val floatDistance = (15 * density) // 15dp up and down, matching HTML
        
        // Player Pokemon float animation
        val playerFloatAnimator = ObjectAnimator.ofFloat(playerPokemonSprite, "translationY", 0f, -floatDistance, 0f).apply {
            duration = 3000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        playerFloatAnimator.start()
        
        // Boss Pokemon float animation (slightly offset for variation)
        val bossFloatAnimator = ObjectAnimator.ofFloat(bossPokemonSprite, "translationY", 0f, -floatDistance, 0f).apply {
            duration = 3000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 500 // Offset slightly so they don't move in sync
        }
        bossFloatAnimator.start()
    }
    
    private fun setupHeaderButtons() {
        val currentContent = try {
            var jsonString = mainContentJson
            if (jsonString == null || jsonString.isEmpty()) {
                jsonString = ContentUpdateService().getCachedMainContent(this)
            }
            if (jsonString != null && jsonString.isNotEmpty()) {
                Gson().fromJson(jsonString, MainContent::class.java)
            } else null
        } catch (e: Exception) {
            android.util.Log.e("BattleHubActivity", "Error loading content for header buttons", e)
            null
        }
        
        headerButtonsLeft.removeAllViews()
        headerButtonsRight.removeAllViews()
        headerButtonsLeft.visibility = View.VISIBLE
        headerButtonsRight.visibility = View.VISIBLE
        
        // Filter buttons to only show "askForTime" and "settings", exclude "openPokedex" and "openBattleHub"
        val filteredButtons = currentContent?.header?.buttons?.filter { button ->
            button.action == "askForTime" || button.action == "settings"
        } ?: emptyList()
        
        // Add "Ask for Time" button first
        val askForTimeButton = filteredButtons.find { it.action == "askForTime" }
        if (askForTimeButton != null || filteredButtons.isEmpty()) {
            val askForTimeBtn = android.widget.Button(this).apply {
                text = "⏰ Ask for Time"
                textSize = 12f
                setOnClickListener {
                    handleHeaderButtonClick("askForTime")
                }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (32 * resources.displayMetrics.density).toInt()  // Match settings button height
            ).apply {
                marginEnd = (6 * resources.displayMetrics.density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            background = getDrawable(R.drawable.button_rounded)
            setTextColor(android.graphics.Color.WHITE)
            setPadding((8 * resources.displayMetrics.density).toInt(), 
                       (4 * resources.displayMetrics.density).toInt(), 
                       (8 * resources.displayMetrics.density).toInt(), 
                       (4 * resources.displayMetrics.density).toInt())
            gravity = android.view.Gravity.CENTER
            }
            headerButtonsRight.addView(askForTimeBtn)
        }
        
        // Add Settings button (gear icon only, far right)
        val settingsBtn = android.widget.Button(this).apply {
            text = "⚙"
            textSize = 16f
            setOnClickListener {
                handleHeaderButtonClick("settings")
            }
            layoutParams = LinearLayout.LayoutParams(
                (32 * resources.displayMetrics.density).toInt(), // Match settings button width
                (32 * resources.displayMetrics.density).toInt()  // Match settings button height
            ).apply {
                marginEnd = (4 * resources.displayMetrics.density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            background = getDrawable(R.drawable.button_rounded)
            setTextColor(android.graphics.Color.WHITE)
            setPadding((4 * resources.displayMetrics.density).toInt(), 
                       (4 * resources.displayMetrics.density).toInt(), 
                       (4 * resources.displayMetrics.density).toInt(), 
                       (4 * resources.displayMetrics.density).toInt())
            gravity = android.view.Gravity.CENTER
        }
        headerButtonsRight.addView(settingsBtn)

        // Add cloud storage toggle button
        addCloudToggleButton()

        android.util.Log.d("BattleHubActivity", "Added header buttons (Ask for Time, Settings icon, Cloud toggle)")
    }

    private fun addCloudToggleButton() {
        val cloudStorageManager = CloudStorageManager(this)
        val isCloudEnabled = cloudStorageManager.isCloudStorageEnabled()

        val cloudButton = android.widget.Button(this).apply {
            text = if (isCloudEnabled) "☁️ Cloud ON" else "☁️ Cloud OFF"
            textSize = 16f
            setOnClickListener {
                toggleCloudStorage()
            }

            layoutParams = LinearLayout.LayoutParams(
                140.dpToPx(),
                (32 * resources.displayMetrics.density).toInt()  // Match settings button height
            ).apply {
                marginEnd = 12.dpToPx()
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            background = resources.getDrawable(
                if (isCloudEnabled) R.drawable.button_rounded_success else R.drawable.button_rounded
            )
            setTextColor(resources.getColor(android.R.color.white))
            setPadding(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
            gravity = android.view.Gravity.CENTER
        }
        headerButtonsRight.addView(cloudButton)
    }

    private fun toggleCloudStorage() {
        val isCurrentlyEnabled = cloudStorageManager.isCloudStorageEnabled()

        if (!cloudStorageManager.isConfigured()) {
            android.widget.Toast.makeText(this, "Supabase not configured. Add SUPABASE_URL and SUPABASE_KEY to local.properties and rebuild.", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        if (!isCurrentlyEnabled) {
            // Enable cloud storage
            cloudStorageManager.setCloudStorageEnabled(true)
            val profile = SettingsManager.readProfile(this) ?: "AM"
            lifecycleScope.launch(Dispatchers.IO) {
                val result = cloudStorageManager.syncIfEnabled(profile)
                runOnUiThread {
                    if (result.isSuccess) {
                        android.widget.Toast.makeText(this@BattleHubActivity, "Cloud storage enabled and synced", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(this@BattleHubActivity, "Cloud enabled but sync failed: ${result.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                    // Refresh UI to update button
                    setupHeaderButtons()
                }
            }
        } else {
            // Disable cloud storage
            cloudStorageManager.setCloudStorageEnabled(false)
            android.widget.Toast.makeText(this, "Cloud storage disabled", android.widget.Toast.LENGTH_SHORT).show()
            // Refresh UI to update button
            setupHeaderButtons()
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
    
    private fun handleHeaderButtonClick(action: String?) {
        when (action) {
            "settings" -> {
                val pinInput = android.widget.EditText(this).apply {
                    hint = "Enter Admin PIN"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    setPadding(50, 50, 50, 50)
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Admin Access")
                    .setMessage("Please enter the PIN to access settings.")
                    .setView(pinInput)
                    .setPositiveButton("Enter") { _, _ ->
                        val enteredPin = pinInput.text.toString()
                        val correctPin = SettingsManager.readPin(this) ?: "1981"
                        if (enteredPin == correctPin) {
                            showSettingsListDialog()
                        } else {
                            android.widget.Toast.makeText(this, "Incorrect PIN", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            "openPokedex" -> {
                val intent = Intent(this, PokemonActivity::class.java)
                startActivity(intent)
            }
            "askForTime" -> {
                val pinInput = android.widget.EditText(this).apply {
                    hint = "Enter Admin PIN"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    setPadding(50, 50, 50, 50)
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Admin Access")
                    .setMessage("Please enter the PIN to grant reward minutes.")
                    .setView(pinInput)
                    .setPositiveButton("Enter") { _, _ ->
                        val enteredPin = pinInput.text.toString()
                        val correctPin = SettingsManager.readPin(this) ?: "1981"
                        if (enteredPin == correctPin) {
                            showRewardMinutesInputDialog()
                        } else {
                            android.widget.Toast.makeText(this, "Incorrect PIN", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
    
    private fun showSettingsListDialog() {
        val settingsOptions = arrayOf("Old Style Main", "Change Profile", "Change PIN", "Change Parent Email", "Send Progress Report", "Reset All Progress", "Unlock Pokemon", "Clear config cache")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(settingsOptions) { _, which ->
                when (which) {
                    0 -> {
                        // Navigate to old MainActivity
                        val intent = Intent(this, MainActivity::class.java)
                        startActivity(intent)
                    }
                    1 -> showChangeProfileDialog()
                    2 -> showChangePinDialog()
                    3 -> showChangeEmailDialog()
                    4 -> sendProgressReportInternal()
                    5 -> showResetProgressConfirmationDialog()
                    6 -> showUnlockPokemonDialog()
                    7 -> clearConfigCache()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearConfigCache() {
        ContentUpdateService().clearCache(this)
        android.widget.Toast.makeText(this, "Config cache cleared. Return to main screen and pull down to refresh to load the latest tasks.", android.widget.Toast.LENGTH_LONG).show()
    }
    
    private fun showUnlockPokemonDialog() {
        // PIN already verified to access settings, so directly show unlock count dialog
        showUnlockCountDialog()
    }
    
    private fun showUnlockCountDialog() {
        val unlockCountInput = android.widget.EditText(this).apply {
            hint = "Number of Pokemon to Unlock"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(50, 50, 50, 50)
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Unlock Pokemon")
            .setMessage("Enter how many Pokemon to unlock:")
            .setView(unlockCountInput)
            .setPositiveButton("Unlock") { _, _ ->
                val countString = unlockCountInput.text.toString()
                if (countString.isBlank()) {
                    android.widget.Toast.makeText(this, "Please enter a number!", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                val count = countString.toIntOrNull()
                if (count == null || count <= 0) {
                    android.widget.Toast.makeText(this, "Please enter a valid number greater than 0!", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                val progressManager = DailyProgressManager(this)
                val previousCount = progressManager.getUnlockedPokemonCount()
                progressManager.unlockPokemon(count)
                val newCount = progressManager.getUnlockedPokemonCount()
                
                android.util.Log.d("BattleHubActivity", "Unlocked $count Pokemon via admin. Previous: $previousCount, New total: $newCount")
                
                // Sync to cloud
                val profile = SettingsManager.readProfile(this) ?: "AM"
                lifecycleScope.launch {
                    try {
                        val result = cloudStorageManager.saveIfEnabled(profile)
                        if (result.isSuccess) {
                            android.util.Log.d("BattleHubActivity", "Successfully synced Pokemon unlock to cloud")
                        } else {
                            android.util.Log.w("BattleHubActivity", "Failed to sync Pokemon unlock to cloud: ${result.exceptionOrNull()?.message}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("BattleHubActivity", "Error syncing Pokemon unlock to cloud", e)
                    }
                }
                
                // Refresh Pokemon data to show new unlocks
                loadPokemonData()
                
                android.widget.Toast.makeText(this, "Unlocked $count Pokemon! Total: $newCount", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showChangeProfileDialog() {
        val availableProfiles = SettingsManager.getAvailableProfiles(this)
        val displayNames = SettingsManager.getProfileDisplayNames()
        val currentProfile = SettingsManager.readProfile(this)

        // Create display names for available profiles
        val profileDisplayNames = availableProfiles.map { profileId ->
            displayNames[profileId] ?: "Profile $profileId"
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select User Profile")
            .setItems(profileDisplayNames) { _, which ->
                val selectedProfile = availableProfiles[which]
                if (currentProfile != selectedProfile) {
                    SettingsManager.writeProfile(this, selectedProfile)
                    finishAffinity()
                    startActivity(Intent(this, BattleHubActivity::class.java))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showChangePinDialog() {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }
        val currentPinInput = android.widget.EditText(this).apply {
            hint = "Enter current PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        dialogLayout.addView(currentPinInput)
        val newPinInput = android.widget.EditText(this).apply {
            hint = "Enter new PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        dialogLayout.addView(newPinInput)
        val confirmPinInput = android.widget.EditText(this).apply {
            hint = "Confirm new PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        dialogLayout.addView(confirmPinInput)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Change PIN")
            .setView(dialogLayout)
            .setPositiveButton("Save") { _, _ ->
                val currentPin = currentPinInput.text.toString()
                val newPin = newPinInput.text.toString()
                val confirmPin = confirmPinInput.text.toString()
                val correctPin = SettingsManager.readPin(this) ?: "1981"
                if (currentPin != correctPin) {
                    android.widget.Toast.makeText(this, "Current PIN is incorrect!", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newPin.isEmpty() || newPin != confirmPin) {
                    android.widget.Toast.makeText(this, "New PINs do not match or are empty", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newPin.length < 4) {
                    android.widget.Toast.makeText(this, "PIN must be at least 4 digits!", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                SettingsManager.writePin(this, newPin)
                android.widget.Toast.makeText(this, "PIN changed successfully", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showChangeEmailDialog() {
        val currentEmail = SettingsManager.readEmail(this) ?: ""
        val input = android.widget.EditText(this).apply {
            hint = "Parent Email Address"
            setText(currentEmail)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val container = FrameLayout(this).apply {
            setPadding(50, 20, 50, 20)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Change Parent Email")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newEmail = input.text.toString().trim()
                if (newEmail.isNotEmpty() && android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
                    SettingsManager.writeEmail(this, newEmail)
                    android.widget.Toast.makeText(this, "Email saved successfully", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "Please enter a valid email address", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun sendProgressReportInternal() {
        try {
            val progressManager = DailyProgressManager(this)
            val timeTracker = TimeTracker(this)
            val reportGenerator = ReportGenerator(this)
            val currentContent = try {
                val jsonString = mainContentJson ?: ContentUpdateService().getCachedMainContent(this)
                if (jsonString != null && jsonString.isNotEmpty()) {
                    Gson().fromJson(jsonString, MainContent::class.java)
                } else MainContent()
            } catch (e: Exception) {
                MainContent()
            }
            val progressReport = progressManager.getComprehensiveProgressReport(currentContent, timeTracker)
            val currentKid = progressManager.getCurrentKid()
            val childName = currentKid
            val report = reportGenerator.generateDailyReport(progressReport, childName, ReportGenerator.ReportFormat.EMAIL)
            val parentEmail = SettingsManager.readEmail(this)
            if (parentEmail.isNullOrBlank()) {
                android.widget.Toast.makeText(this, "Please set parent email in settings first", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            val subject = "Daily Progress Report - $childName - ${progressReport.date}"
            val emailIntent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(parentEmail))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, report)
            }
            val chooser = Intent.createChooser(emailIntent, "Send Progress Report via Email")
            startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("BattleHubActivity", "Error sending progress report", e)
            android.widget.Toast.makeText(this, "Error generating report", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showResetProgressConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Reset All Progress")
            .setMessage("Are you sure you want to reset today's progress? This will clear:\n\n" +
                    "• All completed tasks\n" +
                    "• All time tracking data\n" +
                    "• All reward minutes\n\n" +
                    "This is the same as the daily reset. Game progress, Pokemon unlocks, and video progress will be preserved.")
            .setPositiveButton("Reset All") { _, _ ->
                try {
                    val profile = SettingsManager.readProfile(this) ?: "AM"
                    val resetAndSyncManager = DailyResetAndSyncManager(this)
                    
                    // Set last_reset to yesterday to force reset on next screen load
                    lifecycleScope.launch(Dispatchers.IO) {
                        resetAndSyncManager.setLastResetToYesterday(profile)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(this@BattleHubActivity, "Progress will be reset on next screen load", android.widget.Toast.LENGTH_LONG).show()
                            updateBerryMeter()
                            updateEarnButtonsState()
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BattleHubActivity", "Error resetting progress", e)
                    android.widget.Toast.makeText(this, "Error resetting progress: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showRewardMinutesInputDialog() {
        val minutesInput = android.widget.EditText(this).apply {
            hint = "Reward Minutes to Grant"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(50, 50, 50, 50)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Grant Reward Minutes")
            .setMessage("Enter the number of minutes to add to the reward bank:")
            .setView(minutesInput)
            .setPositiveButton("Submit") { _, _ ->
                val minutesText = minutesInput.text.toString()
                val minutes = minutesText.toIntOrNull()
                if (minutes != null && minutes > 0) {
                    val progressManager = DailyProgressManager(this)
                    val newTotal = progressManager.addRewardMinutes(minutes)
                    // Refresh counts display to show updated minutes
                    updateCountsDisplay()
                    android.widget.Toast.makeText(this, "Granted $minutes minutes! Total: $newTotal minutes", android.widget.Toast.LENGTH_LONG).show()
                    // Note: setBankedRewardMinutes already updates last_updated timestamp
                    // Sync will run automatically next time BattleHub or Trainer Map loads (per Daily Reset Logic.md)
                } else {
                    android.widget.Toast.makeText(this, "Please enter a valid number of minutes", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun setupClickListeners() {
        // Pokedex toggle
        val pokedexHeader: View = findViewById(R.id.pokedexHeader)
        val pokedexToggle: TextView = findViewById(R.id.pokedexToggle)
        pokedexHeader.setOnClickListener {
            togglePokedex(pokedexToggle)
        }
        
        // Battle button
        battleButton.setOnClickListener {
            startBattle()
        }
        
        // Victory overlay close
        findViewById<Button>(R.id.victoryCloseButton).setOnClickListener {
            closeVictory()
        }
        
        // Earn berries buttons - navigate back to MainActivity which will show training map
        earnBerriesButton.setOnClickListener {
            val intent = Intent(this, TrainingMapActivity::class.java).apply {
                putExtra("mapType", "required")
            }
            startActivityForResult(intent, 2001) // Use request code 2001 for training map
        }
        
        earnExtraBerriesButton.setOnClickListener {
            val intent = Intent(this, TrainingMapActivity::class.java).apply {
                putExtra("mapType", "optional")
            }
            startActivityForResult(intent, 2002) // Use request code 2002 for optional map
        }
        
        // Bonus training button
        bonusTrainingButton.setOnClickListener {
            val intent = Intent(this, TrainingMapActivity::class.java).apply {
                putExtra("mapType", "bonus")
            }
            startActivityForResult(intent, 2003) // Use request code 2003 for bonus map
        }

        choresButton.setOnClickListener {
            startActivityForResult(Intent(this, ChoresActivity::class.java), 2010)
        }
    }
    
    private fun showBonusTrainingTasks() {
        // Try to fetch from GitHub first (network), fall back to cached if network unavailable
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // First priority: fetch from GitHub
                val fetchedContent = ContentUpdateService().fetchMainContent(this@BattleHubActivity)
                withContext(Dispatchers.Main) {
                    if (fetchedContent != null) {
                        displayBonusTasksDialog(fetchedContent)
                    } else {
                        // Fall back to cached content
                        tryCachedBonusContent()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BattleHubActivity", "Error fetching bonus tasks from network", e)
                // Network unavailable, fall back to cached content
                withContext(Dispatchers.Main) {
                    tryCachedBonusContent()
                }
            }
        }
    }
    
    private fun tryCachedBonusContent() {
        // Fall back to cached content if network fetch failed
        val currentContent = try {
            var content: MainContent? = null
            val jsonString = mainContentJson ?: ContentUpdateService().getCachedMainContent(this)
            if (jsonString != null && jsonString.isNotEmpty()) {
                content = Gson().fromJson(jsonString, MainContent::class.java)
            }
            content
        } catch (e: Exception) {
            android.util.Log.e("BattleHubActivity", "Error loading cached bonus tasks", e)
            null
        }
        
        if (currentContent != null) {
            displayBonusTasksDialog(currentContent)
        } else {
            android.widget.Toast.makeText(this, "Unable to load bonus tasks. Please check your connection.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun displayBonusTasksDialog(currentContent: MainContent) {
        val bonusSection = currentContent.sections?.find { it.id == "bonus" }
        val bonusTasks = bonusSection?.tasks ?: emptyList()
        
        if (bonusTasks.isEmpty()) {
            android.widget.Toast.makeText(this, "No bonus tasks available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        // Create a ScrollView to contain the bonus task buttons
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * resources.displayMetrics.density).toInt(), 
                       (16 * resources.displayMetrics.density).toInt(), 
                       (16 * resources.displayMetrics.density).toInt(), 
                       (16 * resources.displayMetrics.density).toInt())
        }
        
        // Create a button for each bonus task
        bonusTasks.forEach { task ->
            val taskButton = Button(this).apply {
                text = task.title ?: "Bonus Task"
                textSize = 16f
                setPadding((16 * resources.displayMetrics.density).toInt(), 
                           (12 * resources.displayMetrics.density).toInt(), 
                           (16 * resources.displayMetrics.density).toInt(), 
                           (12 * resources.displayMetrics.density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
                background = getDrawable(R.drawable.button_rounded)
                setTextColor(android.graphics.Color.WHITE)
                
                setOnClickListener {
                    launchTask(task, "bonus")
                    // Close the dialog
                    (context as? android.content.Context)?.let {
                        // The dialog will be dismissed when we launch the activity
                    }
                }
            }
            
            container.addView(taskButton)
        }
        
        scrollView.addView(container)
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🎮 Bonus Training 🎮")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }
    
    private fun launchTask(task: Task, sectionId: String) {
        val gameType = task.launch ?: "unknown"
        val gameTitle = task.title ?: "Task"
        
        // For all task types, launch MainActivity with the task
        // MainActivity will handle launching the task and completion callbacks
        // This uses the existing flow that already handles video completion, coins, etc.
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("launchTask", gameType)
            putExtra("taskTitle", gameTitle)
            putExtra("sectionId", sectionId)
        }
        startActivity(intent)
    }
    
    private fun showOptionalTrainingTasks() {
        // Try to fetch from GitHub first (network), fall back to cached if network unavailable
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // First priority: fetch from GitHub
                val fetchedContent = ContentUpdateService().fetchMainContent(this@BattleHubActivity)
                withContext(Dispatchers.Main) {
                    if (fetchedContent != null) {
                        displayOptionalTasksDialog(fetchedContent)
                    } else {
                        // Fall back to cached content
                        tryCachedOptionalContent()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BattleHubActivity", "Error fetching optional tasks from network", e)
                // Network unavailable, fall back to cached content
                withContext(Dispatchers.Main) {
                    tryCachedOptionalContent()
                }
            }
        }
    }
    
    private fun tryCachedOptionalContent() {
        // Fall back to cached content if network fetch failed
        val currentContent = try {
            var content: MainContent? = null
            val jsonString = mainContentJson ?: ContentUpdateService().getCachedMainContent(this)
            if (jsonString != null && jsonString.isNotEmpty()) {
                content = Gson().fromJson(jsonString, MainContent::class.java)
            }
            content
        } catch (e: Exception) {
            android.util.Log.e("BattleHubActivity", "Error loading cached optional tasks", e)
            null
        }
        
        if (currentContent != null) {
            displayOptionalTasksDialog(currentContent)
        } else {
            android.widget.Toast.makeText(this, "Unable to load optional tasks. Please check your connection.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun displayOptionalTasksDialog(currentContent: MainContent) {
        val optionalSection = currentContent.sections?.find { it.id == "optional" }
        val optionalTasks = optionalSection?.tasks ?: emptyList()
        
        if (optionalTasks.isEmpty()) {
            android.widget.Toast.makeText(this, "No optional tasks available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        // Create a ScrollView to contain the optional task buttons
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * resources.displayMetrics.density).toInt(), 
                       (16 * resources.displayMetrics.density).toInt(), 
                       (16 * resources.displayMetrics.density).toInt(), 
                       (16 * resources.displayMetrics.density).toInt())
        }
        
        // Load icon config for stars
        data class IconConfig(val starsIcon: String = "🍍", val coinsIcon: String = "🪙")
        val icons = try {
            val inputStream = assets.open("config/icon_config.json")
            val configJson = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()
            Gson().fromJson(configJson, IconConfig::class.java) ?: IconConfig()
        } catch (e: Exception) {
            IconConfig()
        }
        
        val progressManager = DailyProgressManager(this)
        val completedTasksMap = progressManager.getCompletedTasksMap()
        
        // Create a button for each optional task
        optionalTasks.forEach { task ->
            val taskId = task.launch ?: "unknown"
            val uniqueTaskId = "${"optional"}_$taskId" // Optional tasks use unique IDs
            val isCompleted = completedTasksMap[uniqueTaskId] == true || completedTasksMap[taskId] == true
            
            val stars = task.stars ?: 0
            val buttonText = if (stars > 0) {
                "${task.title ?: "Optional Task"} ${icons.starsIcon.repeat(stars)}"
            } else {
                task.title ?: "Optional Task"
            }
            
            val taskButton = Button(this).apply {
                text = buttonText
                textSize = 16f
                setPadding((16 * resources.displayMetrics.density).toInt(), 
                           (12 * resources.displayMetrics.density).toInt(), 
                           (16 * resources.displayMetrics.density).toInt(), 
                           (12 * resources.displayMetrics.density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
                background = getDrawable(R.drawable.button_rounded)
                setTextColor(if (isCompleted) android.graphics.Color.GRAY else android.graphics.Color.WHITE)
                alpha = if (isCompleted) 0.6f else 1f
                
                setOnClickListener {
                    launchTask(task, "optional")
                    // Close the dialog
                    (context as? android.content.Context)?.let {
                        // The dialog will be dismissed when we launch the activity
                    }
                }
            }
            
            container.addView(taskButton)
        }
        
        scrollView.addView(container)
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🗺️ Extra Practice 🗺️")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }
    
    private fun showRequiredTrainingTasks() {
        // Try to fetch from GitHub first (network), fall back to cached if network unavailable
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // First priority: fetch from GitHub
                val fetchedContent = ContentUpdateService().fetchMainContent(this@BattleHubActivity)
                withContext(Dispatchers.Main) {
                    if (fetchedContent != null) {
                        displayRequiredTasksDialog(fetchedContent)
                    } else {
                        // Fall back to cached content
                        tryCachedRequiredContent()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BattleHubActivity", "Error fetching required tasks from network", e)
                // Network unavailable, fall back to cached content
                withContext(Dispatchers.Main) {
                    tryCachedRequiredContent()
                }
            }
        }
    }
    
    private fun tryCachedRequiredContent() {
        // Fall back to cached content if network fetch failed
        val currentContent = try {
            var content: MainContent? = null
            val jsonString = mainContentJson ?: ContentUpdateService().getCachedMainContent(this)
            if (jsonString != null && jsonString.isNotEmpty()) {
                content = Gson().fromJson(jsonString, MainContent::class.java)
            }
            content
        } catch (e: Exception) {
            android.util.Log.e("BattleHubActivity", "Error loading cached required tasks", e)
            null
        }
        
        if (currentContent != null) {
            displayRequiredTasksDialog(currentContent)
        } else {
            android.widget.Toast.makeText(this, "Unable to load required tasks. Please check your connection.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun displayRequiredTasksDialog(currentContent: MainContent) {
        val requiredSection = currentContent.sections?.find { it.id == "required" }
        val requiredTasks = requiredSection?.tasks ?: emptyList()
        
        if (requiredTasks.isEmpty()) {
            android.widget.Toast.makeText(this, "No required tasks available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        // Create a ScrollView to contain the required task buttons
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * resources.displayMetrics.density).toInt(), 
                       (16 * resources.displayMetrics.density).toInt(), 
                       (16 * resources.displayMetrics.density).toInt(), 
                       (16 * resources.displayMetrics.density).toInt())
        }
        
        // Load icon config for stars
        data class IconConfig(val starsIcon: String = "🍍", val coinsIcon: String = "🪙")
        val icons = try {
            val inputStream = assets.open("config/icon_config.json")
            val configJson = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()
            Gson().fromJson(configJson, IconConfig::class.java) ?: IconConfig()
        } catch (e: Exception) {
            IconConfig()
        }
        
        val progressManager = DailyProgressManager(this)
        val completedTasksMap = progressManager.getCompletedTasksMap()
        
        // Create a button for each required task
        requiredTasks.forEach { task ->
            val taskId = task.launch ?: "unknown"
            val isCompleted = completedTasksMap[taskId] == true
            
            val stars = task.stars ?: 0
            val buttonText = if (stars > 0) {
                "${task.title ?: "Required Task"} ${icons.starsIcon.repeat(stars)}"
            } else {
                task.title ?: "Required Task"
            }
            
            val taskButton = Button(this).apply {
                text = buttonText
                textSize = 16f
                setPadding((16 * resources.displayMetrics.density).toInt(), 
                           (12 * resources.displayMetrics.density).toInt(), 
                           (16 * resources.displayMetrics.density).toInt(), 
                           (12 * resources.displayMetrics.density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
                background = getDrawable(R.drawable.button_rounded)
                setTextColor(if (isCompleted) android.graphics.Color.GRAY else android.graphics.Color.WHITE)
                alpha = if (isCompleted) 0.6f else 1f
                
                setOnClickListener {
                    launchTask(task, "required")
                    // Close the dialog
                    (context as? android.content.Context)?.let {
                        // The dialog will be dismissed when we launch the activity
                    }
                }
            }
            
            container.addView(taskButton)
        }
        
        scrollView.addView(container)
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🗺️ Training Map 🗺️")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }
    
    
    private fun updateEarnButtonsState() {
        val currentContent = try {
            val jsonString = mainContentJson ?: ContentUpdateService().getCachedMainContent(this)
            if (jsonString != null && jsonString.isNotEmpty()) {
                Gson().fromJson(jsonString, MainContent::class.java)
            } else null
        } catch (e: Exception) {
            null
        }
        
        if (currentContent != null) {
            val progressManager = DailyProgressManager(this)
            val completedTasksMap = progressManager.getCompletedTasksMap()
            
            android.util.Log.d("BattleHubActivity", "updateEarnButtonsState: completedTasksMap keys: ${completedTasksMap.keys}")
            android.util.Log.d("BattleHubActivity", "updateEarnButtonsState: completedTasksMap size: ${completedTasksMap.size}")
            android.util.Log.d("BattleHubActivity", "updateEarnButtonsState: completed tasks (true): ${completedTasksMap.filter { it.value }.keys}")
            
            // Check if all required tasks are completed
            val requiredSection = currentContent.sections?.find { it.id == "required" }
            val allRequiredTasks = requiredSection?.tasks ?: emptyList()
            android.util.Log.d("BattleHubActivity", "updateEarnButtonsState: allRequiredTasks count (before filtering): ${allRequiredTasks.size}")
            
            val requiredTasks = allRequiredTasks.filter { task ->
                val hasTitle = task.title != null
                val hasLaunch = task.launch != null
                val isVisible = TaskVisibilityChecker.isTaskVisible(task)
                
                // Log tasks that are being filtered out
                if (!isVisible && hasTitle && hasLaunch) {
                    android.util.Log.d("BattleHubActivity", "updateEarnButtonsState: Filtering out task '${task.title}' (disable: ${task.disable}, showdays: ${task.showdays}, hidedays: ${task.hidedays}, displayDays: ${task.displayDays})")
                }
                
                hasTitle && hasLaunch && isVisible
            }
            
            android.util.Log.d("BattleHubActivity", "updateEarnButtonsState: requiredTasks count (after filtering): ${requiredTasks.size}")
            android.util.Log.d("BattleHubActivity", "updateEarnButtonsState: requiredTasks titles: ${requiredTasks.map { it.title }}")
            
            // Handle duplicate task titles by using a Set of unique task names
            // If a task appears multiple times, we only need to check it once
            val uniqueRequiredTaskNames = requiredTasks.mapNotNull { it.title }.toSet()
            android.util.Log.d("BattleHubActivity", "updateEarnButtonsState: uniqueRequiredTaskNames count: ${uniqueRequiredTaskNames.size}, names: $uniqueRequiredTaskNames")
            
            val allUniqueTasksCompleted = uniqueRequiredTaskNames.isNotEmpty() && uniqueRequiredTaskNames.all { taskName ->
                // Use task name as key (matches how markTaskCompletedWithName stores tasks)
                val completed = completedTasksMap[taskName] == true
                android.util.Log.d("BattleHubActivity", "updateEarnButtonsState: unique task '$taskName' completed: $completed (value in map: ${completedTasksMap[taskName]})")
                completed
            }
            
            // Also check tasks that might not have titles (fallback to task ID)
            val tasksWithoutTitles = requiredTasks.filter { it.title.isNullOrEmpty() }
            val allTasksWithoutTitlesCompleted = if (tasksWithoutTitles.isNotEmpty()) {
                android.util.Log.d("BattleHubActivity", "updateEarnButtonsState: Found ${tasksWithoutTitles.size} tasks without titles, checking by ID")
                tasksWithoutTitles.all { task ->
                    val baseTaskId = task.launch ?: ""
                    var taskId = baseTaskId
                    
                    // Handle diagramLabeler with unique IDs
                    if (baseTaskId == "diagramLabeler" && !task.url.isNullOrEmpty()) {
                        val originalUrl = task.url
                        if (originalUrl.contains("diagram=")) {
                            val diagramParam = originalUrl.substringAfter("diagram=").substringBefore("&").substringBefore("#")
                            if (diagramParam.isNotEmpty()) {
                                taskId = "${baseTaskId}_$diagramParam"
                            }
                        }
                    }
                    
                    val completed = completedTasksMap[taskId] == true
                    android.util.Log.d("BattleHubActivity", "updateEarnButtonsState: task without title (ID: $taskId) completed: $completed")
                    completed
                }
            } else {
                true // No tasks without titles, so this check passes
            }
            
            val allRequiredCompleted = allUniqueTasksCompleted && allTasksWithoutTitlesCompleted
            
            android.util.Log.d("BattleHubActivity", "updateEarnButtonsState: allRequiredCompleted: $allRequiredCompleted")
            
            // Enable/disable earn Extra berries button based on required tasks completion
            earnExtraBerriesButton.isEnabled = allRequiredCompleted
            earnExtraBerriesButton.alpha = if (allRequiredCompleted) 1f else 0.5f
        } else {
            android.util.Log.w("BattleHubActivity", "updateEarnButtonsState: currentContent is null, cannot check task completion")
        }
    }
    
    // Removed isTaskVisible() - now using TaskVisibilityChecker for consistency with Layout.kt
    
    private fun togglePokedex(toggleView: TextView) {
        isPokedexExpanded = !isPokedexExpanded
        
        val animator = if (isPokedexExpanded) {
            ObjectAnimator.ofFloat(pokedexContent, "alpha", 0f, 1f).apply {
                duration = 300
            }
        } else {
            ObjectAnimator.ofFloat(pokedexContent, "alpha", 1f, 0f).apply {
                duration = 300
            }
        }
        
        pokedexContent.visibility = if (isPokedexExpanded) View.VISIBLE else View.GONE
        toggleView.text = if (isPokedexExpanded) "▼" else "▶"
        toggleView.rotation = if (isPokedexExpanded) 0f else -90f
        animator.start()
        
        // Scroll to selected Pokemon when expanding
        if (isPokedexExpanded && selectedPokemonId != null) {
            pokedexContent.post {
                scrollToSelectedPokemon()
            }
        }
    }
    
    private fun scrollToSelectedPokemon() {
        for (i in 0 until pokedexGrid.childCount) {
            val card = pokedexGrid.getChildAt(i) as? LinearLayout ?: continue
            val cardId = card.tag as? Int
            if (cardId == selectedPokemonId) {
                val scrollX = card.left - (pokedexContent.width / 2) + (card.width / 2)
                pokedexContent.smoothScrollTo(scrollX.coerceAtLeast(0), 0)
                break
            }
        }
    }

    private fun parsePokemonFilename(filename: String): ParsedPokemon? {
        if (!filename.endsWith(".png")) return null
        
        val base = filename.replace(".png", "")
        val shiny = base.endsWith("-s")
        val baseNoShiny = if (shiny) base.substring(0, base.length - 2) else base
        val parts = baseNoShiny.split("-")
        
        if (parts.size < 2) return null
        
        val prefix = parts[0].toIntOrNull() ?: return null
        val pokenum = parts[1].toIntOrNull() ?: return null
        
        return ParsedPokemon(
            prefix = prefix,
            pokenum = pokenum,
            name = getPokemonName(pokenum),
            filename = filename,
            shiny = shiny
        )
    }
    
    private fun getPokemonName(pokenum: Int): String {
        val names = mapOf(
            1 to "Bulbasaur", 2 to "Ivysaur", 3 to "Venusaur", 4 to "Charmander", 5 to "Charmeleon",
            6 to "Charizard", 7 to "Squirtle", 8 to "Wartortle", 9 to "Blastoise", 25 to "Pikachu",
            130 to "Gyarados", 143 to "Snorlax", 149 to "Dragonite", 150 to "Mewtwo"
        )
        return names[pokenum] ?: "#$pokenum"
    }
    
    private fun loadPokemonData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val manifestJson = assets.open("images/pokeSprites/sprites/pokemon/pokedex_manifest.json")
                    .bufferedReader().use { it.readText() }
                val fileList = JSONArray(manifestJson) // This is an array of strings (filenames)
                
                val progressManager = DailyProgressManager(this@BattleHubActivity)
                // Refresh unlocked count (in case it changed after battle victory)
                unlockedPokemonCount = progressManager.getUnlockedPokemonCount()
                
                // Update Pokedex title with unlocked count
                withContext(Dispatchers.Main) {
                    updatePokedexTitle()
                }
                
                // Parse all Pokemon files
                val allPokemon = mutableListOf<ParsedPokemon>()
                for (i in 0 until fileList.length()) {
                    val filename = fileList.getString(i)
                    val parsed = parsePokemonFilename(filename)
                    if (parsed != null) {
                        allPokemon.add(parsed)
                    }
                }
                
                // Sort by pokenum, then by shiny (non-shiny first)
                allPokemon.sortWith(compareBy<ParsedPokemon> { it.pokenum }.thenBy { it.shiny })
                
                // Cache Pokemon by prefix for quick lookup
                pokemonCache = allPokemon.associateBy { it.prefix }
                
                withContext(Dispatchers.Main) {
                    if (!isDestroyed && !isFinishing) {
                        populatePokemonGrid(allPokemon.filter { it.prefix <= unlockedPokemonCount })
                        
                        // Find boss Pokemon (next to unlock - prefix = unlockedPokemonCount + 1)
                        val bossPokemon = allPokemon.find { it.prefix == unlockedPokemonCount + 1 }
                            ?: allPokemon.filter { it.prefix > unlockedPokemonCount }
                                .minByOrNull { it.prefix }
                        
            if (bossPokemon != null) {
                currentBossPokemon = bossPokemon
                loadBossPokemon(bossPokemon.filename, bossPokemon.name)
            }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BattleHubActivity", "Error loading Pokemon data", e)
                e.printStackTrace()
            }
        }
    }
    
    private fun populatePokemonGrid(pokemonList: List<ParsedPokemon>) {
        pokedexGrid.removeAllViews()
        val density = resources.displayMetrics.density
        
        // Calculate card width to fit as many as possible horizontally
        // Card width = sprite (60dp) + padding (16dp) + margins (16dp) = ~92dp minimum
        // But we'll use 100dp for comfortable spacing
        val cardWidthDp = 100f
        val cardWidthPx = (cardWidthDp * density).toInt()
        val screenWidthPx = resources.displayMetrics.widthPixels
        val paddingPx = (16 * density).toInt() // Account for padding
        val availableWidth = screenWidthPx - paddingPx
        val cardsPerScreen = (availableWidth / cardWidthPx).coerceAtLeast(1)
        
        android.util.Log.d("BattleHubActivity", "Screen width: ${screenWidthPx}px, Card width: ${cardWidthPx}px, Cards per screen: $cardsPerScreen")
        
        // Store Pokemon data for lazy loading
        pokemonListForLazyLoading = pokemonList
        
        // Create cards without loading sprites immediately (lazy loading)
        for (pokemon in pokemonList) {
            val card = createPokemonCard(pokemon.prefix, pokemon.name, pokemon.filename, true, density, cardWidthPx, loadSpriteImmediately = false)
            pokedexGrid.addView(card)
        }
        
        // Set up scroll listener for lazy loading
        setupPokedexScrollListener()
        
        // Load initial visible sprites (only what's visible on screen)
        loadVisiblePokemonSprites()
        
        // Set default selected Pokemon (highest prefix = most recently unlocked, which should be the old boss)
        if (pokemonList.isNotEmpty()) {
            val defaultPokemon = pokemonList.maxByOrNull { it.prefix } ?: pokemonList.first()
            selectedPokemonId = defaultPokemon.prefix
            updatePlayerPokemon(defaultPokemon.prefix)
        }
    }
    
    private var pokemonListForLazyLoading: List<ParsedPokemon> = emptyList()
    
    private fun setupPokedexScrollListener() {
        // Use ViewTreeObserver for scroll changes (compatible with all API levels)
        pokedexContent.viewTreeObserver.addOnScrollChangedListener {
            loadVisiblePokemonSprites()
        }
    }
    
    private fun loadVisiblePokemonSprites() {
        if (pokemonListForLazyLoading.isEmpty()) return
        
        val scrollX = pokedexContent.scrollX
        val viewWidth = pokedexContent.width
        val cardWidthPx = (100f * resources.displayMetrics.density).toInt()
        
        // Calculate which cards are visible (with some buffer for smooth scrolling)
        val buffer = cardWidthPx * 2 // Load 2 cards ahead/behind
        val startIndex = ((scrollX - buffer) / cardWidthPx).coerceAtLeast(0)
        val endIndex = ((scrollX + viewWidth + buffer) / cardWidthPx).coerceAtMost(pokedexGrid.childCount - 1)
        
        // Load sprites for visible cards
        for (i in startIndex..endIndex) {
            if (i < pokedexGrid.childCount) {
                val card = pokedexGrid.getChildAt(i) as? LinearLayout ?: continue
                val sprite = card.getChildAt(0) as? ImageView ?: continue
                val pokemonId = card.tag as? Int ?: continue
                
                // Check if sprite needs to be loaded
                val spriteTag = sprite.tag?.toString() ?: ""
                if (spriteTag != "loading" && spriteTag != "loaded" && spriteTag != "locked") {
                    val pokemon = pokemonListForLazyLoading.find { it.prefix == pokemonId }
                    if (pokemon != null) {
                        sprite.tag = "loading"
                        loadPokemonSpriteIntoImageView(sprite, pokemon.filename)
                        // Tag will be set to "loaded" after image loads in loadPokemonSpriteIntoImageView
                    }
                }
            }
        }
    }
    
    private fun loadPokemonSpriteAsBase64(filename: String): String? {
        return try {
            val inputStream = assets.open("images/pokeSprites/sprites/pokemon/$filename")
            val bytes = inputStream.readBytes()
            inputStream.close()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            "data:image/png;base64,$base64"
        } catch (e: Exception) {
            android.util.Log.e("BattleHubActivity", "Error loading Pokemon image: $filename", e)
            null
        }
    }
    
    private fun loadPokemonSpriteIntoImageView(imageView: ImageView, filename: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val base64Data = loadPokemonSpriteAsBase64(filename)
            withContext(Dispatchers.Main) {
                if (!isDestroyed && !isFinishing && base64Data != null) {
                    try {
                        // Decode base64 to bitmap
                        val base64String = base64Data.substringAfter(",")
                        val decodedBytes = android.util.Base64.decode(base64String, android.util.Base64.DEFAULT)
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap)
                            imageView.visibility = View.VISIBLE
                            imageView.tag = "loaded" // Mark as loaded
                            android.util.Log.d("BattleHubActivity", "Successfully loaded sprite: $filename")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("BattleHubActivity", "Error decoding base64 sprite: $filename", e)
                    }
                }
            }
        }
    }
    
    private fun loadBossPokemon(filename: String, name: String) {
        android.util.Log.d("BattleHubActivity", "Loading boss Pokemon sprite: $filename")
        if (::bossPokemonSprite.isInitialized && !isDestroyed && !isFinishing) {
            bossPokemonName.text = name
            
            // Reset boss sprite state before loading (right side up, not faded)
            bossPokemonSprite.alpha = 1f
            bossPokemonSprite.rotation = 0f
            bossPokemonSprite.translationX = 0f
            bossPokemonSprite.translationY = 0f
            bossPokemonSprite.scaleX = 1f
            bossPokemonSprite.scaleY = 1f
            
            loadPokemonSpriteIntoImageView(bossPokemonSprite, filename)
        }
    }
    
    private fun createPokemonCard(pokemonId: Int, name: String, filename: String, isUnlocked: Boolean, density: Float, cardWidthPx: Int, loadSpriteImmediately: Boolean = true): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                cardWidthPx,
                (120 * density).toInt()
            ).apply {
                setMargins((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            }
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            gravity = android.view.Gravity.CENTER
            
            tag = pokemonId // Store ID for selection checking
            
            background = GradientDrawable().apply {
                setColor(if (isUnlocked) 0xFFF5F7FA.toInt() else 0xFFE0E0E0.toInt())
                cornerRadius = 10 * density
                setStroke((2 * density).toInt(), if (pokemonId == selectedPokemonId) 0xFFFFD700.toInt() else 0x00000000.toInt())
            }
            
            if (!isUnlocked) {
                alpha = 0.5f
            }
            
            setOnClickListener {
                if (isUnlocked) {
                    selectPokemon(pokemonId)
                }
            }
        }
        
        // Pokemon sprite
        val sprite = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (60 * density).toInt(),
                (60 * density).toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        
        // Add sprite to card first
        card.addView(sprite)
        
        if (isUnlocked) {
            if (loadSpriteImmediately) {
                // Load sprite immediately (for boss/player Pokemon)
                loadPokemonSpriteIntoImageView(sprite, filename)
                sprite.tag = "loaded"
            } else {
                // Lazy loading: sprite will be loaded when visible
                sprite.tag = "pending"
                // Set a placeholder or leave empty
                sprite.setImageResource(android.R.color.transparent)
            }
        } else {
            sprite.setImageResource(android.R.drawable.ic_lock_lock)
            sprite.visibility = View.VISIBLE
            sprite.tag = "locked"
        }
        
        // Pokemon name
        val nameView = TextView(this).apply {
            text = name
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
            }
        }
        
        card.addView(nameView)
        
        return card
    }
    
    private fun selectPokemon(pokemonId: Int) {
        selectedPokemonId = pokemonId
        updatePokemonGridSelection()
        updatePlayerPokemon(pokemonId)
        scrollToSelectedPokemon()
    }
    
    private fun updatePokemonGridSelection() {
        for (i in 0 until pokedexGrid.childCount) {
            val card = pokedexGrid.getChildAt(i) as? LinearLayout ?: continue
            val cardId = card.tag as? Int
            val isSelected = cardId == selectedPokemonId
            val density = resources.displayMetrics.density
            val isUnlocked = card.alpha > 0.5f
            
            card.background = GradientDrawable().apply {
                setColor(if (isUnlocked) 0xFFF5F7FA.toInt() else 0xFFE0E0E0.toInt())
                cornerRadius = 10 * density
                setStroke((2 * density).toInt(), if (isSelected) 0xFFFFD700.toInt() else 0x00000000.toInt())
            }
            
            // Pulse animation for selected card
            if (isSelected) {
                card.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(200)
                    .withEndAction {
                        card.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(200)
                            .start()
                    }
                    .start()
            }
        }
    }
    
    private var pokemonCache: Map<Int, ParsedPokemon> = emptyMap()
    
    private fun updatePlayerPokemon(pokemonId: Int) {
        // Load and display selected Pokemon from cache
        val pokemon = pokemonCache[pokemonId]
        if (pokemon != null) {
            android.util.Log.d("BattleHubActivity", "Loading player Pokemon sprite: ${pokemon.filename}")
            if (::playerPokemonSprite.isInitialized && !isDestroyed && !isFinishing) {
                playerPokemonName.text = pokemon.name
                loadPokemonSpriteIntoImageView(playerPokemonSprite, pokemon.filename)
                
                // Pulse animation on selection
                playerPokemonSprite.animate()
                    .scaleX(1.2f)
                    .scaleY(1.2f)
                    .setDuration(200)
                    .withEndAction {
                        playerPokemonSprite.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(200)
                            .start()
                    }
                    .start()
            }
        }
    }
    
    private fun getHPColor(hpPercent: Int): Int {
        return when {
            hpPercent >= 76 -> 0xFF2ECC71.toInt() // Green at 76-100%
            hpPercent >= 51 -> 0xFFFFD700.toInt() // Yellow at 51-75%
            else -> 0xFFE74C3C.toInt() // Red at 1-50%
        }
    }
    
    private fun updateBossHPColor(hpPercent: Int) {
        bossHP.progressTintList = android.content.res.ColorStateList.valueOf(getHPColor(hpPercent))
    }
    
    private fun updatePlayerHPColor(hpPercent: Int) {
        playerHP.progressTintList = android.content.res.ColorStateList.valueOf(getHPColor(hpPercent))
    }
    
    private fun updatePlayerPowerColor(powerPercent: Int) {
        playerPower.progressTintList = android.content.res.ColorStateList.valueOf(getHPColor(powerPercent))
    }
    
    private fun updateBossPowerColor(powerPercent: Int) {
        bossPower.progressTintList = android.content.res.ColorStateList.valueOf(getHPColor(powerPercent))
    }
    
    private fun updateBerryMeter() {
        android.util.Log.d("BattleHubActivity", "updateBerryMeter() CALLED")
        val progressManager = DailyProgressManager(this)
        
        // Check if mainContentJson is null and reload if needed
        if (mainContentJson == null) {
            android.util.Log.d("BattleHubActivity", "updateBerryMeter: mainContentJson is NULL, reloading from cache")
            mainContentJson = ContentUpdateService().getCachedMainContent(this)
        }
        
        // Reload mainContentJson if it's null or empty
        if (mainContentJson == null || mainContentJson!!.isEmpty()) {
            android.util.Log.d("BattleHubActivity", "updateBerryMeter: mainContentJson is null/empty, reloading from cache")
            mainContentJson = ContentUpdateService().getCachedMainContent(this)
            android.util.Log.d("BattleHubActivity", "updateBerryMeter: Reloaded mainContentJson, is ${if (mainContentJson != null && mainContentJson!!.isNotEmpty()) "NOT NULL (length=${mainContentJson!!.length})" else "NULL or EMPTY"}")
        }
        
        // Try to get current content (parse JSON string to MainContent)
        val jsonString = mainContentJson ?: ContentUpdateService().getCachedMainContent(this)
        android.util.Log.d("BattleHubActivity", "updateBerryMeter: jsonString is ${if (jsonString != null) "NOT NULL (length=${jsonString.length})" else "NULL"}")
        
        val currentContent = try {
            if (jsonString != null && jsonString.isNotEmpty()) {
                Gson().fromJson(jsonString, MainContent::class.java)
            } else {
                android.util.Log.w("BattleHubActivity", "updateBerryMeter: MainContent JSON is null or empty")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("BattleHubActivity", "Error parsing MainContent JSON", e)
            null
        }
        
        android.util.Log.d("BattleHubActivity", "updateBerryMeter: currentContent is ${if (currentContent != null) "NOT NULL" else "NULL"}")
        
        if (currentContent != null) {
            // Simple: just get earned berries directly
            earnedStars = progressManager.getEarnedBerries()
            
            // Before battle: Show required berries earned out of total required berries
            val progressData = progressManager.getCurrentProgressWithTotals(currentContent)
            totalStars = progressData.second.second // Total required stars
            
            android.util.Log.d("BattleHubActivity", "Berry meter: earnedStars=$earnedStars, totalStars=$totalStars")

            android.util.Log.d("BattleHubActivity", "DEBUG updateBerryMeter: currentContent required_tasks count=${currentContent.sections?.sumOf { it.tasks?.size ?: 0 } ?: 0}")
            android.util.Log.d("BattleHubActivity", "DEBUG updateBerryMeter: progressManager.getCompletedTasksMap() = ${progressManager.getCompletedTasksMap()}")
        } else {
            android.util.Log.w("BattleHubActivity", "CurrentContent is null, using defaults")
            earnedStars = 0
            totalStars = 100
        }
        
        val percentage = if (totalStars > 0) (earnedStars * 100 / totalStars) else 0
        
        // Animate berry meter fill - berryFill is a LinearLayout inside FrameLayout, so use FrameLayout.LayoutParams
        val parent = berryFill.parent as? FrameLayout
        val parentWidth = parent?.width ?: resources.displayMetrics.widthPixels
        val padding = (6 * resources.displayMetrics.density).toInt() // Account for FrameLayout padding
        val availableWidth = parentWidth - padding
        val targetWidth = (availableWidth * percentage / 100).toInt()
        
        val currentWidth = (berryFill.layoutParams as? FrameLayout.LayoutParams)?.width ?: 0
        
        ValueAnimator.ofInt(currentWidth, targetWidth).apply {
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val params = berryFill.layoutParams as FrameLayout.LayoutParams
                params.width = animator.animatedValue as Int
                berryFill.layoutParams = params
            }
            start()
        }
        
        berryCount.text = "$earnedStars / $totalStars Berries"
        android.util.Log.d("BattleHubActivity", "updateBerryMeter: UI updated - berryCount.text=${berryCount.text}, percentage=$percentage")
        
        // Update player power to match berry progress (same percentage)
        playerPower.max = 100
        playerPower.progress = percentage
        playerPowerValue = percentage
        updatePlayerPowerColor(percentage)
        
        // Boss power is always at 100
        bossPower.max = 100
        bossPower.progress = 100
        bossPowerValue = 100
        updateBossPowerColor(100)
        
        // Update HP colors - both start at 100% (green)
        updateBossHPColor(100) // Boss starts at 100%
        updatePlayerHPColor(100) // Player starts at 100%
        
        // Enable battle button if berries are full
        battleButton.isEnabled = percentage >= 100
    }
    
    private fun startBattle() {
        if (isBattling) return
        
        // Check if berries are full
        if (earnedStars < totalStars) {
            android.widget.Toast.makeText(this, "You need to earn all berries to battle!", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        isBattling = true
        battleButton.isEnabled = false
        battleButton.text = "⚔️ Battling... ⚔️"
        
        // Clear any existing battle messages
        clearBattleMessage()
        
        // Reset battle state
        playerHealth = 100
        bossHealth = 100
        playerPowerValue = 100
        bossPowerValue = 100
        
        // Reset HP bars to 100
        playerHP.progress = 100
        bossHP.progress = 100
        updateBossHPColor(100)
        updatePlayerHPColor(100)
        
        // Reset power bars: boss to 100, player to 100 (since berries are full)
        bossPower.progress = 100
        playerPower.progress = 100
        updatePlayerPowerColor(100)
        updateBossPowerColor(100)
        
        // Reset sprite states
        playerPokemonSprite.alpha = 1f
        playerPokemonSprite.rotation = 0f
        playerPokemonSprite.translationX = 0f
        playerPokemonSprite.translationY = 0f
        playerPokemonSprite.scaleX = 1f
        playerPokemonSprite.scaleY = 1f
        bossPokemonSprite.alpha = 1f
        bossPokemonSprite.rotation = 0f
        bossPokemonSprite.translationX = 0f
        bossPokemonSprite.translationY = 0f
        bossPokemonSprite.scaleX = 1f
        bossPokemonSprite.scaleY = 1f
        
        // Start battle sequence
        showBattleMessage("Battle begins!")
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            clearBattleMessage()
            battleRound(1)
        }, 1500)
    }
    
    private fun showBattleMessage(text: String) {
        // Create a temporary TextView for battle message
        val messageView = TextView(this).apply {
            this.text = text
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(30, 15, 30, 15)
            gravity = android.view.Gravity.CENTER
            elevation = 10f
        }
        
        val rootView = window.decorView.rootView as FrameLayout
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.CENTER
        }
        
        rootView.addView(messageView, params)
        
        // Store reference to remove later
        messageView.tag = "battle_message"
        
        // Animate in
        messageView.alpha = 0f
        messageView.scaleX = 0.5f
        messageView.scaleY = 0.5f
        messageView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .start()
    }
    
    private fun clearBattleMessage() {
        val rootView = window.decorView.rootView as FrameLayout
        // Find and remove all battle messages
        val messagesToRemove = mutableListOf<TextView>()
        for (i in 0 until rootView.childCount) {
            val child = rootView.getChildAt(i)
            if (child is TextView && child.tag == "battle_message") {
                messagesToRemove.add(child)
            }
        }
        // Remove all found messages
        messagesToRemove.forEach { messageView ->
            messageView.animate()
                .alpha(0f)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setDuration(300)
                .withEndAction {
                    rootView.removeView(messageView)
                }
                .start()
        }
    }
    
    private fun battleRound(round: Int) {
        if (!isBattling) return
        
        val maxRounds = 5
        if (round > maxRounds) {
            // Run final round: "You attack" only (no boss counter), then defeat sequence and victory.
            // This ensures the player always sees the last hit, boss defeat animation, and then victory.
            battleFinalRound()
            return
        }
        
        // Player attacks first - add delay after previous round (except first round)
        val roundDelay: Long = if (round == 1) 0L else 1500L // First round starts immediately, subsequent rounds have delay
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            clearBattleMessage()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            showBattleMessage("You attack!")
            
                    // Player attack animation - make it visible (1-2 seconds)
                    playerPokemonSprite.animate()
                        .translationX(50f)
                        .scaleX(1.2f)
                        .scaleY(1.2f)
                        .setDuration(1000) // 1 second forward
                        .withEndAction {
                            playerPokemonSprite.animate()
                                .translationX(0f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(1000) // 1 second back
                                .start()
                    
                    // Boss takes damage first - reduce range to make battles closer
                    val bossDamage = 12 + (0..8).random() // Reduced from 15-25 to 12-20
                    bossHealth = (bossHealth - bossDamage).coerceAtLeast(0)
                    bossPowerValue = (bossPowerValue - bossDamage).coerceAtLeast(0)
                    
                    // Update boss HP bar with animation - make it visible (2 seconds)
                    val bossHPPercent = (bossHealth * 100 / 100).coerceIn(0, 100)
                    val bossHPAnimator = ObjectAnimator.ofInt(bossHP, "progress", bossHP.progress, bossHealth).apply {
                        duration = 2000 // 2 seconds so it's clearly visible
                        interpolator = AccelerateDecelerateInterpolator()
                    }
                    updateBossHPColor(bossHPPercent)
                    
                    // Update boss power bar with animation - make it visible (2 seconds)
                    val bossPowerPercent = (bossPowerValue * 100 / 100).coerceIn(0, 100)
                    val bossPowerAnimator = ObjectAnimator.ofInt(bossPower, "progress", bossPower.progress, bossPowerValue).apply {
                        duration = 2000 // 2 seconds so it's clearly visible
                        interpolator = AccelerateDecelerateInterpolator()
                    }
                    updateBossPowerColor(bossPowerPercent)
                    
                    // Track when all animations complete (HP, power, and boss hit shake)
                    var hpAnimationComplete = false
                    var powerAnimationComplete = false
                    var bossHitAnimationComplete = false
                    
                    fun checkAnimationsComplete() {
                        if (hpAnimationComplete && powerAnimationComplete && bossHitAnimationComplete) {
                            // All animations complete, now check if boss is defeated
                            if (bossHealth <= 0) {
                                // All animations are done, now show defeated sequence
                                clearBattleMessage()
                                val bossName = currentBossPokemon?.name ?: "Boss"
                                showBattleMessage("$bossName was defeated!")
                                
                                // Wait for message to be clearly visible (2 seconds), then start shake animation
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    // Boss shake animation (shake left/right before rolling over) - make it visible (1.5 seconds total)
                                    bossPokemonSprite.animate()
                                        .translationX(-20f)
                                        .setDuration(300)
                                        .withEndAction {
                                            bossPokemonSprite.animate()
                                                .translationX(20f)
                                                .setDuration(300)
                                                .withEndAction {
                                                    bossPokemonSprite.animate()
                                                        .translationX(-20f)
                                                        .setDuration(300)
                                                        .withEndAction {
                                                            bossPokemonSprite.animate()
                                                                .translationX(20f)
                                                                .setDuration(300)
                                                                .withEndAction {
                                                                    bossPokemonSprite.animate()
                                                                        .translationX(0f)
                                                                        .setDuration(300)
                                                                        .withEndAction {
                                                                            // Now roll over and grey out - make it visible (2 seconds)
                                                                            bossPokemonSprite.animate()
                                                                                .rotation(180f)
                                                                                .translationY(100f)
                                                                                .alpha(0.3f)
                                                                                .setDuration(2000)
                                                                                .withEndAction {
                                                                                    // Apply grayscale filter
                                                                                    bossPokemonSprite.setColorFilter(android.graphics.ColorMatrixColorFilter(
                                                                                        android.graphics.ColorMatrix().apply {
                                                                                            setSaturation(0f) // Grayscale
                                                                                        }
                                                                                    ))
                                                                                    
                                                                                    clearBattleMessage()
                                                                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                                                        showBattleMessage("🎉 Victory! 🎉")
                                                                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                                                            clearBattleMessage()
                                                                                            endBattle(true)
                                                                                        }, 2000) // 2 seconds to see victory message
                                                                                    }, 1000) // 1 second delay before victory
                                                                                }
                                                                                .start()
                                                                        }
                                                                        .start()
                                                                }
                                                                .start()
                                                        }
                                                        .start()
                                                }
                                                .start()
                                        }
                                        .start()
                                }, 2000) // Wait 2 seconds for defeated message to be clearly visible
                            } else {
                                // Boss attacks back (only if still alive) - add longer delay between attacks
                                // Wait for player attack animations to complete, then add delay before boss attacks
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    clearBattleMessage()
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        showBattleMessage("Boss attacks!")
                                        
                                        // Boss attack animation - make it visible (2 seconds total)
                                        bossPokemonSprite.animate()
                                            .translationX(-50f)
                                            .scaleX(1.2f)
                                            .scaleY(1.2f)
                                            .setDuration(1000) // 1 second forward
                                            .withEndAction {
                                                bossPokemonSprite.animate()
                                                    .translationX(0f)
                                                    .scaleX(1f)
                                                    .scaleY(1f)
                                                    .setDuration(1000) // 1 second back
                                                    .start()
                                                
                                                // Player takes damage - 1-3 less than boss took to make battle much closer
                                                val playerDamage = (bossDamage - (1..3).random()).coerceAtLeast(1)
                                                playerHealth = (playerHealth - playerDamage).coerceAtLeast(0)
                                                playerPowerValue = (playerPowerValue - playerDamage).coerceAtLeast(0)
                                                
                                                // Update player HP bar with animation - make it visible (2 seconds)
                                                val playerHPPercent = (playerHealth * 100 / 100).coerceIn(0, 100)
                                                val playerHPAnimator = ObjectAnimator.ofInt(playerHP, "progress", playerHP.progress, playerHealth).apply {
                                                    duration = 2000 // 2 seconds so it's clearly visible
                                                    interpolator = AccelerateDecelerateInterpolator()
                                                }
                                                playerHPAnimator.start()
                                                updatePlayerHPColor(playerHPPercent)
                                                
                                                // Update player power bar with animation - make it visible (2 seconds)
                                                val playerPowerPercent = (playerPowerValue * 100 / 100).coerceIn(0, 100)
                                                val playerPowerAnimator = ObjectAnimator.ofInt(playerPower, "progress", playerPower.progress, playerPowerValue).apply {
                                                    duration = 2000 // 2 seconds so it's clearly visible
                                                    interpolator = AccelerateDecelerateInterpolator()
                                                }
                                                playerPowerAnimator.start()
                                                updatePlayerPowerColor(playerPowerPercent)
                                                
                                                // Player hit animation (red flash/fade) - make it visible (1 second total)
                                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                    // Create red overlay effect by animating alpha with red tint
                                                    val originalAlpha = playerPokemonSprite.alpha
                                                    val redTint = android.graphics.Color.argb(150, 255, 0, 0) // Semi-transparent red
                                                    
                                                    // Flash effect: fade out, fade in, fade out, fade in - 250ms each = 1s total
                                                    playerPokemonSprite.animate()
                                                        .alpha(0.3f)
                                                        .setDuration(250)
                                                        .withEndAction {
                                                            playerPokemonSprite.animate()
                                                                .alpha(1f)
                                                                .setDuration(250)
                                                                .withEndAction {
                                                                    playerPokemonSprite.animate()
                                                                        .alpha(0.3f)
                                                                        .setDuration(250)
                                                                        .withEndAction {
                                                                            playerPokemonSprite.animate()
                                                                                .alpha(originalAlpha)
                                                                                .setDuration(250)
                                                                                .withEndAction {
                                                                                    clearBattleMessage()
                                                                                    // Next: either final round (You attack only) or next full round
                                                                                    if (round >= maxRounds) {
                                                                                        battleFinalRound()
                                                                                    } else {
                                                                                        battleRound(round + 1)
                                                                                    }
                                                                                }
                                                                                .start()
                                                                        }
                                                                        .start()
                                                                }
                                                                .start()
                                                        }
                                                        .start()
                                                }, 1000) // 1s delay to see HP bar animation start
                                            }
                                            .start()
                                    }, 2000) // 2s delay between attacks to see animations clearly
                                }, 2000) // Wait 2s for boss hit shake animation to complete (1s animation + 1s buffer)
                            }
                        }
                    }
                    
                    // Set up animation listeners to wait for completion
                    bossHPAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            hpAnimationComplete = true
                            checkAnimationsComplete()
                        }
                    })
                    
                    bossPowerAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            powerAnimationComplete = true
                            checkAnimationsComplete()
                        }
                    })
                    
                    // Start both animations
                    bossHPAnimator.start()
                    bossPowerAnimator.start()
                    
                    // Boss hit animation (red flash/fade) - make it visible (1-2 seconds)
                    // This animation must complete before we check if boss is defeated
                    // Wait for HP bar animation to start (give it time to be visible)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        // Create red overlay effect by animating alpha with red tint
                        val originalAlpha = bossPokemonSprite.alpha
                        val redTint = android.graphics.Color.argb(150, 255, 0, 0) // Semi-transparent red
                        
                        // Flash effect: fade out, fade in, fade out, fade in - make each flash visible (250ms each = 1s total)
                        bossPokemonSprite.animate()
                            .alpha(0.3f)
                            .setDuration(250)
                            .withEndAction {
                                bossPokemonSprite.animate()
                                    .alpha(1f)
                                    .setDuration(250)
                                    .withEndAction {
                                        bossPokemonSprite.animate()
                                            .alpha(0.3f)
                                            .setDuration(250)
                                            .withEndAction {
                                                bossPokemonSprite.animate()
                                                    .alpha(originalAlpha)
                                                    .setDuration(250)
                                                    .withEndAction {
                                                        // Mark boss hit animation as complete
                                                        bossHitAnimationComplete = true
                                                        checkAnimationsComplete()
                                                    }
                                                    .start()
                                            }
                                            .start()
                                    }
                                    .start()
                            }
                            .start()
                    }, 1000) // 1s delay to see HP bar animation start
                }
                .start()
            }, 1500) // 1.5s delay between rounds to see animations
        }, roundDelay) // First round starts immediately, subsequent rounds have delay
    }
    
    /**
     * Final round: "You attack" only (no boss counter). Ensures the player always sees
     * the last hit, boss defeat animation (shake, roll over, grey out), then victory.
     * Called when round > maxRounds instead of jumping straight to endBattle(true).
     */
    private fun battleFinalRound() {
        if (!isBattling) return
        android.util.Log.d("BattleHubActivity", "battleFinalRound: starting final You attack (no boss counter)")
        // Brief pause after "Boss attacks" then show final "You attack!" (no boss counter)
        clearBattleMessage()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isBattling) return@postDelayed
            showBattleMessage("You attack!")
                // Player attack animation
                playerPokemonSprite.animate()
                    .translationX(50f)
                    .scaleX(1.2f)
                    .scaleY(1.2f)
                    .setDuration(1000)
                    .withEndAction {
                        playerPokemonSprite.animate()
                            .translationX(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(1000)
                            .start()
                    }
                    .start()
                // Boss takes final damage
                val bossDamage = 12 + (0..8).random()
                bossHealth = (bossHealth - bossDamage).coerceAtLeast(0)
                bossPowerValue = (bossPowerValue - bossDamage).coerceAtLeast(0)
                val bossHPPercent = (bossHealth * 100 / 100).coerceIn(0, 100)
                val bossHPAnimator = ObjectAnimator.ofInt(bossHP, "progress", bossHP.progress, bossHealth).apply {
                    duration = 2000
                    interpolator = AccelerateDecelerateInterpolator()
                }
                updateBossHPColor(bossHPPercent)
                val bossPowerPercent = (bossPowerValue * 100 / 100).coerceIn(0, 100)
                val bossPowerAnimator = ObjectAnimator.ofInt(bossPower, "progress", bossPower.progress, bossPowerValue).apply {
                    duration = 2000
                    interpolator = AccelerateDecelerateInterpolator()
                }
                updateBossPowerColor(bossPowerPercent)
                var hpAnimationComplete = false
                var powerAnimationComplete = false
                var bossHitAnimationComplete = false
                fun checkAnimationsComplete() {
                    if (!hpAnimationComplete || !powerAnimationComplete || !bossHitAnimationComplete) return
                    // Final round: always show defeat sequence (boss is at 0 or we treat as victory)
                    if (bossHealth <= 0) {
                        clearBattleMessage()
                        val bossName = currentBossPokemon?.name ?: "Boss"
                        showBattleMessage("$bossName was defeated!")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            bossPokemonSprite.animate()
                                .translationX(-20f)
                                .setDuration(300)
                                .withEndAction {
                                    bossPokemonSprite.animate()
                                        .translationX(20f)
                                        .setDuration(300)
                                        .withEndAction {
                                            bossPokemonSprite.animate()
                                                .translationX(-20f)
                                                .setDuration(300)
                                                .withEndAction {
                                                    bossPokemonSprite.animate()
                                                        .translationX(20f)
                                                        .setDuration(300)
                                                        .withEndAction {
                                                            bossPokemonSprite.animate()
                                                                .translationX(0f)
                                                                .setDuration(300)
                                                                .withEndAction {
                                                                    bossPokemonSprite.animate()
                                                                        .rotation(180f)
                                                                        .translationY(100f)
                                                                        .alpha(0.3f)
                                                                        .setDuration(2000)
                                                                        .withEndAction {
                                                                            bossPokemonSprite.setColorFilter(android.graphics.ColorMatrixColorFilter(
                                                                                android.graphics.ColorMatrix().apply { setSaturation(0f) }
                                                                            ))
                                                                            clearBattleMessage()
                                                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                                                showBattleMessage("🎉 Victory! 🎉")
                                                                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                                                    clearBattleMessage()
                                                                                    endBattle(true)
                                                                                }, 2000)
                                                                            }, 1000)
                                                                        }
                                                                        .start()
                                                                }
                                                                .start()
                                                        }
                                                        .start()
                                                }
                                                .start()
                                        }
                                        .start()
                                }
                                .start()
                        }, 2000)
                    } else {
                        endBattle(true)
                    }
                }
                bossHPAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        hpAnimationComplete = true
                        checkAnimationsComplete()
                    }
                })
                bossPowerAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        powerAnimationComplete = true
                        checkAnimationsComplete()
                    }
                })
                bossHPAnimator.start()
                bossPowerAnimator.start()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val originalAlpha = bossPokemonSprite.alpha
                    bossPokemonSprite.animate()
                        .alpha(0.3f)
                        .setDuration(250)
                        .withEndAction {
                            bossPokemonSprite.animate()
                                .alpha(1f)
                                .setDuration(250)
                                .withEndAction {
                                    bossPokemonSprite.animate()
                                        .alpha(0.3f)
                                        .setDuration(250)
                                        .withEndAction {
                                            bossPokemonSprite.animate()
                                                .alpha(originalAlpha)
                                                .setDuration(250)
                                                .withEndAction {
                                                    bossHitAnimationComplete = true
                                                    checkAnimationsComplete()
                                                }
                                                .start()
                                        }
                                        .start()
                                }
                                .start()
                        }
                        .start()
                }, 1000)
        }, 1500)
    }
    
    private fun endBattle(playerWon: Boolean) {
        isBattling = false
        
        // Clear any remaining battle messages
        clearBattleMessage()
        
        if (playerWon && currentBossPokemon != null) {
            // Unlock the boss Pokemon
            val progressManager = DailyProgressManager(this)
            val currentUnlocked = progressManager.getUnlockedPokemonCount()
            progressManager.setUnlockedPokemonCount(currentUnlocked + 1)
            
            // Mark that berries were spent on battle - store in SharedPreferences so it persists across all screens
            val currentContent = try {
                val jsonString = mainContentJson ?: ContentUpdateService().getCachedMainContent(this)
                if (jsonString != null && jsonString.isNotEmpty()) {
                    Gson().fromJson(jsonString, MainContent::class.java)
                } else null
            } catch (e: Exception) {
                null
            }
            if (currentContent != null) {
                // Simple: reset earned berries to 0 when battle is won
                progressManager.resetEarnedBerries()
                // Also store for detecting new required task completions (for reset logic)
                val requiredEarnedStars = progressManager.getEarnedStarsWithoutSpentBerries(currentContent)
                progressManager.setEarnedStarsAtBattleEnd(requiredEarnedStars)
                android.util.Log.d("BattleHubActivity", "Battle won: Reset earned berries to 0")

                // Sync berries spent to cloud after battle victory
                val currentProfile = SettingsManager.readProfile(this) ?: "AM"
                val cloudStorageManager = CloudStorageManager(this)
                lifecycleScope.launch {
                    cloudStorageManager.saveIfEnabled(currentProfile)
                }
            }
            updateBerryMeter()
            
            // Show victory dialog
            showVictory(currentBossPokemon!!)
        } else {
            // Battle failed
            battleButton.isEnabled = true
            battleButton.text = "⚔️ Battle ⚔️"
            android.widget.Toast.makeText(this, "Not enough power! Earn more berries!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showVictory(pokemon: ParsedPokemon) {
        // Load Pokemon sprite in victory screen
        loadPokemonSpriteIntoImageView(victoryPokemonSprite, pokemon.filename)
        victoryPokemonName.text = pokemon.name
        victoryMessage.text = "You caught ${pokemon.name}!\nAdded to your Pokedex!"
        
        victoryOverlay.visibility = View.VISIBLE
        victoryOverlay.alpha = 0f
        victoryOverlay.scaleX = 0.5f
        victoryOverlay.scaleY = 0.5f
        
        victoryOverlay.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(BounceInterpolator())
            .start()
    }
    
    private fun closeVictory() {
        victoryOverlay.visibility = View.GONE
        
        // Clear any remaining battle messages
        clearBattleMessage()
        
        // Reset HP bars to 100
        playerHealth = 100
        bossHealth = 100
        playerHP.progress = 100
        bossHP.progress = 100
        updateBossHPColor(100)
        updatePlayerHPColor(100)
        
        // Reset power bars: boss to 100, player to 0 (so they need to earn berries again)
        bossPowerValue = 100
        playerPowerValue = 0
        bossPower.progress = 100
        playerPower.progress = 0
        updatePlayerPowerColor(0)
        updateBossPowerColor(100)
        
        // Reset sprite states
        playerPokemonSprite.alpha = 1f
        playerPokemonSprite.rotation = 0f
        playerPokemonSprite.translationX = 0f
        playerPokemonSprite.translationY = 0f
        playerPokemonSprite.scaleX = 1f
        playerPokemonSprite.scaleY = 1f
        bossPokemonSprite.alpha = 1f
        bossPokemonSprite.rotation = 0f
        bossPokemonSprite.translationX = 0f
        bossPokemonSprite.translationY = 0f
        bossPokemonSprite.scaleX = 1f
        bossPokemonSprite.scaleY = 1f
        
        // Reload Pokemon data to show new boss and updated player Pokemon
        // This will automatically select the highest unlocked Pokemon (old boss) as the new player Pokemon
        loadPokemonData()
        
        // Reset battle button (will be disabled since player power is 0)
        battleButton.isEnabled = false
        battleButton.text = "⚔️ Battle ⚔️"
        
        // Update grid selection to show the new player Pokemon is selected
        selectedPokemonId?.let { updatePokemonGridSelection() }
        
        // Update earn buttons state - enable practice tasks button if all required tasks are completed
        updateEarnButtonsState()
    }
    
    override fun onResume() {
        super.onResume()

        // CRITICAL: Check for profile changes from cloud BEFORE syncing data
        // This ensures we use the correct profile for the sync
        // Note: This is now async to avoid blocking main thread, so we always refresh title after a brief delay
        SettingsManager.checkAndApplyProfileFromCloud(this)
        
        // Refresh title after a short delay to allow async profile check to complete
        // This ensures UI updates if profile changed (profile changes are rare, so slight delay is acceptable)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            updateTitle()
        }, 100)

        // Run daily_reset_process() and then cloud_sync() when resuming
        val profile = SettingsManager.readProfile(this) ?: "AM"
        android.util.Log.d("BattleHubActivity", "Starting daily reset and sync in onResume for profile $profile")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resetAndSyncManager = DailyResetAndSyncManager(this@BattleHubActivity)
                resetAndSyncManager.dailyResetProcessAndSync(profile)
                android.util.Log.d("BattleHubActivity", "Daily reset and sync completed in onResume for profile $profile")
            } catch (e: Exception) {
                android.util.Log.e("BattleHubActivity", "Error during daily reset and sync in onResume", e)
            }
            runOnUiThread {
                // Refresh UI after sync completes
                updateBerryMeter()
                updateEarnButtonsState()
                updateCountsDisplay()
                updatePokedexTitle()
                // Also refresh header in case profile changed during sync
                updateTitle()
            }
        }

        // Handle berry reset logic when returning to this activity
        // Check if new required tasks were completed since battle ended (when earned berries were reset to 0)
        val progressManager = DailyProgressManager(this)
        val earnedBerries = progressManager.getEarnedBerries()
        
        // Only check if earned berries is 0 (meaning battle happened and reset occurred)
        if (earnedBerries == 0) {
            val currentContent = try {
                val jsonString = mainContentJson ?: ContentUpdateService().getCachedMainContent(this)
                if (jsonString != null && jsonString.isNotEmpty()) {
                    Gson().fromJson(jsonString, MainContent::class.java)
                } else null
            } catch (e: Exception) {
                null
            }
            
            if (currentContent != null) {
                // Calculate actual earned stars to compare
                val currentEarnedStars = progressManager.getEarnedStarsWithoutSpentBerries(currentContent)
                
                val earnedStarsAtBattleEnd = progressManager.getEarnedStarsAtBattleEnd()
                // Only reset tracking if NEW required tasks were completed (earned stars increased since battle ended)
                // This prevents optional tasks from resetting the flag
                if (currentEarnedStars > earnedStarsAtBattleEnd) {
                    progressManager.resetBattleEndTracking()

                    // Sync to cloud when new required tasks are completed
                    val currentProfile = SettingsManager.readProfile(this) ?: "AM"
                    val cloudStorageManager = CloudStorageManager(this)
                    lifecycleScope.launch {
                        cloudStorageManager.saveIfEnabled(currentProfile)
                    }
                }
            }
        }
        // Always refresh mainContentJson from cache/network on resume so we pick up updates
        // (e.g. Trainer Map may have fetched fresh config from GitHub and updated the cache)
        mainContentJson = ContentUpdateService().getCachedMainContent(this)

        // Refresh all displays using persisted data (same as MainActivity)
        updateCountsDisplay()
        updateBerryMeter()
        updateEarnButtonsState()
        updatePokedexTitle()
        setupHeaderButtons() // Refresh header buttons on resume
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // When returning from TrainingMapActivity, sync from cloud first, then refresh displays
        // Tasks may have been completed even if the activity was canceled
        if (requestCode == 2001 || requestCode == 2002 || requestCode == 2003) {
            // Reload mainContentJson from cache/network (Trainer Map may have updated cache with fresh GitHub content)
            mainContentJson = ContentUpdateService().getCachedMainContent(this)

            // Run daily_reset_process() and then cloud_sync() to get latest progress, then refresh UI
            val profile = SettingsManager.readProfile(this) ?: "AM"
            android.util.Log.d("BattleHubActivity", "Starting daily reset and sync in onActivityResult for profile $profile")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val resetAndSyncManager = DailyResetAndSyncManager(this@BattleHubActivity)
                    resetAndSyncManager.dailyResetProcessAndSync(profile)
                    android.util.Log.d("BattleHubActivity", "Daily reset and sync completed in onActivityResult for profile $profile")
                } catch (e: Exception) {
                    android.util.Log.e("BattleHubActivity", "Error during daily reset and sync in onActivityResult", e)
                }
                runOnUiThread {
                    // Force refresh by calling update methods directly after sync completes
                    updateCountsDisplay()
                    updateBerryMeter()
                    updateEarnButtonsState()
                    updatePokedexTitle()
                }
            }
        }

        // When returning from Chores 4 $$, refresh coin display (sync will run on next resume)
        if (requestCode == 2010) {
            updateCountsDisplay()
        }
    }
}
