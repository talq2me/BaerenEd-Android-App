package com.talq2me.baerened

import android.content.ContentValues
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

//Update Checker
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.PackageInstaller
import android.database.Cursor
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainActivity : AppCompatActivity() {

    private lateinit var loadingProgressBar: ProgressBar
    lateinit var contentUpdateService: GitHubGameContentService
    private lateinit var layout: Layout
    private var currentMainContent: MainContent? = null
    private val readAlongPrefs by lazy { getSharedPreferences(READ_ALONG_PREFS_NAME, Context.MODE_PRIVATE) }
    private val boukiliPrefs by lazy { getSharedPreferences(BOUKILI_PREFS_NAME, Context.MODE_PRIVATE) }
    private var wasLaunchedForReadAlong: Boolean = false // Track if we were launched just for Read Along
    private var wasLaunchedForBoukili: Boolean = false // Track if we were launched just for Boukili

    private val updateJsonUrl = "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/config/version.json"
    private var downloadId: Long = -1
    private lateinit var downloadReceiver: BroadcastReceiver
    private val downloadCheckHandler = Handler(Looper.getMainLooper())
    private var downloadCheckRunnable: Runnable? = null
    private var downloadProgressDialog: AlertDialog? = null
    private val updatePrefs by lazy { getSharedPreferences(UPDATE_PREFS_NAME, Context.MODE_PRIVATE) }
    private val rewardPrefs by lazy { getSharedPreferences(REWARD_PREFS_NAME, Context.MODE_PRIVATE) }

    // Activity result launchers
    lateinit var videoCompletionLauncher: ActivityResultLauncher<Intent>
    lateinit var webGameCompletionLauncher: ActivityResultLauncher<Intent>
    lateinit var chromePageLauncher: ActivityResultLauncher<Intent>
    lateinit var emailReportLauncher: ActivityResultLauncher<Intent>
    lateinit var gameCompletionLauncher: ActivityResultLauncher<Intent>
    
    // Store pending reward minutes to launch after email is sent
    private var pendingRewardMinutes: Int? = null
    private var emailLaunchTime: Long = 0
    private var rewardEmailInFlight = false

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
        // Check for profile changes from cloud devices table and apply locally
        // This should be called BEFORE preloadSettings to ensure correct profile is used
        SettingsManager.checkAndApplyProfileFromCloud(this)
        // Preload settings from Supabase on startup
        SettingsManager.preloadSettings(this)
        
        // Check if this activity was launched to handle a specific task (e.g., from TrainingMapActivity)
        val launchTask = intent.getStringExtra("launchTask")
        if (launchTask == "googleReadAlong") {
            wasLaunchedForReadAlong = true
            val taskTitle = intent.getStringExtra("taskTitle") ?: "Google Read Along"
            val sectionId = intent.getStringExtra("sectionId")
            val taskStars = intent.getIntExtra("taskStars", 0)
            // Create a Task object from the intent extras
            val task = Task(
                title = taskTitle,
                launch = "googleReadAlong",
                stars = taskStars
            )
            // Launch Google Read Along directly without loading UI
            launchGoogleReadAlong(task, sectionId)
            // Don't load main content since we're launching Google Read Along
            finish()
            return
        }
        
        if (launchTask == "boukili") {
            wasLaunchedForBoukili = true
            val taskTitle = intent.getStringExtra("taskTitle") ?: "Boukili"
            val sectionId = intent.getStringExtra("sectionId")
            val taskStars = intent.getIntExtra("taskStars", 0)
            // Create a Task object from the intent extras
            val task = Task(
                title = taskTitle,
                launch = "boukili",
                stars = taskStars
            )
            // Launch Boukili directly without loading UI
            launchBoukili(task, sectionId)
            // Don't load main content since we're launching Boukili
            finish()
            return
        }
        
        setContentView(R.layout.activity_main)

        // Remove any leftover downloads that already match the installed build
        cleanupDownloadedUpdatesIfAlreadyInstalled()

        // Check for updates to app (but don't check for completed downloads - let user install from notifications)
        checkForUpdateIfOnline()

        // TTS is pre-warmed in BaerenApplication via TtsManager

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
        contentUpdateService = GitHubGameContentService()

        // Initialize layout manager
        layout = Layout(this)

        // Setup pull-to-refresh
        setupPullToRefresh()

        // Initialize activity result launchers
        android.util.Log.d("MainActivity", "Registering video completion launcher")
        videoCompletionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            android.util.Log.d("MainActivity", "Video completion launcher triggered")
            handleVideoCompletion(result)
        }

        webGameCompletionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleWebGameCompletion(result)
        }

        chromePageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleChromePageCompletion(result)
        }

        gameCompletionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleGameCompletion(result)
        }

        emailReportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // After email activity finishes (user sent email or cancelled), launch reward selection if pending
            val timeSinceEmailLaunch = System.currentTimeMillis() - emailLaunchTime
            Log.d(TAG, "Email activity finished, result code: ${result.resultCode}, rewardEmailInFlight=$rewardEmailInFlight, timeSinceLaunch=${timeSinceEmailLaunch}ms")
            
            // Only process if we actually launched email (not just a quick return)
            // If callback fires too quickly (< 500ms), something went wrong
            if (rewardEmailInFlight && timeSinceEmailLaunch > 500) {
                rewardEmailInFlight = false
                val minutes = getPendingRewardMinutes()
                Log.d(TAG, "After email completed, pending reward minutes: $minutes")
                if (minutes != null && minutes > 0) {
                    clearPendingRewardState()
                    // Launch BaerenLock with reward minutes
                    launchRewardSelectionActivity(minutes)
                }
            } else if (timeSinceEmailLaunch <= 500) {
                Log.w(MainActivity.TAG, "Email callback fired too quickly (${timeSinceEmailLaunch}ms), skipping app-side report retry")
                rewardEmailInFlight = false
                val minutes = getPendingRewardMinutes()
                if (minutes != null && minutes > 0) {
                    clearPendingRewardState()
                    launchRewardSelectionActivity(minutes)
                }
            }
        }

        if (getOrCreateProfile() != null) {
            loadMainContent()
        }

        // Register download completion receiver (just for logging, no install prompts)
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1L
                Log.d(TAG, "Download completed: id=$id. User can install from notification.")
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
                val current = packageManager.getPackageInfo(packageName, 0).versionCode

                if (latest > current) {
                    handleUpdateAvailability(latest, apkUrl)
                } else {
                    clearPendingUpdateState()
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun handleUpdateAvailability(latestVersion: Int, apkUrl: String) {
        // Check if this version is already downloaded
        val downloadedFile = findDownloadedApkForVersion(latestVersion)
        if (downloadedFile != null && downloadedFile.exists()) {
            Log.d(TAG, "Update version $latestVersion already downloaded. User can install from Downloads/notifications.")
            return // Don't prompt, user can install manually
        }
        
        // Show dialog to download
        runOnUiThread { 
            if (!isDestroyed && !isFinishing) {
                forceUpdateDialog(latestVersion, apkUrl)
            }
        }
    }

    private fun forceUpdateDialog(targetVersion: Int, apkUrl: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("update required")
            .setMessage("a new version of this app is available. please install it to continue.")
            .setCancelable(false)   // cannot dismiss
            .setPositiveButton("update now") { _, _ ->
                downloadAndInstall(targetVersion, apkUrl)
            }
            .create()

        dialog.show()
    }

    private fun downloadAndInstall(targetVersion: Int, url: String) {
        // Check if this version is already downloaded
        val existingFile = findDownloadedApkForVersion(targetVersion)
        if (existingFile != null && existingFile.exists()) {
            Log.d(TAG, "APK for version $targetVersion already downloaded")
            Toast.makeText(this, "Update already downloaded. Install it from your Downloads folder or notifications.", Toast.LENGTH_LONG).show()
            return
        }

        val targetFileName = getApkFileName(targetVersion)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val targetFile = File(downloadsDir, targetFileName)
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(getDownloadNotificationTitle(targetVersion))
            .setDescription("Tap the notification when download completes to install")
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                targetFileName
            )
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)
        Log.d(TAG, "Download started with ID: $downloadId")
        updatePrefs.edit()
            .putInt(KEY_PENDING_VERSION, targetVersion)
            .putString(KEY_PENDING_URL, url)
            .putString(KEY_PENDING_FILE_NAME, targetFileName)
            .putLong(KEY_PENDING_DOWNLOAD_ID, downloadId)
            .apply()
        
        // Show toast telling user to install from notification
        Toast.makeText(this, "Download started! Install from the notification when it completes.", Toast.LENGTH_LONG).show()
    }
    
    private fun cleanupDownloadedUpdatesIfAlreadyInstalled() {
        val currentVersion = getCurrentAppVersionCode()
        if (currentVersion == -1) {
            return
        }
        listDownloadedApkFiles().forEach { file ->
            val fileVersion = getApkVersionCode(file)
            if (fileVersion != null && fileVersion <= currentVersion) {
                if (file.delete()) {
                    Log.d(TAG, "Deleted stale update ${file.name} (version $fileVersion)")
                }
                removeDownloadEntriesForFile(file)
            }
        }

        val pendingVersion = updatePrefs.getInt(KEY_PENDING_VERSION, -1)
        if (pendingVersion != -1 && currentVersion >= pendingVersion) {
            clearPendingUpdateState()
        }
    }

    private fun findDownloadedApkForVersion(targetVersion: Int): File? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val preferred = File(downloadsDir, getApkFileName(targetVersion))
        if (preferred.exists()) {
            return preferred
        }
        return listDownloadedApkFiles().firstOrNull { file ->
            getApkVersionCode(file) == targetVersion
        }
    }

    private fun listDownloadedApkFiles(): List<File> {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists() || !downloadsDir.isDirectory) {
            return emptyList()
        }
        return downloadsDir.listFiles { _, name ->
            name.startsWith(APK_FILE_PREFIX) && name.lowercase(Locale.getDefault()).endsWith(".apk")
        }?.toList() ?: emptyList()
    }

    @Suppress("DEPRECATION")
    private fun getApkVersionCode(apkFile: File): Int? {
        if (!apkFile.exists()) {
            return null
        }
        return try {
            val packageInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            packageInfo?.applicationInfo?.apply {
                sourceDir = apkFile.absolutePath
                publicSourceDir = apkFile.absolutePath
            }
            packageInfo?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    it.versionCode
                } else {
                    @Suppress("DEPRECATION")
                    it.versionCode
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read APK version for ${apkFile.name}", e)
            null
        }
    }

    private fun removeDownloadEntriesForFile(targetFile: File) {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query())
        val idsToRemove = mutableListOf<Long>()
        try {
            while (cursor.moveToNext()) {
                val file = resolveFileFromCursor(cursor)
                if (file != null && file.absolutePath == targetFile.absolutePath) {
                    idsToRemove.add(cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing download entries for ${targetFile.name}", e)
        } finally {
            cursor.close()
        }
        idsToRemove.forEach { dm.remove(it) }
    }

    private fun clearPendingUpdateState() {
        updatePrefs.edit()
            .remove(KEY_PENDING_VERSION)
            .remove(KEY_PENDING_URL)
            .remove(KEY_PENDING_FILE_NAME)
            .remove(KEY_PENDING_DOWNLOAD_ID)
            .apply()
        downloadId = -1L
    }

    private fun getCurrentAppVersionCode(): Int {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.versionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read current version code", e)
            -1
        }
    }

    private fun getApkFileName(version: Int): String = "${APK_FILE_PREFIX}-v$version.apk"

    private fun getDownloadNotificationTitle(version: Int): String = "BaerenEd update v$version"

    private fun isUpdateDownloadEntry(localUri: String?, title: String?): Boolean {
        val normalizedTitle = title?.lowercase(Locale.getDefault()) ?: ""
        val normalizedUri = localUri?.lowercase(Locale.getDefault()) ?: ""
        return normalizedTitle.contains("update") || normalizedUri.contains(APK_FILE_PREFIX)
    }

    @Suppress("DEPRECATION")
    private fun resolveFileFromCursor(cursor: Cursor): File? {
        return try {
            val legacyPath = try {
                cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME))
            } catch (e: IllegalArgumentException) {
                null
            }
            if (!legacyPath.isNullOrBlank()) {
                return File(legacyPath)
            }
            val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            if (!localUri.isNullOrBlank()) {
                val parsed = Uri.parse(localUri)
                val path = parsed.path
                if (!path.isNullOrBlank()) {
                    return File(path)
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unable to resolve file from cursor", e)
            null
        }
    }

    private fun determineDownloadedVersion(apkFile: File?, uri: Uri?, title: String? = null): Int? {
        val candidates = listOfNotNull(
            apkFile?.let { getApkVersionCode(it) },
            apkFile?.name?.let { parseVersionFromName(it) },
            uri?.lastPathSegment?.let { parseVersionFromName(it) },
            title?.let { parseVersionFromName(it) },
            updatePrefs.getInt(KEY_PENDING_VERSION, -1).takeIf { it != -1 }
        )
        return candidates.firstOrNull()
    }

    private fun parseVersionFromName(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val match = VERSION_IN_NAME_REGEX.find(raw)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /**
     * Simple Google Read Along completion check.
     * When BaerenEd comes back to foreground, check if enough time has passed since launch.
     */
    private fun checkGoogleReadAlongCompletion() {
        when (val outcome = ExternalReadingCompletionBridge.checkReadAlong(this)) {
            is ExternalReadingCompletionBridge.Outcome.None,
            is ExternalReadingCompletionBridge.Outcome.TooSoon -> return

            is ExternalReadingCompletionBridge.Outcome.NotEnoughTime -> {
                val remainingSeconds = outcome.minSeconds - outcome.elapsedSeconds
                Toast.makeText(
                    this,
                    "Stay in Google Read Along for at least ${outcome.minSeconds}s to earn rewards (only ${outcome.elapsedSeconds}s). Need ${remainingSeconds}s more.",
                    Toast.LENGTH_LONG
                ).show()
                Log.d(TAG, "Read Along not completed yet: ${outcome.elapsedSeconds}s < ${outcome.minSeconds}s")
            }

            is ExternalReadingCompletionBridge.Outcome.Complete -> {
                layout.handleManualTaskCompletion(
                    taskId = outcome.taskId,
                    taskTitle = outcome.taskTitle,
                    stars = outcome.stars,
                    sectionId = outcome.sectionId,
                    completionMessage = "📚 Google Read Along completed! Great job reading!"
                )
                layout.refreshSections()
                Toast.makeText(
                    this,
                    "Great reading! You spent ${outcome.elapsedSeconds}s in Read Along.",
                    Toast.LENGTH_SHORT
                ).show()
                ExternalReadingCompletionBridge.clearReadAlong(this)
            }
        }
    }

    /**
     * Simple Boukili completion check.
     * When BaerenEd comes back to foreground, check if enough time has passed since launch.
     */
    private fun checkBoukiliCompletion() {
        when (val outcome = ExternalReadingCompletionBridge.checkBoukili(this)) {
            is ExternalReadingCompletionBridge.Outcome.None,
            is ExternalReadingCompletionBridge.Outcome.TooSoon -> return

            is ExternalReadingCompletionBridge.Outcome.NotEnoughTime -> {
                val remainingSeconds = outcome.minSeconds - outcome.elapsedSeconds
                Toast.makeText(
                    this,
                    "Stay in Boukili for at least ${outcome.minSeconds}s to earn rewards (only ${outcome.elapsedSeconds}s). Need ${remainingSeconds}s more.",
                    Toast.LENGTH_LONG
                ).show()
                Log.d(TAG, "Boukili not completed yet: ${outcome.elapsedSeconds}s < ${outcome.minSeconds}s")
            }

            is ExternalReadingCompletionBridge.Outcome.Complete -> {
                layout.handleManualTaskCompletion(
                    taskId = outcome.taskId,
                    taskTitle = outcome.taskTitle,
                    stars = outcome.stars,
                    sectionId = outcome.sectionId,
                    completionMessage = "📚 Boukili completed! Great job reading!"
                )
                layout.refreshSections()
                Toast.makeText(
                    this,
                    "Great reading! You spent ${outcome.elapsedSeconds}s in Boukili.",
                    Toast.LENGTH_SHORT
                ).show()
                ExternalReadingCompletionBridge.clearBoukili(this)
            }
        }
    }

    /**
     * Handles Google Read Along completion notification from BaerenLock.
     * Marks the task as complete if it's found in the current content.
     */
    override fun onPause() {
        super.onPause()
        // No special handling needed - the start time is already saved when launching
    }

    private fun isDeviceOwner(): Boolean {
        return try {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            devicePolicyManager.isDeviceOwnerApp(packageName)
        } catch (e: Exception) {
            false
        }
    }
    
    @Suppress("UNUSED")
    private fun installSilentlyAsDeviceOwner(file: File?): Boolean {
        if (file == null || !file.exists()) {
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use PackageInstaller for Android 8.0+
                val packageInstaller = packageManager.packageInstaller
                val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = packageInstaller.createSession(sessionParams)
                val session = packageInstaller.openSession(sessionId)
                
                val inputStream = FileInputStream(file)
                val outputStream = session.openWrite("package", 0, -1)
                
                inputStream.copyTo(outputStream)
                session.fsync(outputStream)
                inputStream.close()
                outputStream.close()
                
                val intent = Intent(this, MainActivity::class.java).apply {
                    action = "INSTALL_COMPLETE"
                }
                val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                
                session.commit(pendingIntent.intentSender)
                session.close()
                
                Log.d(TAG, "Silent install initiated via PackageInstaller")
                true
            } else {
                // For Android < 8.0, try DevicePolicyManager
                val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // This requires special permissions and setup
                    Log.d(TAG, "Device owner install not fully supported on Android < 8.0")
                    false
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in silent install", e)
            false
        }
    }

    private fun handleVideoCompletion(result: ActivityResult) {
        android.util.Log.d("MainActivity", "VIDEO COMPLETION HANDLER CALLED: resultCode=${result.resultCode}, data=${result.data != null}")
        // Accept both RESULT_OK and RESULT_CANCELED for videos, as some video types may return CANCELED on completion
        if ((result.resultCode == RESULT_OK || result.resultCode == RESULT_CANCELED) && result.data != null) {
            android.util.Log.d("MainActivity", "VIDEO COMPLETION: Processing video completion (resultCode: ${result.resultCode})")
            val taskId = result.data?.getStringExtra("TASK_ID")
            val taskTitle = result.data?.getStringExtra("TASK_TITLE")
            val stars = result.data?.getIntExtra("TASK_STARS", 0) ?: 0
            val videoFile = result.data?.getStringExtra("VIDEO_FILE")
            val videoIndex = result.data?.getIntExtra("VIDEO_INDEX", -1)

            if (taskId != null && taskTitle != null) {
                val indexToPass = if (videoIndex != -1) videoIndex else null
                layout.handleVideoCompletion(taskId, taskTitle, stars, videoFile, indexToPass)
            } else {
                android.util.Log.d("MainActivity", "VIDEO COMPLETION: Missing taskId or taskTitle in data")
            }
        } else {
            android.util.Log.d("MainActivity", "VIDEO COMPLETION: Ignoring result - wrong resultCode or no data (resultCode=${result.resultCode}, hasData=${result.data != null})")
        }
    }

    private fun handleWebGameCompletion(result: ActivityResult) {
        android.util.Log.d("MainActivity", "WebGame result received: resultCode=${result.resultCode}")
        val taskId = result.data?.getStringExtra(WebGameActivity.EXTRA_TASK_ID)
        val wasFromBattleHub = taskId?.startsWith("battleHub_") == true
        
        if (result.resultCode == RESULT_OK && result.data != null) {
            val sectionId = result.data?.getStringExtra(WebGameActivity.EXTRA_SECTION_ID)
            val stars = result.data?.getIntExtra(WebGameActivity.EXTRA_STARS, 0) ?: 0
            val taskTitle = result.data?.getStringExtra(WebGameActivity.EXTRA_TASK_TITLE)
            
            lifecycleScope.launch(Dispatchers.Main) {
                layout.handleWebGameCompletion(taskId, sectionId, stars, taskTitle)

                // If game was launched from battle hub or gym map, refresh the embedded views
                if (wasFromBattleHub || taskId?.startsWith("gymMap_") == true || taskId?.startsWith("trainingMap_") == true) {
                    Log.d(TAG, "Game completed from battle hub, gym map, or training map, refreshing embedded views")
                    layout.refreshBattleHub()
                    layout.refreshGymMap()
                    layout.refreshTrainingMap()
                }
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
    
    private fun handleGameCompletion(result: ActivityResult) {
        android.util.Log.d("MainActivity", "Game result received: resultCode=${result.resultCode}")
        if (result.resultCode == RESULT_OK && result.data != null) {
            val battleHubTaskId = result.data?.getStringExtra("BATTLE_HUB_TASK_ID")
            val gameStars = result.data?.getIntExtra("GAME_STARS", 0) ?: 0
            val gameType = result.data?.getStringExtra("GAME_TYPE")
            
            val sectionId = result.data?.getStringExtra("SECTION_ID")
            
            // If game was launched from battle hub/gym map OR from required/optional section, refresh views
            val shouldRefresh = battleHubTaskId != null || (sectionId == "required" || sectionId == "optional")
            
            if (shouldRefresh) {
                Log.d(TAG, "Game completed (battleHub=${battleHubTaskId != null}, section=$sectionId), refreshing embedded views")
                layout.refreshBattleHub()
                layout.refreshGymMap()
                layout.refreshTrainingMap()
            }
        }
    }

    private fun getOrCreateProfile(): String? {
        SettingsManager.readProfile(this)?.let { return it }

        val availableProfiles = SettingsManager.getAvailableProfiles(this)
        val displayNames = SettingsManager.getProfileDisplayNames()
        val profileDisplayNames = availableProfiles.map { displayNames[it] ?: "Profile $it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select User Profile")
            .setCancelable(false)
            .setItems(profileDisplayNames) { _, which ->
                val selectedProfile = availableProfiles[which]
                SettingsManager.writeProfile(this, selectedProfile)
                finishAffinity()
                startActivity(Intent(this, MainActivity::class.java))
            }
            .show()
        return null
    }

    private fun showChangeProfileDialog() {
        val availableProfiles = SettingsManager.getAvailableProfiles(this)
        val displayNames = SettingsManager.getProfileDisplayNames()
        val profileDisplayNames = availableProfiles.map { displayNames[it] ?: "Profile $it" }.toTypedArray()
        val currentProfile = SettingsManager.readProfile(this)
        AlertDialog.Builder(this)
            .setTitle("Select User Profile")
            .setItems(profileDisplayNames) { _, which ->
                val selectedProfile = availableProfiles[which]
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

    override fun onResume() {
        super.onResume()
        
        // Check for profile changes from cloud devices table and apply locally
        SettingsManager.checkAndApplyProfileFromCloud(this)
        
        // Simple Google Read Along completion check
        checkGoogleReadAlongCompletion()
        
        // Simple Boukili completion check
        checkBoukiliCompletion()
        
        // If we were launched just for Read Along and have handled the return, finish to go back to TrainingMapActivity
        if (wasLaunchedForReadAlong) {
            // Check if we just completed it
            val startTime = readAlongPrefs.getLong(KEY_READ_ALONG_START_TIME, -1L)
            if (startTime <= 0L) {
                // No pending Read Along, we can finish
                Log.d(TAG, "Read Along completed or cancelled, finishing MainActivity to return to TrainingMapActivity")
                finish()
                return
            }
            // Still pending, don't load content yet
            return
        }
        
        // If we were launched just for Boukili and have handled the return, finish to go back to TrainingMapActivity
        if (wasLaunchedForBoukili) {
            // Check if we just completed it
            val startTime = boukiliPrefs.getLong(KEY_BOUKILI_START_TIME, -1L)
            if (startTime <= 0L) {
                // No pending Boukili, we can finish
                Log.d(TAG, "Boukili completed or cancelled, finishing MainActivity to return to TrainingMapActivity")
                finish()
                return
            }
            // Still pending, don't load content yet
            return
        }
        
        // Check if battle hub requested to show training map
        val battleHubPrefs = getSharedPreferences("battle_hub_prefs", Context.MODE_PRIVATE)
        val showTrainingMap = battleHubPrefs.getBoolean("showTrainingMap", false)
        val showOptionalTrainingMap = battleHubPrefs.getBoolean("showOptionalTrainingMap", false)
        val showBonusTrainingMap = battleHubPrefs.getBoolean("showBonusTrainingMap", false)
        
        // Don't trigger reward launch here when email is in flight - wait for ActivityResult callback
        // Only check for other pending rewards if email is not in flight
        if (!rewardEmailInFlight) {
            triggerPendingRewardLaunch()
        } else {
            Log.d(TAG, "onResume: Email is in flight, waiting for ActivityResult callback before launching BaerenLock")
        }
        
        layout.refreshProgressDisplay()
        layout.refreshHeaderButtons()
        // Rebuild sections so task/checklist completion state (green) is updated after returning from a game
        layout.refreshSections()
        
        // Show training map if requested, otherwise show normal content
        if (showTrainingMap) {
            battleHubPrefs.edit().remove("showTrainingMap").apply()
            Log.d(TAG, "Showing training map from battle hub")
            layout.showTrainingMap()
        } else if (showOptionalTrainingMap) {
            battleHubPrefs.edit().remove("showOptionalTrainingMap").apply()
            if (canShowOptionalTrainingMapNow()) {
                Log.d(TAG, "Showing optional training map from battle hub")
                layout.showOptionalTrainingMap()
            } else {
                Log.d(TAG, "Blocking optional training map: required tasks are not all complete for current day")
                layout.showTrainingMap()
            }
        } else if (showBonusTrainingMap) {
            battleHubPrefs.edit().remove("showBonusTrainingMap").apply()
            Log.d(TAG, "Showing bonus training map from battle hub")
            layout.showBonusTrainingMap()
        }
    }

    /**
     * Optional (extra practice) map should only be shown once required tasks are completed.
     * This blocks stale persisted flags from reopening optional practice after an overnight restart.
     */
    private fun canShowOptionalTrainingMapNow(): Boolean {
        val requiredTasks = DailyProgressManager(this).getRequiredTasksMap()
        if (requiredTasks.isEmpty()) return false
        return requiredTasks.values.all { progress ->
            progress.status.equals("complete", ignoreCase = true)
        }
    }

    private fun loadMainContent() {
        loadingProgressBar.visibility = View.VISIBLE
        titleText.text = "Syncing..."
        headerLayout.visibility = View.GONE
        progressLayout.visibility = View.GONE
        sectionsContainer.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val profile = SettingsManager.readProfile(this@MainActivity) ?: "AM"
            val result = runCatching {
                DbProfileSessionLoader(this@MainActivity).loadAfterDailyResetRpcThenApply(profile)
            }.getOrElse { Result.failure(it) }
            withContext(Dispatchers.Main) {
                loadingProgressBar.visibility = View.GONE
                if (::swipeRefreshLayout.isInitialized && swipeRefreshLayout.isRefreshing) {
                    swipeRefreshLayout.isRefreshing = false
                }
                if (result.isSuccess) {
                    titleText.text = "Use Battle Hub / Training Map"
                } else {
                    titleText.text = "Could not sync progress. Check internet."
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
            packageInfo.versionName ?: packageInfo.versionCode.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app version", e)
            "?"
        }
    }

    fun handleHeaderButtonClick(action: String?) {
        when (action) {
            "settings" -> openSettings()
            "openPokedex" -> openPokemonCollection()
            "openBattleHub" -> openBattleHub()
            "askForTime" -> showAskForTimeDialog()
            null -> Log.w(TAG, "Header button action is null")
            else -> Log.d(TAG, "Unknown header button action: $action")
        }
    }
    
    /** Refetches profile row from Supabase after writes (RPCs already updated DB). */
    fun saveToCloudIfEnabled() {
        val profile = SettingsManager.readProfile(this) ?: "A"
        if (!SupabaseInterface().isConfigured()) return
        lifecycleScope.launch(Dispatchers.IO) {
            DbProfileSessionLoader(this@MainActivity).loadAfterDailyResetRpcThenApply(profile)
        }
    }

    fun launchGoogleReadAlong(task: Task, sectionId: String?) {
        android.util.Log.d(TAG, "Launching Google Read Along for task: ${task.title}")
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.seekh")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                
                // Simple approach: Just save the start timestamp and task info
                val taskId = task.launch ?: "googleReadAlong"
                val taskTitle = task.title ?: "Google Read Along"
                val stars = task.stars ?: 0
                val startTimeMs = System.currentTimeMillis()
                
                // Save to SharedPreferences
                readAlongPrefs.edit()
                    .putString(KEY_READ_ALONG_TASK_ID, taskId)
                    .putString(KEY_READ_ALONG_TASK_TITLE, taskTitle)
                    .putInt(KEY_READ_ALONG_STARS, stars)
                    .putString(KEY_READ_ALONG_SECTION_ID, sectionId ?: "")
                    .putLong(KEY_READ_ALONG_START_TIME, startTimeMs)
                    .apply()
                
                Log.d(TAG, "Saved Google Read Along start time: $startTimeMs for task: $taskTitle")
                
                startActivity(intent)
            } else {
                Toast.makeText(this, "Google Read Along app is not installed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Google Read Along", e)
            Toast.makeText(this, "Error launching Google Read Along: ${e.message}", Toast.LENGTH_SHORT).show()
            // Clear the start time on error
            readAlongPrefs.edit()
                .remove(KEY_READ_ALONG_START_TIME)
                .remove(KEY_READ_ALONG_TASK_ID)
                .remove(KEY_READ_ALONG_TASK_TITLE)
                .remove(KEY_READ_ALONG_STARS)
                .remove(KEY_READ_ALONG_SECTION_ID)
                .apply()
        }
    }

    fun launchBoukili(task: Task, sectionId: String?) {
        android.util.Log.d(TAG, "Launching Boukili for task: ${task.title}")
        val packageName = "ca.boukili.app"
        
        try {
            // Check if package is installed first
            val packageInfo = try {
                packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                false
            }
            
            if (!packageInfo) {
                Log.w(TAG, "Boukili app (package: $packageName) is not installed")
                Toast.makeText(this, "Boukili app is not installed", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Try to get launch intent
            var intent = packageManager.getLaunchIntentForPackage(packageName)
            
            // If getLaunchIntentForPackage returns null (can happen on Android 11+ due to package visibility),
            // try querying for launcher activities
            if (intent == null) {
                Log.d(TAG, "getLaunchIntentForPackage returned null, querying for launcher activities")
                try {
                    val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setPackage(packageName)
                    }
                    val resolveInfos = packageManager.queryIntentActivities(launcherIntent, 0)
                    if (resolveInfos.isNotEmpty()) {
                        val resolveInfo = resolveInfos[0]
                        val activityInfo = resolveInfo.activityInfo
                        intent = Intent().apply {
                            setClassName(activityInfo.packageName, activityInfo.name)
                        }
                        Log.d(TAG, "Found launcher activity: ${activityInfo.name}")
                    } else {
                        Log.w(TAG, "No launcher activities found for package: $packageName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to query launcher activities for Boukili", e)
                }
            }
            
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                
                // Simple approach: Just save the start timestamp and task info
                val taskId = task.launch ?: "boukili"
                val taskTitle = task.title ?: "Boukili"
                val stars = task.stars ?: 0
                val startTimeMs = System.currentTimeMillis()
                
                // Save to SharedPreferences
                boukiliPrefs.edit()
                    .putString(KEY_BOUKILI_TASK_ID, taskId)
                    .putString(KEY_BOUKILI_TASK_TITLE, taskTitle)
                    .putInt(KEY_BOUKILI_STARS, stars)
                    .putString(KEY_BOUKILI_SECTION_ID, sectionId ?: "")
                    .putLong(KEY_BOUKILI_START_TIME, startTimeMs)
                    .apply()
                
                Log.d(TAG, "Saved Boukili start time: $startTimeMs for task: $taskTitle")
                Log.d(TAG, "Starting Boukili with intent: $intent")
                
                startActivity(intent)
            } else {
                Log.e(TAG, "Could not create launch intent for Boukili")
                Toast.makeText(this, "Unable to launch Boukili app", Toast.LENGTH_SHORT).show()
            }
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "Activity not found for Boukili", e)
            Toast.makeText(this, "Boukili app is not available", Toast.LENGTH_SHORT).show()
            // Clear the start time on error
            boukiliPrefs.edit()
                .remove(KEY_BOUKILI_START_TIME)
                .remove(KEY_BOUKILI_TASK_ID)
                .remove(KEY_BOUKILI_TASK_TITLE)
                .remove(KEY_BOUKILI_STARS)
                .remove(KEY_BOUKILI_SECTION_ID)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Boukili", e)
            Toast.makeText(this, "Error launching Boukili: ${e.message}", Toast.LENGTH_SHORT).show()
            // Clear the start time on error
            boukiliPrefs.edit()
                .remove(KEY_BOUKILI_START_TIME)
                .remove(KEY_BOUKILI_TASK_ID)
                .remove(KEY_BOUKILI_TASK_TITLE)
                .remove(KEY_BOUKILI_STARS)
                .remove(KEY_BOUKILI_SECTION_ID)
                .apply()
        }
    }

    fun openPokemonCollection() {
        Log.d(TAG, "Opening Pokemon collection")
        val intent = Intent(this, PokemonActivity::class.java)
        startActivity(intent)
    }

    fun openBattleHub() {
        Log.d(TAG, "Opening Pokemon Battle Hub")
        val intent = Intent(this, WebGameActivity::class.java).apply {
            putExtra(WebGameActivity.EXTRA_GAME_URL, "file:///android_asset/html/pokemonBattleHub.html")
            putExtra(WebGameActivity.EXTRA_TASK_ID, "battleHub")
            putExtra(WebGameActivity.EXTRA_TASK_TITLE, "Pokemon Battle Hub")
        }
        webGameCompletionLauncher.launch(intent)
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
        val settingsOptions = arrayOf("Change Profile", "Change PIN", "Change Parent Email")

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(settingsOptions) { _, which ->
                when (which) {
                    0 -> showChangeProfileDialog()
                    1 -> showChangePinDialog()
                    2 -> showChangeEmailDialog()
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
                    lifecycleScope.launch {
                        val rawProfile = SettingsManager.readProfile(this@MainActivity) ?: "AM"
                        val profile = when (rawProfile) {
                            "A" -> "AM"
                            "B" -> "BM"
                            else -> rawProfile
                        }
                        val result = SupabaseInterface().invokeAddRewardTime(profile, minutes)
                        if (result.isSuccess) {
                            Toast.makeText(this@MainActivity, "Granted $minutes minutes", Toast.LENGTH_LONG).show()
                            Log.d(TAG, "Granted $minutes reward minutes via DB for profile=$profile (raw=$rawProfile)")
                            layout.refreshProgressDisplay()
                        } else {
                            val message = result.exceptionOrNull()?.message ?: "unknown error"
                            Log.e(TAG, "Failed granting reward minutes via DB for profile=$profile (raw=$rawProfile): $message")
                            Toast.makeText(this@MainActivity, "Could not grant minutes: $message", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Please enter a valid number of minutes", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    /**
     * Launches the RewardSelectionActivity with the specified reward minutes
     */
    private fun launchRewardSelectionActivity(rewardMinutes: Int) {
        val intent = Intent(this, RewardSelectionActivity::class.java).apply {
            putExtra(RewardSelectionActivity.EXTRA_REWARD_MINUTES, rewardMinutes)
        }
        startActivity(intent)
    }

    private fun storePendingRewardMinutes(minutes: Int) {
        pendingRewardMinutes = minutes
        rewardPrefs.edit().putInt(KEY_PENDING_REWARD_MINUTES, minutes).apply()
    }

    private fun clearPendingRewardState() {
        pendingRewardMinutes = null
        rewardPrefs.edit().remove(KEY_PENDING_REWARD_MINUTES).apply()
    }

    private fun getPendingRewardMinutes(): Int? {
        val inMemory = pendingRewardMinutes
        if (inMemory != null && inMemory > 0) return inMemory
        val persisted = rewardPrefs.getInt(KEY_PENDING_REWARD_MINUTES, 0)
        return if (persisted > 0) persisted else null
    }

    private fun triggerPendingRewardLaunch(force: Boolean = false) {
        if (!force && rewardEmailInFlight) {
            return
        }
        val minutes = getPendingRewardMinutes() ?: return
        clearPendingRewardState()
        launchRewardSelectionActivity(minutes)
    }

    private fun displayProfileSelection(content: MainContent) {
        layout.displayProfileSelection(content)
    }

    override fun onDestroy() {
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
        const val TAG = "MainActivity"
        internal const val MIN_READ_ALONG_DURATION_MS = 30_000L
        internal const val UPDATE_PREFS_NAME = "update_manager"
        internal const val KEY_PENDING_VERSION = "pending_version"
        internal const val KEY_PENDING_URL = "pending_url"
        internal const val KEY_PENDING_FILE_NAME = "pending_file_name"
        internal const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"
        internal const val APK_FILE_PREFIX = "myapp-update"
        internal const val REWARD_PREFS_NAME = "reward_manager"
        internal const val KEY_PENDING_REWARD_MINUTES = "pending_reward_minutes"
        internal val VERSION_IN_NAME_REGEX = Regex("v(\\d+)")
        internal const val GMAIL_PACKAGE = "com.google.android.gm"
        internal const val GMAIL_COMPOSE_CLASS = "com.google.android.gm.ComposeActivityGmail"
        internal const val READ_ALONG_PREFS_NAME = "read_along_session"
        internal const val KEY_READ_ALONG_TASK_ID = "read_along_task_id"
        internal const val KEY_READ_ALONG_TASK_TITLE = "read_along_task_title"
        internal const val KEY_READ_ALONG_STARS = "read_along_stars"
        internal const val KEY_READ_ALONG_SECTION_ID = "read_along_section_id"
        internal const val KEY_READ_ALONG_START_TIME = "read_along_start_time"
        internal const val KEY_READ_ALONG_HAS_PAUSED = "read_along_has_paused"
        internal const val BOUKILI_PREFS_NAME = "boukili_session"
        internal const val KEY_BOUKILI_TASK_ID = "boukili_task_id"
        internal const val KEY_BOUKILI_TASK_TITLE = "boukili_task_title"
        internal const val KEY_BOUKILI_STARS = "boukili_stars"
        internal const val KEY_BOUKILI_SECTION_ID = "boukili_section_id"
        internal const val KEY_BOUKILI_START_TIME = "boukili_start_time"
        
        // GitHub upload configuration
        // GitHub token is encrypted using AES-256-CBC and stored in BuildConfig
        // Repository: BaerenCloud (dedicated repository for reports and artifacts)
        // Reports path: BaerenEd_Reports/
        // 
        // How encryption works:
        // 1. Token is encrypted using encrypt_token.py script
        // 2. Encrypted token is stored in local.properties → BuildConfig (not in repo)
        // 3. Decryption key is hardcoded below (safe to commit - it's just a key, not the token)
        // 
        // Why this prevents GitHub secret scanning:
        // - GitHub looks for token PATTERNS (like "github_pat_", "ghp_")
        // - The encrypted token is just random Base64 data, so GitHub won't detect it
        // - The key being in source code is fine - you need BOTH key + encrypted token to decrypt
        private const val GITHUB_OWNER = "talq2me"
        private const val GITHUB_REPO = "BaerenCloud"
        private const val GITHUB_REPORTS_PATH = "BaerenEd_Reports"  // Directory in repo for reports
    }

    fun startGame(game: Game, gameContent: String? = null, sectionId: String? = null, battleHubTaskId: String? = null) {
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

                // Check if this is the printing game (doesn't need JSON)
                if (game.type == "printing") {
                    launchPrintingGame(game, sectionId, battleHubTaskId)
                    return
                }

                if (gameContent != null) {
                    launchGameActivity(game, gameContent, sectionId, battleHubTaskId)
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

        val profileId = when {
            configUrl.contains("AM_config.json") -> "AM"
            configUrl.contains("BM_config.json") -> "BM"
            configUrl.contains("TE_config.json") -> "TE"
            else -> {
                // Generic: extract from {ProfileId}_config.json
                val match = Regex("([A-Z]{2})_config\\.json").find(configUrl)
                if (match != null) {
                    match.groupValues[1]
                } else {
                    Toast.makeText(this, "Unknown profile configuration", Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }
        SettingsManager.writeProfile(this, profileId)
        Toast.makeText(this, "Selected $profileId profile", Toast.LENGTH_SHORT).show()

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

    private fun launchPrintingGame(game: Game, sectionId: String?, battleHubTaskId: String?) {
        val gameType = game.type
        val gameTitle = game.title
        val stars = game.estimatedTime
        val isRequired = sectionId == "required"
        
        val intent = Intent(this, PrintingGameActivity::class.java).apply {
            putExtra("GAME_TYPE", gameType)
            putExtra("GAME_TITLE", gameTitle)
            putExtra("GAME_STARS", stars)
            putExtra("IS_REQUIRED_GAME", isRequired)
            sectionId?.let { putExtra("SECTION_ID", it) }
            battleHubTaskId?.let { putExtra("BATTLE_HUB_TASK_ID", it) }
        }
        
        if (battleHubTaskId != null) {
            gameCompletionLauncher.launch(intent)
        } else {
            startActivity(intent)
        }
    }

    private fun launchGameActivity(game: Game, gameContent: String, sectionId: String? = null, battleHubTaskId: String? = null) {
        try {
            val gson = Gson()
            val questions = gson.fromJson(gameContent, Array<GameData>::class.java)
            // Prioritize game.totalQuestions from config - this is the authoritative value
            // Only fall back to questions.size if totalQuestions is not specified in config
            // But ensure we use at least 5 questions as a reasonable default
            val totalQuestions = when {
                game.totalQuestions != null -> {
                    // Config explicitly specifies totalQuestions - use it
                    Log.d(TAG, "Using totalQuestions from config: ${game.totalQuestions}")
                    game.totalQuestions!!
                }
                questions.size > 0 -> {
                    // No config value, but we have questions - use all available
                    Log.d(TAG, "No totalQuestions in config, using questions.size: ${questions.size}")
                    questions.size
                }
                else -> {
                    // No config and no questions - use default
                    Log.d(TAG, "No totalQuestions in config and no questions, using default: 5")
                    5
                }
            }
            
            Log.d(TAG, "Launching game: ${game.title}, totalQuestions from config: ${game.totalQuestions}, questions in content: ${questions.size}, final totalQuestions: $totalQuestions")

            val isRequired = sectionId == "required"

            val intent = Intent(this, com.talq2me.baerened.GameActivity::class.java).apply {
                putExtra("GAME_CONTENT", gameContent)
                putExtra("GAME_TYPE", game.type)
                putExtra("GAME_TITLE", game.title)
                putExtra("TOTAL_QUESTIONS", totalQuestions)
                putExtra("GAME_STARS", game.estimatedTime)
                putExtra("IS_REQUIRED_GAME", isRequired)
                putExtra("BLOCK_OUTLINES", game.blockOutlines)
                sectionId?.let { putExtra("SECTION_ID", it) }
                // Pass battleHubTaskId if this game was launched from battle hub
                battleHubTaskId?.let { putExtra("BATTLE_HUB_TASK_ID", it) }
            }
            
            // If launched from battle hub, use launcher to get result; otherwise just start activity
            if (battleHubTaskId != null) {
                gameCompletionLauncher.launch(intent)
            } else {
            startActivity(intent)
            }
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
    val displayDays: String? = null,
    val disable: String? = null,
    /** When true, tappableText uses simpler English hints for tap questions (see [TappableTextActivity]). */
    val easy: Boolean? = null
)

data class ChecklistItem(
    val label: String? = null,
    val stars: Int? = null,
    val done: Boolean? = null,
    val id: String? = null,
    val showdays: String? = null,
    val hidedays: String? = null,
    val displayDays: String? = null
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

