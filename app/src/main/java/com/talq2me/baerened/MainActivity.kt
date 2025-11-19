package com.talq2me.baerened

import android.content.ContentValues
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.gson.Gson
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import java.util.*

// Import GameData for JSON parsing
import android.content.Intent
import android.text.InputType
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.talq2me.baerened.GameData
import com.talq2me.contract.SettingsContract

//Update Checker
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File


class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var loadingProgressBar: ProgressBar
    lateinit var contentUpdateService: ContentUpdateService
    private lateinit var layout: Layout
    private var currentMainContent: MainContent? = null

    private val updateJsonUrl = "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/config/version.json"
    private var downloadId: Long = -1
    private lateinit var downloadReceiver: BroadcastReceiver

    // Activity result launchers
    lateinit var videoCompletionLauncher: ActivityResultLauncher<Intent>
    lateinit var webGameCompletionLauncher: ActivityResultLauncher<Intent>
    lateinit var chromePageLauncher: ActivityResultLauncher<Intent>

    // UI elements for structured layout
    lateinit var headerLayout: LinearLayout
    lateinit var titleText: TextView
    lateinit var progressLayout: LinearLayout
    lateinit var progressText: TextView
    lateinit var progressBar: ProgressBar
    lateinit var sectionsContainer: LinearLayout
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Check for updates to app
        checkForUpdateIfOnline()

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
        swipeRefreshLayout = findViewById(R.id.mainSwipeRefresh)

        // Initialize content update service
        contentUpdateService = ContentUpdateService()

        // Initialize layout manager
        layout = Layout(this)

        // Setup pull-to-refresh
        setupPullToRefresh()

        // Initialize activity result launchers
        videoCompletionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleVideoCompletion(result)
        }

        webGameCompletionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleWebGameCompletion(result)
        }

        chromePageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleChromePageCompletion(result)
        }

        if (getOrCreateProfile() != null) {
            loadMainContent()
        }

        // Clean up any stale reward data on app start (on background thread to avoid blocking UI)
        lifecycleScope.launch(Dispatchers.IO) {
            cleanupStaleRewardData()
            resetRewardDataForNewDay()
        }

        // Register download completion receiver
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId && id != -1L) {
                    handleDownloadComplete()
                }
            }
        }
        ContextCompat.registerReceiver(this, downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED)

    }

    private fun checkForUpdateIfOnline() {
        if (!isOnline()) {
            // no internet → skip check
            return
        }

        checkForUpdates()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return false
        val actNw = cm.getNetworkCapabilities(nw) ?: return false

        return actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
               actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun checkForUpdates() {
        Thread {
            try {
                val client = OkHttpClient()
                val req = Request.Builder().url(updateJsonUrl).build()
                val res = client.newCall(req).execute()
                val json = JSONObject(res.body!!.string())

                val latest = json.getInt("latestVersionCode")
                val apkUrl = json.getString("apkUrl")
                val current = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()

                if (latest > current) {
                    runOnUiThread { forceUpdateDialog(apkUrl) }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun forceUpdateDialog(apkUrl: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("update required")
            .setMessage("a new version of this app is available. please install it to continue.")
            .setCancelable(false)   // cannot dismiss
            .setPositiveButton("update now") { _, _ ->
                downloadAndInstall(apkUrl)
            }
            .create()

        dialog.show()
    }

    private fun downloadAndInstall(url: String) {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("downloading update…")
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "myapp-update.apk"
            )
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)
    }

    private fun handleDownloadComplete() {
        if (isDestroyed || isFinishing) {
            return
        }
        
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)
        
        if (cursor.moveToFirst()) {
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                val uriString = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                cursor.close()
                runOnUiThread { 
                    if (!isDestroyed && !isFinishing) {
                        showInstallDialog(Uri.parse(uriString))
                    }
                }
            } else {
                cursor.close()
            }
        } else {
            cursor.close()
        }
    }

    private fun showInstallDialog(apkUri: Uri) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("install update")
            .setMessage("the update has been downloaded. please install it now to continue.")
            .setCancelable(false)
            .setPositiveButton("install now") { _, _ ->
                installApk(apkUri)
            }
            .create()
        
        dialog.show()
    }

    private fun installApk(apkUri: Uri) {
        try {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                
                // For Android 7.0+ (API 24+), we need to use FileProvider
                val finalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Convert file:// URI to content:// URI using FileProvider
                    if (apkUri.scheme == "file") {
                        val file = File(apkUri.path ?: "")
                        FileProvider.getUriForFile(
                            this@MainActivity,
                            "${packageName}.fileprovider",
                            file
                        )
                    } else {
                        // Already a content:// URI, use as-is
                        apkUri
                    }
                } else {
                    // For older Android versions, file:// URI is fine
                    apkUri
                }
                
                setDataAndType(finalUri, "application/vnd.android.package-archive")
            }
            
            startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
            Toast.makeText(this, "Error installing update: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleVideoCompletion(result: ActivityResult) {
        android.util.Log.d("MainActivity", "Video result received: resultCode=${result.resultCode}")
        if (result.resultCode == RESULT_OK && result.data != null) {
            val taskId = result.data?.getStringExtra("TASK_ID")
            val taskTitle = result.data?.getStringExtra("TASK_TITLE")
            val stars = result.data?.getIntExtra("TASK_STARS", 0) ?: 0

            if (taskId != null && taskTitle != null) {
                layout.handleVideoCompletion(taskId, taskTitle, stars)
            }
        }
    }

    private fun handleWebGameCompletion(result: ActivityResult) {
        android.util.Log.d("MainActivity", "WebGame result received: resultCode=${result.resultCode}")
        if (result.resultCode == RESULT_OK && result.data != null) {
            val taskId = result.data?.getStringExtra(WebGameActivity.EXTRA_TASK_ID)
            val sectionId = result.data?.getStringExtra(WebGameActivity.EXTRA_SECTION_ID)
            val stars = result.data?.getIntExtra(WebGameActivity.EXTRA_STARS, 0) ?: 0
            val taskTitle = result.data?.getStringExtra(WebGameActivity.EXTRA_TASK_TITLE)
            lifecycleScope.launch(Dispatchers.Main) {
                layout.handleWebGameCompletion(taskId, sectionId, stars, taskTitle)
            }
        }
    }

    private fun handleChromePageCompletion(result: ActivityResult) {
        android.util.Log.d("MainActivity", "ChromePage result received: resultCode=${result.resultCode}")
        if (result.resultCode == RESULT_OK && result.data != null) {
            val taskId = result.data?.getStringExtra(ChromePageActivity.EXTRA_TASK_ID)  // Use task ID for completion tracking
            val sectionId = result.data?.getStringExtra(ChromePageActivity.EXTRA_SECTION_ID)
            val taskTitle = result.data?.getStringExtra(ChromePageActivity.EXTRA_TASK_TITLE)
            val stars = result.data?.getIntExtra(ChromePageActivity.EXTRA_STARS, 0) ?: 0

            if (taskId != null && taskTitle != null) {
                layout.handleChromePageCompletion(taskId, taskTitle, stars, sectionId)
            }
        }
    }

    private fun getOrCreateProfile(): String? {
        SettingsManager.readProfile(this)?.let { return it }

        val profiles = arrayOf("Profile A", "Profile B")
        AlertDialog.Builder(this)
            .setTitle("Select User Profile")
            .setCancelable(false)
            .setItems(profiles) { _, which ->
                val selectedProfile = if (which == 0) "A" else "B"
                SettingsManager.writeProfile(this, selectedProfile)
                finishAffinity()
                startActivity(Intent(this, MainActivity::class.java))
            }
            .show()
        return null
    }

    private fun showChangeProfileDialog() {
        val profiles = arrayOf("Profile A", "Profile B")
        val currentProfile = SettingsManager.readProfile(this)
        AlertDialog.Builder(this)
            .setTitle("Select User Profile")
            .setItems(profiles) { _, which ->
                val selectedProfile = if (which == 0) "A" else "B"
                if (currentProfile != selectedProfile) {
                    SettingsManager.writeProfile(this, selectedProfile)
                    finishAffinity()
                    startActivity(Intent(this, MainActivity::class.java))
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

        val currentPinInput = EditText(this).apply {
            hint = "Enter current PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        dialogLayout.addView(currentPinInput)

        val newPinInput = EditText(this).apply {
            hint = "Enter new PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        dialogLayout.addView(newPinInput)

        val confirmPinInput = EditText(this).apply {
            hint = "Confirm new PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        dialogLayout.addView(confirmPinInput)

        AlertDialog.Builder(this)
            .setTitle("Change PIN")
            .setView(dialogLayout)
            .setPositiveButton("Save") { _, _ ->
                val currentPin = currentPinInput.text.toString()
                val newPin = newPinInput.text.toString()
                val confirmPin = confirmPinInput.text.toString()

                val correctPin = SettingsManager.readPin(this) ?: "1981"
                if (currentPin != correctPin) {
                    Toast.makeText(this, "Current PIN is incorrect!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (newPin.isEmpty() || newPin != confirmPin) {
                    Toast.makeText(this, "New PINs do not match or are empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (newPin.length < 4) {
                    Toast.makeText(this, "PIN must be at least 4 digits!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // All checks passed, save the new PIN
                SettingsManager.writePin(this, newPin)
                Toast.makeText(this, "PIN changed successfully", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun showChangeEmailDialog() {
        val currentEmail = SettingsManager.readEmail(this) ?: ""
        val input = EditText(this).apply {
            hint = "Parent Email Address"
            setText(currentEmail)
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        val container = FrameLayout(this).apply {
            setPadding(50, 20, 50, 20)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Change Parent Email")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newEmail = input.text.toString().trim()
                if (newEmail.isNotEmpty() && android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
                    SettingsManager.writeEmail(this, newEmail)
                    Toast.makeText(this, "Email saved successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun cleanupStaleRewardData() {
        try {
            val progressManager = DailyProgressManager(this)
            val pendingReward = progressManager.getPendingRewardData()

            if (pendingReward != null) {
                val (minutes, timestamp) = pendingReward
                val currentTime = System.currentTimeMillis()
                val oneHourInMillis = 60 * 60 * 1000L

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

            val lastResetDate = progressManager.getLastResetDate()
            val currentDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())

            if (lastResetDate != currentDate) {
                progressManager.clearPendingRewardData()
                android.util.Log.d("MainActivity", "Reset reward data for new day: $currentDate")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error resetting reward data for new day", e)
        }
    }

    override fun onResume() {
        super.onResume()
        layout.refreshProgressDisplay()
        layout.refreshHeaderButtons()
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

    private fun loadMainContent() {
        loadingProgressBar.visibility = View.VISIBLE
        titleText.text = "Loading..."
        headerLayout.visibility = View.GONE
        progressLayout.visibility = View.GONE
        sectionsContainer.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val content = contentUpdateService.fetchMainContent(this@MainActivity)
                if (content != null) {
                    withContext(Dispatchers.Main) {
                        displayContent(content)
                        loadingProgressBar.visibility = View.GONE
                        // Stop refresh indicator if it's showing
                        if (::swipeRefreshLayout.isInitialized && swipeRefreshLayout.isRefreshing) {
                            swipeRefreshLayout.isRefreshing = false
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        titleText.text = "Failed to load content. Please check your connection."
                        loadingProgressBar.visibility = View.GONE
                        // Stop refresh indicator if it's showing
                        if (::swipeRefreshLayout.isInitialized && swipeRefreshLayout.isRefreshing) {
                            swipeRefreshLayout.isRefreshing = false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading main content", e)
                withContext(Dispatchers.Main) {
                    titleText.text = "Error loading content: ${e.message}"
                    loadingProgressBar.visibility = View.GONE
                    // Stop refresh indicator if it's showing
                    if (::swipeRefreshLayout.isInitialized && swipeRefreshLayout.isRefreshing) {
                        swipeRefreshLayout.isRefreshing = false
                    }
                }
            }
        }
    }

    private fun setupPullToRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            loadMainContent()
        }
    }


    private fun displayContent(content: MainContent) {
        currentMainContent = content
        layout.displayContent(content)
    }

    fun getCurrentMainContent(): MainContent? {
        return currentMainContent
    }

    fun getAppVersion(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: packageInfo.longVersionCode.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app version", e)
            "?"
        }
    }

    fun handleHeaderButtonClick(action: String?) {
        when (action) {
            "settings" -> openSettings()
            "openPokedex" -> openPokemonCollection()
            "askForTime" -> showAskForTimeDialog()
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
        val pinInput = EditText(this).apply {
            hint = "Enter Admin PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(50, 50, 50, 50)
        }

        AlertDialog.Builder(this)
            .setTitle("Admin Access")
            .setMessage("Please enter the PIN to access settings.")
            .setView(pinInput)
            .setPositiveButton("Enter") { _, _ ->
                val enteredPin = pinInput.text.toString()
                val correctPin = SettingsManager.readPin(this) ?: "1981" // Use default if not set
                if (enteredPin == correctPin) {
                    showSettingsListDialog()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsListDialog() {
        val settingsOptions = arrayOf("Change Profile", "Change PIN", "Change Parent Email", "Send Progress Report")

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(settingsOptions) { _, which ->
                when (which) {
                    0 -> showChangeProfileDialog()
                    1 -> showChangePinDialog()
                    2 -> showChangeEmailDialog()
                    3 -> sendProgressReportInternal()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAskForTimeDialog() {
        // First, show PIN prompt
        val pinInput = EditText(this).apply {
            hint = "Enter Admin PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(50, 50, 50, 50)
        }

        AlertDialog.Builder(this)
            .setTitle("Admin Access")
            .setMessage("Please enter the PIN to grant reward minutes.")
            .setView(pinInput)
            .setPositiveButton("Enter") { _, _ ->
                val enteredPin = pinInput.text.toString()
                val correctPin = SettingsManager.readPin(this) ?: "1981" // Use default if not set
                if (enteredPin == correctPin) {
                    // PIN is correct, show minutes input dialog
                    showRewardMinutesInputDialog()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRewardMinutesInputDialog() {
        val minutesInput = EditText(this).apply {
            hint = "Reward Minutes to Grant"
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(50, 50, 50, 50)
        }

        AlertDialog.Builder(this)
            .setTitle("Grant Reward Minutes")
            .setMessage("Enter the number of minutes to add to the reward bank:")
            .setView(minutesInput)
            .setPositiveButton("Submit") { _, _ ->
                val minutesText = minutesInput.text.toString()
                val minutes = minutesText.toIntOrNull()
                
                if (minutes != null && minutes > 0) {
                    // Add minutes to reward bank
                    val progressManager = DailyProgressManager(this)
                    val newTotal = progressManager.addRewardMinutes(minutes)
                    
                    // Refresh progress display
                    layout.refreshProgressDisplay()
                    
                    Toast.makeText(this, "Granted $minutes minutes! Total: $newTotal minutes", Toast.LENGTH_LONG).show()
                    Log.d(TAG, "Granted $minutes reward minutes. New total: $newTotal minutes")
                } else {
                    Toast.makeText(this, "Please enter a valid number of minutes", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    fun sendProgressReport(view: android.view.View) {
        sendProgressReportInternal()
    }

    /**
     * Sends progress report programmatically (can be called without a View)
     */
    fun sendProgressReportInternal() {
        try {
            val progressManager = DailyProgressManager(this)
            val timeTracker = TimeTracker(this)
            val reportGenerator = ReportGenerator(this)

            val mainContent = getCurrentMainContent() ?: MainContent()

            val progressReport = progressManager.getComprehensiveProgressReport(mainContent, timeTracker)

            val currentKid = progressManager.getCurrentKid()
            val childName = if (currentKid == "A") "AM" else "BM"

            val report = reportGenerator.generateDailyReport(progressReport, childName, ReportGenerator.ReportFormat.EMAIL)

            // Get parent email from settings
            val parentEmail = SettingsManager.readEmail(this)
            
            if (parentEmail.isNullOrBlank()) {
                Toast.makeText(this, "Please set parent email in settings first", Toast.LENGTH_LONG).show()
                return
            }

            // Use mailto: URI to open Gmail directly
            val emailUri = android.net.Uri.parse("mailto:$parentEmail").buildUpon()
                .appendQueryParameter("subject", "Daily Progress Report - $childName - ${progressReport.date}")
                .appendQueryParameter("body", report)
                .build()

            val emailIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                data = emailUri
            }

            try {
                if (emailIntent.resolveActivity(packageManager) != null) {
                    startActivity(emailIntent)
                } else {
                    // Fallback to generic email chooser if mailto: doesn't work
                    val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(parentEmail))
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Daily Progress Report - $childName - ${progressReport.date}")
                        putExtra(android.content.Intent.EXTRA_TEXT, report)
                    }
                    startActivity(Intent.createChooser(fallbackIntent, "Share Progress Report"))
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error opening email: ${e.message}", Toast.LENGTH_SHORT).show()
                android.util.Log.e("MainActivity", "Error opening email", e)
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
        if (::downloadReceiver.isInitialized) {
            try {
                unregisterReceiver(downloadReceiver)
            } catch (e: Exception) {
                // Receiver may not be registered
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    fun startGame(game: Game, gameContent: String? = null, sectionId: String? = null) {
        Log.d(TAG, "Starting game: ${game.title}")

        when (game.type) {
            "profile_selection" -> {
                selectProfile(game.id)
            }
            else -> {
                if (game.requiresRewardTime && !hasAvailableRewardTime()) {
                    showRewardTimeDialog()
                    return
                }

                if (gameContent != null) {
                    launchGameActivity(game, gameContent, sectionId)
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

        when {
            configUrl.contains("AM_config.json") -> {
                SettingsManager.writeProfile(this, "A")
                Toast.makeText(this, "Selected AM profile", Toast.LENGTH_SHORT).show()
            }
            configUrl.contains("BM_config.json") -> {
                SettingsManager.writeProfile(this, "B")
                Toast.makeText(this, "Selected BM profile", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(this, "Unknown profile configuration", Toast.LENGTH_SHORT).show()
                return
            }
        }

        contentUpdateService.clearCache(this)

        loadMainContent()
    }

    private fun hasAvailableRewardTime(): Boolean {
        return true
    }

    private fun showRewardTimeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reward Time Required")
            .setMessage("You need to earn more stars to unlock this game. Complete more tasks!")
            .setPositiveButton("OK") { _, _ -> }
            .setCancelable(false)
            .show()
    }

    private fun launchGameActivity(game: Game, gameContent: String, sectionId: String? = null) {
        try {
            val gson = Gson()
            val questions = gson.fromJson(gameContent, Array<GameData>::class.java)
            val totalQuestions = game.totalQuestions ?: questions.size

            val currentContent = getCurrentMainContent()
            val isRequired = currentContent?.sections?.any { section ->
                section.id == "required" && section.tasks?.any { it.launch == game.type } == true
            } ?: false

            val intent = Intent(this, com.talq2me.baerened.GameActivity::class.java).apply {
                putExtra("GAME_CONTENT", gameContent)
                putExtra("GAME_TYPE", game.type)
                putExtra("GAME_TITLE", game.title)
                putExtra("TOTAL_QUESTIONS", totalQuestions)
                putExtra("GAME_STARS", game.estimatedTime)
                putExtra("IS_REQUIRED_GAME", isRequired)
                putExtra("BLOCK_OUTLINES", game.blockOutlines)
                sectionId?.let { putExtra("SECTION_ID", it) }
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing game content for ${game.type}", e)
            Toast.makeText(this, "Error loading game content", Toast.LENGTH_SHORT).show()
        }
    }

}

data class MainContent(
    val version: String? = null,
    val title: String? = null,
    val header: Header? = null,
    val progress: Progress? = null,
    val sections: List<Section>? = null,
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
    val videoSequence: String? = null,
    val video: String? = null,
    val playlistId: String? = null,
    val blockOutlines: Boolean? = null,
    val webGame: Boolean? = null,
    val chromePage: Boolean? = null,
    val url: String? = null,
    val rewardId: String? = null,
    val easydays: String? = null,
    val harddays: String? = null,
    val extremedays: String? = null,
    val showdays: String? = null,
    val hidedays: String? = null,
    val displayDays: String? = null
)

data class ChecklistItem(
    val label: String? = null,
    val stars: Int? = null,
    val done: Boolean? = null,
    val id: String? = null,
    val showdays: String? = null,
    val hidedays: String? = null
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
