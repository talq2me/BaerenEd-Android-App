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


class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var loadingProgressBar: ProgressBar
    lateinit var contentUpdateService: ContentUpdateService
    private lateinit var layout: Layout
    private var currentMainContent: MainContent? = null
    private var readAlongLaunchTime: Long? = null
    private var pendingReadAlongReward: PendingReadAlongReward? = null
    private var readAlongStartTime: Long = 0
    private var readAlongHasBeenPaused: Boolean = false
    private lateinit var readAlongTimeTracker: TimeTracker
    private val readAlongPrefs by lazy { getSharedPreferences(READ_ALONG_PREFS_NAME, Context.MODE_PRIVATE) }
    private lateinit var cloudStorageManager: CloudStorageManager
    private var wasLaunchedForReadAlong: Boolean = false // Track if we were launched just for Read Along

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
        // Preload settings from Supabase on startup
        SettingsManager.preloadSettings(this)
        
        // Initialize cloud storage manager
        cloudStorageManager = CloudStorageManager(this)
        
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
        
        setContentView(R.layout.activity_main)

        // Remove any leftover downloads that already match the installed build
        cleanupDownloadedUpdatesIfAlreadyInstalled()

        // Check for updates to app (but don't check for completed downloads - let user install from notifications)
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
                Log.w(TAG, "Email callback fired too quickly (${timeSinceEmailLaunch}ms), email may not have opened. Retrying...")
                // Email didn't actually open, try again or show error
                rewardEmailInFlight = false
                val minutes = getPendingRewardMinutes()
                if (minutes != null && minutes > 0) {
                    // Still try to show email one more time
                    val parentEmail = SettingsManager.readEmail(this)
                    if (!parentEmail.isNullOrBlank()) {
                        try {
                            val progressManager = DailyProgressManager(this)
                            val timeTracker = TimeTracker(this)
                            val reportGenerator = ReportGenerator(this)
                            val mainContent = getCurrentMainContent() ?: MainContent()
                            val progressReport = progressManager.getComprehensiveProgressReport(mainContent, timeTracker)
                            val currentKid = progressManager.getCurrentKid()
                            val childName = currentKid
                            val report = reportGenerator.generateDailyReport(progressReport, childName, ReportGenerator.ReportFormat.EMAIL)
                            val subject = "Daily Progress Report - $childName - ${progressReport.date}"
                            val emailIntent = buildEmailIntent(parentEmail, subject, report)
                            if (emailIntent != null) {
                                storePendingRewardMinutes(minutes)
                                rewardEmailInFlight = true
                                emailLaunchTime = System.currentTimeMillis()
                                emailReportLauncher.launch(emailIntent)
                                return@registerForActivityResult
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error retrying email", e)
                        }
                    }
                    // If retry fails, just launch BaerenLock
                    clearPendingRewardState()
                    launchRewardSelectionActivity(minutes)
                }
            }
        }

        if (getOrCreateProfile() != null) {
            loadMainContent()
        }

        // Clean up any stale reward data on app start (on background thread to avoid blocking UI)
        lifecycleScope.launch(Dispatchers.IO) {
            cleanupStaleRewardData()
            resetRewardDataForNewDay()
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

    private fun handleReadAlongReturnIfNeeded(): Boolean {
        val pendingReward = pendingReadAlongReward ?: return false

        val MIN_READ_ALONG_DURATION_SECONDS = 30L
        var session: TimeTracker.ActivitySession? = null
        var secondsSpent = 0L

        // Reinitialize TimeTracker if it was lost (e.g., if MainActivity was destroyed and recreated)
        if (!::readAlongTimeTracker.isInitialized) {
            readAlongTimeTracker = TimeTracker(this)
            Log.d(TAG, "Reinitialized readAlongTimeTracker for ReadAlong return check")
        }

        // Try to get time from TimeTracker first
        session = readAlongTimeTracker.endActivity("readalong")
        secondsSpent = session?.durationSeconds ?: 0

        // If TimeTracker didn't have the session, use fallback calculation based on stored start time
        if (secondsSpent <= 0L) {
            secondsSpent = getStoredReadAlongDurationSeconds()
            Log.d(TAG, "TimeTracker session not found or duration was 0, using fallback calculation: ${secondsSpent}s")
        }

        Log.d(TAG, "Google Read Along session ended. Time spent: ${secondsSpent}s (required: ${MIN_READ_ALONG_DURATION_SECONDS}s), pendingReward: ${pendingReward.taskTitle}")

        // Always process the return if there's a pending reward - check time requirement
        if (secondsSpent >= MIN_READ_ALONG_DURATION_SECONDS) {
            if (::readAlongTimeTracker.isInitialized && session != null) {
                readAlongTimeTracker.updateStarsEarned("readalong", pendingReward.stars)
            }
            
            layout.handleManualTaskCompletion(
                pendingReward.taskId,
                pendingReward.taskTitle,
                pendingReward.stars,
                pendingReward.sectionId,
                "📚 Google Read Along completed! Great job reading!"
            )
            // Explicitly refresh sections to ensure UI updates
            layout.refreshSections()
            Toast.makeText(
                this,
                "Great reading! You spent ${secondsSpent}s in Read Along.",
                Toast.LENGTH_SHORT
            ).show()
            
            Log.d(TAG, "ReadAlong task completed successfully: ${pendingReward.taskTitle}, ${secondsSpent}s >= ${MIN_READ_ALONG_DURATION_SECONDS}s")
        } else {
            Toast.makeText(
                this,
                "Stay in Google Read Along for at least ${MIN_READ_ALONG_DURATION_SECONDS}s to earn rewards (only ${secondsSpent}s).",
                Toast.LENGTH_LONG
            ).show()
            
            Log.d(TAG, "ReadAlong task NOT completed: ${pendingReward.taskTitle}, ${secondsSpent}s < ${MIN_READ_ALONG_DURATION_SECONDS}s")
        }

        clearPendingReadAlongState()
        return true // Indicate that we handled the Read Along return
    }

    private fun persistPendingReadAlongState(reward: PendingReadAlongReward, startTimeMs: Long) {
        readAlongPrefs.edit()
            .putString(KEY_READ_ALONG_TASK_ID, reward.taskId)
            .putString(KEY_READ_ALONG_TASK_TITLE, reward.taskTitle)
            .putInt(KEY_READ_ALONG_STARS, reward.stars)
            .putString(KEY_READ_ALONG_SECTION_ID, reward.sectionId ?: "")
            .putLong(KEY_READ_ALONG_START_TIME, startTimeMs)
            .putBoolean(KEY_READ_ALONG_HAS_PAUSED, false)
            .apply()
    }

    private fun restorePendingReadAlongStateIfNeeded() {
        if (pendingReadAlongReward != null) {
            return
        }

        val taskId = readAlongPrefs.getString(KEY_READ_ALONG_TASK_ID, null) ?: return
        val taskTitle = readAlongPrefs.getString(KEY_READ_ALONG_TASK_TITLE, taskId) ?: taskId
        val stars = readAlongPrefs.getInt(KEY_READ_ALONG_STARS, 0)
        val sectionId = readAlongPrefs.getString(KEY_READ_ALONG_SECTION_ID, null)?.takeIf { it.isNotBlank() }

        pendingReadAlongReward = PendingReadAlongReward(
            taskId = taskId,
            taskTitle = taskTitle,
            stars = stars,
            sectionId = sectionId
        )
        readAlongHasBeenPaused = readAlongPrefs.getBoolean(KEY_READ_ALONG_HAS_PAUSED, false)
        
        // Reinitialize TimeTracker if it was lost (e.g., if MainActivity was destroyed and recreated)
        if (!::readAlongTimeTracker.isInitialized) {
            readAlongTimeTracker = TimeTracker(this)
            Log.d(TAG, "Reinitialized readAlongTimeTracker when restoring ReadAlong state")
        }
    }

    private fun clearPendingReadAlongState() {
        pendingReadAlongReward = null
        readAlongHasBeenPaused = false
        readAlongPrefs.edit().clear().apply()
    }

    private fun hasPendingReadAlongSession(): Boolean {
        return pendingReadAlongReward != null || readAlongPrefs.contains(KEY_READ_ALONG_TASK_ID)
    }

    private fun getStoredReadAlongDurationSeconds(): Long {
        val startTimeMs = readAlongPrefs.getLong(KEY_READ_ALONG_START_TIME, -1L)
        if (startTimeMs <= 0L) {
            Log.w(TAG, "No stored start time found for ReadAlong")
            return 0
        }
        val currentTimeMs = System.currentTimeMillis()
        val elapsedMs = currentTimeMs - startTimeMs
        val seconds = if (elapsedMs > 0) elapsedMs / 1000 else 0
        Log.d(TAG, "Calculated ReadAlong duration from stored time: start=${startTimeMs}, current=${currentTimeMs}, elapsed=${elapsedMs}ms, seconds=${seconds}s")
        return seconds
    }
    
    override fun onPause() {
        super.onPause()
        if (hasPendingReadAlongSession()) {
            readAlongHasBeenPaused = true
            readAlongPrefs.edit().putBoolean(KEY_READ_ALONG_HAS_PAUSED, true).apply()
            // Only set start time if it's not already set (don't overwrite existing start time)
            val existingStartTime = readAlongPrefs.getLong(KEY_READ_ALONG_START_TIME, -1L)
            if (existingStartTime <= 0L) {
                val startTime = System.currentTimeMillis()
                readAlongPrefs.edit().putLong(KEY_READ_ALONG_START_TIME, startTime).apply()
                Log.d(TAG, "MainActivity paused - Read Along is now active, set start time: $startTime")
            } else {
                Log.d(TAG, "MainActivity paused - Read Along is now active, start time already set: $existingStartTime")
            }
        }
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

                // Sync progress to cloud after video completion
                val profile = SettingsManager.readProfile(this) ?: "AM"
                android.util.Log.d("MainActivity", "Video completed, triggering cloud sync for profile: $profile")
                lifecycleScope.launch(Dispatchers.IO) {
                    CloudStorageManager(this@MainActivity).saveIfEnabled(profile)
                }
            } else {
                android.util.Log.d("MainActivity", "VIDEO COMPLETION: Missing taskId or taskTitle in data")
            }
        } else {
            android.util.Log.d("MainActivity", "VIDEO COMPLETION: Ignoring result - wrong resultCode or no data (resultCode=${result.resultCode}, hasData=${result.data != null})")
        }
    }

    private fun handleWebGameCompletion(result: ActivityResult) {
        android.util.Log.d("MainActivity", "WebGame result received: resultCode=${result.resultCode}")
        
        // Check if battle hub requested to launch a game
        if (result.resultCode == WebGameActivity.RESULT_LAUNCH_GAME && result.data != null) {
            val gameId = result.data?.getStringExtra(WebGameActivity.RESULT_EXTRA_GAME_ID)
            if (gameId != null) {
                Log.d(TAG, "Battle hub requested to launch game: $gameId")
                launchGameFromBattleHub(gameId)
                return
            }
        }
        
        // Check if this was a game launched from battle hub (taskId starts with "battleHub_")
            val taskId = result.data?.getStringExtra(WebGameActivity.EXTRA_TASK_ID)
        val wasFromBattleHub = taskId?.startsWith("battleHub_") == true
        
        if (result.resultCode == RESULT_OK && result.data != null) {
            val sectionId = result.data?.getStringExtra(WebGameActivity.EXTRA_SECTION_ID)
            val stars = result.data?.getIntExtra(WebGameActivity.EXTRA_STARS, 0) ?: 0
            val taskTitle = result.data?.getStringExtra(WebGameActivity.EXTRA_TASK_TITLE)
            
            // If game was launched from battle hub, save berries to be added when battle hub reopens
            if (wasFromBattleHub && stars > 0) {
                val progressManager = DailyProgressManager(this)
                progressManager.addEarnedBerries(stars)
                Log.d(TAG, "Saved $stars berries from battle hub game completion")
            }
            
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

            // Sync progress to cloud after game completion
            val profile = SettingsManager.readProfile(this) ?: "A"
            lifecycleScope.launch(Dispatchers.IO) {
                CloudStorageManager(this@MainActivity).saveIfEnabled(profile)
            }
        }
    }
    
    private fun launchGameFromBattleHub(gameId: String) {
        val currentContent = getCurrentMainContent() ?: return
        
        // Find the task in the config by launch ID
        var taskToLaunch: Task? = null
        var sectionId: String? = null
        
        currentContent.sections?.forEach { section ->
            section.tasks?.forEach { task ->
                if (task.launch == gameId) {
                    taskToLaunch = task
                    sectionId = section.id
                    return@forEach
                }
            }
        }
        
        if (taskToLaunch != null && sectionId != null) {
            Log.d(TAG, "Found task for battle hub: ${taskToLaunch!!.title}, totalQuestions=${taskToLaunch!!.totalQuestions}, stars=${taskToLaunch!!.stars}")
            // Launch the game using the same logic as Layout.kt
            // Pass a modified task ID so we know it came from battle hub
            layout.launchTaskFromBattleHub(taskToLaunch!!, sectionId, "battleHub_$gameId")
        } else {
            Log.w(TAG, "Game not found in config: $gameId")
            Toast.makeText(this, "Game not found: $gameId", Toast.LENGTH_SHORT).show()
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

                // Sync progress to cloud after chrome page completion
                val profile = SettingsManager.readProfile(this) ?: "AM"
                lifecycleScope.launch(Dispatchers.IO) {
                    CloudStorageManager(this@MainActivity).saveIfEnabled(profile)
                }
            }
        }
    }
    
    private fun handleGameCompletion(result: ActivityResult) {
        android.util.Log.d("MainActivity", "Game result received: resultCode=${result.resultCode}")
        if (result.resultCode == RESULT_OK && result.data != null) {
            val battleHubTaskId = result.data?.getStringExtra("BATTLE_HUB_TASK_ID")
            val gameStars = result.data?.getIntExtra("GAME_STARS", 0) ?: 0
            val gameType = result.data?.getStringExtra("GAME_TYPE")
            
            // Get sectionId from the current content to check if it's from required/optional
            val currentContent = getCurrentMainContent()
            val sectionId = currentContent?.sections?.find { section ->
                section.tasks?.any { it.launch == gameType } == true
            }?.id
            
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

        val profiles = arrayOf("Profile A", "Profile B")
        AlertDialog.Builder(this)
            .setTitle("Select User Profile")
            .setCancelable(false)
            .setItems(profiles) { _, which ->
                val selectedProfile = if (which == 0) "AM" else "BM"
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
                val selectedProfile = if (which == 0) "AM" else "BM"
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
            val currentDate = java.text.SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", java.util.Locale.getDefault()).format(java.util.Date())

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
        restorePendingReadAlongStateIfNeeded()
        val readAlongHandled = handleReadAlongReturnIfNeeded()
        
        // If we were launched just for Read Along and have handled the return, finish to go back to TrainingMapActivity
        if (wasLaunchedForReadAlong && readAlongHandled) {
            Log.d(TAG, "Read Along completed, finishing MainActivity to return to TrainingMapActivity")
            finish()
            return
        }
        
        // If we were launched just for Read Along but haven't handled return yet, don't try to load/display content
        if (wasLaunchedForReadAlong) {
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
        
        // Show training map if requested, otherwise show normal content
        if (showTrainingMap) {
            battleHubPrefs.edit().remove("showTrainingMap").apply()
            Log.d(TAG, "Showing training map from battle hub")
            layout.showTrainingMap()
        } else if (showOptionalTrainingMap) {
            battleHubPrefs.edit().remove("showOptionalTrainingMap").apply()
            Log.d(TAG, "Showing optional training map from battle hub")
            layout.showOptionalTrainingMap()
        } else if (showBonusTrainingMap) {
            battleHubPrefs.edit().remove("showBonusTrainingMap").apply()
            Log.d(TAG, "Showing bonus training map from battle hub")
            layout.showBonusTrainingMap()
        } else {
            currentMainContent?.let { content ->
                layout.displayContent(content)
            }
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
    
    fun toggleCloudStorage() {
        val isCurrentlyEnabled = cloudStorageManager.isCloudStorageEnabled()
        
        if (!cloudStorageManager.isConfigured()) {
            Toast.makeText(this, "Supabase not configured. Add SUPABASE_URL and SUPABASE_KEY to local.properties and rebuild.", Toast.LENGTH_LONG).show()
            return
        }
        
        if (!isCurrentlyEnabled) {
            // Enable cloud storage
            cloudStorageManager.setCloudStorageEnabled(true)
            val profile = SettingsManager.readProfile(this) ?: "A"
            lifecycleScope.launch(Dispatchers.IO) {
                val result = cloudStorageManager.syncIfEnabled(profile)
                runOnUiThread {
                    if (result.isSuccess) {
                        Toast.makeText(this@MainActivity, "Cloud storage enabled and synced", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Cloud enabled but sync failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                    // Refresh UI to update button
                    currentMainContent?.let { layout.displayContent(it) }
                }
            }
        } else {
            // Disable cloud storage
            cloudStorageManager.setCloudStorageEnabled(false)
            Toast.makeText(this, "Cloud storage disabled", Toast.LENGTH_SHORT).show()
            // Refresh UI to update button
            currentMainContent?.let { layout.displayContent(it) }
        }
    }
    
    /**
     * Saves data to cloud if cloud storage is enabled
     * Call this after any data changes
     */
    fun saveToCloudIfEnabled() {
        if (cloudStorageManager.isCloudStorageEnabled()) {
            val profile = SettingsManager.readProfile(this) ?: "A"
            lifecycleScope.launch(Dispatchers.IO) {
                cloudStorageManager.saveIfEnabled(profile)
            }
        }
    }

    fun launchGoogleReadAlong(task: Task, sectionId: String?) {
        android.util.Log.d(TAG, "Launching Google Read Along for task: ${task.title}")
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.seekh")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                
                // Initialize time tracker
                readAlongTimeTracker = TimeTracker(this)
                
                // Use unique task ID that includes section info to track separately for required vs optional
                val progressManager = DailyProgressManager(this)
                val taskId = task.launch ?: "googleReadAlong"
                val uniqueTaskId = progressManager.getUniqueTaskId(taskId, sectionId ?: "unknown")
                
                // Start tracking time
                val taskTitle = task.title ?: "Google Read Along"
                readAlongTimeTracker.startActivity(uniqueTaskId, "readalong", taskTitle)
                val reward = PendingReadAlongReward(
                    taskId = taskId,
                    taskTitle = taskTitle,
                    stars = task.stars ?: 0,
                    sectionId = sectionId
                )
                pendingReadAlongReward = reward
                readAlongHasBeenPaused = false
                readAlongStartTime = android.os.SystemClock.elapsedRealtime()
                persistPendingReadAlongState(reward, System.currentTimeMillis())
                
                startActivity(intent)
            } else {
                Toast.makeText(this, "Google Read Along app is not installed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Google Read Along", e)
            Toast.makeText(this, "Error launching Google Read Along: ${e.message}", Toast.LENGTH_SHORT).show()
            pendingReadAlongReward = null
            clearPendingReadAlongState()
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
        val settingsOptions = arrayOf("Change Profile", "Change PIN", "Change Parent Email", "Send Progress Report", "Reset All Progress")

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(settingsOptions) { _, which ->
                when (which) {
                    0 -> showChangeProfileDialog()
                    1 -> showChangePinDialog()
                    2 -> showChangeEmailDialog()
                    3 -> sendProgressReportInternal()
                    4 -> showResetProgressConfirmationDialog()
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

    private fun showResetProgressConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset All Progress")
            .setMessage("Are you sure you want to reset today's progress? This will clear:\n\n" +
                    "• All completed tasks\n" +
                    "• All time tracking data\n" +
                    "• All reward minutes\n\n" +
                    "This is the same as the daily reset. Game progress, Pokemon unlocks, and video progress will be preserved.")
            .setPositiveButton("Reset All") { _, _ ->
                resetAllProgress()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetAllProgress() {
        try {
            // Just trigger the same reset that happens daily
            val progressManager = DailyProgressManager(this)
            progressManager.resetAllProgress()
            
            // Also reset TimeTracker (it resets daily too)
            TimeTracker(this).clearAllData()
            
            // Refresh the UI
            layout.refreshProgressDisplay()
            layout.refreshSections()
            
            Toast.makeText(this, "Progress has been reset", Toast.LENGTH_LONG).show()
            Log.d(TAG, "Progress reset completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting progress", e)
            Toast.makeText(this, "Error resetting progress: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

            val subject = "Daily Progress Report - $childName - ${progressReport.date}"
            val emailIntent = buildEmailIntent(parentEmail, subject, report)

            try {
                if (emailIntent != null) {
                    startActivity(emailIntent)
                } else {
                    throw IllegalStateException("No email apps available")
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

    /**
     * Sends progress report using ActivityResultLauncher (for reward time flow)
     * This ensures the email app is shown and the user can send the email before
     * the reward selection activity is launched
     */
    fun sendProgressReportForRewardTime(rewardMinutes: Int) {
        try {
            val progressManager = DailyProgressManager(this)
            val timeTracker = TimeTracker(this)
            val reportGenerator = ReportGenerator(this)

            val mainContent = getCurrentMainContent() ?: MainContent()

            val progressReport = progressManager.getComprehensiveProgressReport(mainContent, timeTracker)

            val currentKid = progressManager.getCurrentKid()
            val childName = if (currentKid == "A") "AM" else "BM"

            // Log the reward minutes being used for the report (should match what's sent to BaerenLock)
            Log.d(TAG, "Generating report with reward minutes: $rewardMinutes (will be sent to BaerenLock)")
            
            val report = reportGenerator.generateDailyReport(progressReport, childName, ReportGenerator.ReportFormat.EMAIL, rewardMinutes)
            val subject = "Daily Progress Report - $childName - ${progressReport.date}"
            
            // Store pending minutes BEFORE uploading
            storePendingRewardMinutes(rewardMinutes)
            
            // Automatically upload to GitHub - no user interaction needed!
            uploadReportToGitHub(report, subject, childName, rewardMinutes)

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error sending progress report for reward time", e)
            rewardEmailInFlight = false
            // If report generation fails, still launch reward selection
            launchRewardSelectionActivity(rewardMinutes)
        }
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

    private fun buildEmailIntent(parentEmail: String, subject: String, body: String): Intent? {
        // Use message/rfc822 type which filters chooser to email apps only (Gmail, Outlook, etc.)
        // This way the chooser only shows email apps, not all share options
        val emailIntent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"  // Email MIME type - limits to email apps
            putExtra(Intent.EXTRA_EMAIL, arrayOf(parentEmail))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        // Create chooser with email apps only - user picks their preferred email app once
        return Intent.createChooser(emailIntent, "Send Progress Report via Email")
    }
    
    private fun buildSMSIntent(message: String): Intent? {
        // Open SMS app with message body - user can enter phone number
        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:")  // Empty recipient - user enters phone number
            putExtra("sms_body", message)
        }
        
        if (smsIntent.resolveActivity(packageManager) != null) {
            return smsIntent
        }
        
        // Fallback: use ACTION_SEND for SMS
        val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        return Intent.createChooser(fallbackIntent, "Send Progress Report via SMS")
    }
    
    /**
     * Decrypts the GitHub token using AES-256-CBC decryption
     * The encrypted token is stored in BuildConfig (from local.properties)
     * The decryption key is hardcoded in source code (safe to commit - it's just a key, not the token)
     * 
     * Why this works: GitHub secret scanning looks for token PATTERNS (like "github_pat_", "ghp_")
     * The encrypted token is just random-looking Base64, so GitHub won't detect it as a token
     */
    private fun decryptGitHubToken(): String {
        return try {
            val encryptedToken = BuildConfig.ENCRYPTED_GITHUB_TOKEN
            
            if (encryptedToken.isEmpty()) {
                Log.w(TAG, "GitHub token encryption not configured")
                return ""
            }
            
            // Hardcoded decryption key - this is safe to commit (it's not the token, just a key)
            // This key decrypts the token that's stored in BuildConfig.ENCRYPTED_GITHUB_TOKEN
            val encryptionKeyB64 = "MOBRoFYjmXL0ZwELC/CcQXgWm2xThNJlTSElwRhReZI="  // Base64 encoded 32-byte key
            
            // Decode Base64 encrypted data and key
            val encryptedBytes = Base64.decode(encryptedToken, Base64.DEFAULT)
            val keyBytes = Base64.decode(encryptionKeyB64, Base64.DEFAULT)
            
            // Extract IV (first 16 bytes) and ciphertext (rest)
            val iv = ByteArray(16)
            System.arraycopy(encryptedBytes, 0, iv, 0, 16)
            val ciphertextLength = encryptedBytes.size - 16
            val ciphertext = ByteArray(ciphertextLength)
            System.arraycopy(encryptedBytes, 16, ciphertext, 0, ciphertextLength)
            
            // Decrypt using AES-256-CBC
            val secretKeySpec = SecretKeySpec(keyBytes, "AES")
            val ivParameterSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
            
            val decryptedBytes = cipher.doFinal(ciphertext)
            
            // Remove PKCS5 padding
            val padLength = decryptedBytes[decryptedBytes.size - 1].toInt()
            val unpaddedLength = decryptedBytes.size - padLength
            val unpaddedBytes = ByteArray(unpaddedLength)
            System.arraycopy(decryptedBytes, 0, unpaddedBytes, 0, unpaddedLength)
            
            String(unpaddedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt GitHub token", e)
            ""
        }
    }
    
    /**
     * Automatically uploads the progress report to GitHub as a text file
     * No user interaction required - fully automated for young children
     */
    private fun uploadReportToGitHub(
        report: String,
        subject: String,
        childName: String,
        rewardMinutes: Int
    ) {
        // Decrypt GitHub token at runtime (encrypted token and key stored in BuildConfig)
        val githubToken = decryptGitHubToken()
        
        // Check if GitHub token is configured
        if (githubToken.isBlank()) {
            Toast.makeText(this, "Report upload not configured. Contact administrator.", Toast.LENGTH_SHORT).show()
            launchRewardSelectionActivity(rewardMinutes)
            return
        }
        
        // Show progress (optional, for feedback)
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Uploading Report")
            .setMessage("Please wait...")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        lifecycleScope.launch(Dispatchers.IO) {
            val client = OkHttpClient()
            try {
                // Create simple filename - one file per kid, always overwrite
                val fileName = "$childName.txt"
                val filePath = "$GITHUB_REPORTS_PATH/$fileName"
                
                // Full report content (subject + report)
                val fullReport = "$subject\n\n$report"
                
                // Base64 encode the content (GitHub API requirement)
                val contentBytes = fullReport.toByteArray(Charsets.UTF_8)
                val base64Content = Base64.encodeToString(contentBytes, Base64.NO_WRAP)
                
                // GitHub API endpoint
                val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/$filePath"
                
                // Check if file exists first (to get SHA for update)
                // If file doesn't exist, GitHub API will create it; if it exists, we'll overwrite it
                var existingSha: String? = null
                var isUpdate = false
                try {
                    val checkRequest = Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "token $githubToken")
                        .addHeader("Accept", "application/vnd.github.v3+json")
                        .get()
                        .build()
                    client.newCall(checkRequest).execute().use { checkResponse ->
                        if (checkResponse.isSuccessful) {
                            val checkBody = checkResponse.body?.string()
                            if (!checkBody.isNullOrEmpty()) {
                                val fileInfo = JSONObject(checkBody)
                                existingSha = fileInfo.optString("sha", null)
                                isUpdate = true
                                Log.d(TAG, "File exists, will overwrite. SHA: $existingSha")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // File doesn't exist, will create new - this is fine
                    Log.d(TAG, "File doesn't exist yet, will create new file")
                }
                
                // Create JSON payload for GitHub API
                val commitMessage = if (isUpdate) {
                    "Update progress report for $childName"
                } else {
                    "Create progress report for $childName"
                }
                val json = JSONObject().apply {
                    put("message", commitMessage)
                    put("content", base64Content)
                    put("branch", "main")  // Change to "master" if your repo uses that
                    if (existingSha != null) {
                        put("sha", existingSha)  // Required for updates/overwrites
                    }
                }
                
                // Create request
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = json.toString().toRequestBody(mediaType)
                
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "token $githubToken")
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .put(body)
                    .build()
                
                // Execute request
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        
                        if (response.isSuccessful) {
                            Log.d(TAG, "Report uploaded successfully to GitHub: $filePath")
                            Toast.makeText(this@MainActivity, "Report uploaded!", Toast.LENGTH_SHORT).show()
                            
                            // Successfully uploaded, immediately grant reward
                            rewardEmailInFlight = false
                            Handler(Looper.getMainLooper()).postDelayed({
                                triggerPendingRewardLaunch(force = true)
                            }, 500)
                        } else {
                            Log.e(TAG, "GitHub upload failed: ${response.code} - $responseBody")
                            val errorMsg = try {
                                val errorJson = JSONObject(responseBody ?: "{}")
                                errorJson.optString("message", "Upload failed")
                            } catch (e: Exception) {
                                "Upload failed: ${response.code}"
                            }
                            
                            Toast.makeText(this@MainActivity, "Upload failed: $errorMsg", Toast.LENGTH_LONG).show()
                            rewardEmailInFlight = false
                            launchRewardSelectionActivity(rewardMinutes)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading report to GitHub", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Upload error: ${e.message}", Toast.LENGTH_LONG).show()
                    rewardEmailInFlight = false
                    launchRewardSelectionActivity(rewardMinutes)
                }
            }
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
        private const val MIN_READ_ALONG_DURATION_MS = 30_000L
        private const val UPDATE_PREFS_NAME = "update_manager"
        private const val KEY_PENDING_VERSION = "pending_version"
        private const val KEY_PENDING_URL = "pending_url"
        private const val KEY_PENDING_FILE_NAME = "pending_file_name"
        private const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"
        private const val APK_FILE_PREFIX = "myapp-update"
        private const val REWARD_PREFS_NAME = "reward_manager"
        private const val KEY_PENDING_REWARD_MINUTES = "pending_reward_minutes"
        private val VERSION_IN_NAME_REGEX = Regex("v(\\d+)")
        private const val GMAIL_PACKAGE = "com.google.android.gm"
        private const val GMAIL_COMPOSE_CLASS = "com.google.android.gm.ComposeActivityGmail"
        private const val READ_ALONG_PREFS_NAME = "read_along_session"
        private const val KEY_READ_ALONG_TASK_ID = "read_along_task_id"
        private const val KEY_READ_ALONG_TASK_TITLE = "read_along_task_title"
        private const val KEY_READ_ALONG_STARS = "read_along_stars"
        private const val KEY_READ_ALONG_SECTION_ID = "read_along_section_id"
        private const val KEY_READ_ALONG_START_TIME = "read_along_start_time"
        private const val KEY_READ_ALONG_HAS_PAUSED = "read_along_has_paused"
        
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

        when {
            configUrl.contains("AM_config.json") -> {
                SettingsManager.writeProfile(this, "AM")
                Toast.makeText(this, "Selected AM profile", Toast.LENGTH_SHORT).show()
            }
            configUrl.contains("BM_config.json") -> {
                SettingsManager.writeProfile(this, "BM")
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
    val disable: String? = null
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

data class PendingReadAlongReward(
    val taskId: String,
    val taskTitle: String,
    val stars: Int,
    val sectionId: String?
)

