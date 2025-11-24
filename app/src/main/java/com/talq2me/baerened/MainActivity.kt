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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException


class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var loadingProgressBar: ProgressBar
    lateinit var contentUpdateService: ContentUpdateService
    private lateinit var layout: Layout
    private var currentMainContent: MainContent? = null
    private var readAlongLaunchTime: Long? = null
    private var pendingReadAlongReward: PendingReadAlongReward? = null

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
    private lateinit var installPackagePermissionLauncher: ActivityResultLauncher<Intent>
    
    // Store pending reward minutes to launch after email is sent
    private var pendingRewardMinutes: Int? = null
    private var emailLaunchTime: Long = 0
    private var rewardEmailInFlight = false
    private var pendingApkInstall: DownloadedApk? = null

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

        // Remove any leftover downloads that already match the installed build
        cleanupDownloadedUpdatesIfAlreadyInstalled()

        // Restore download ID from preferences if we have an active download
        restoreDownloadState()

        // Check for updates to app
        checkForUpdateIfOnline()
        
        // Check if there's a completed download waiting to be installed
        checkForCompletedDownload()
        
        // If we have an active download, start monitoring it
        if (downloadId != -1L) {
            startDownloadStatusPolling()
            showDownloadProgressDialog()
        }

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

        installPackagePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            // After user returns from settings, try to install
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (packageManager.canRequestPackageInstalls()) {
                    pendingApkInstall?.let { apk ->
                        pendingApkInstall = null
                        performInstall(apk)
                    }
                } else {
                    pendingApkInstall = null
                    Toast.makeText(this, "Install permission not granted. Cannot install update automatically.", Toast.LENGTH_LONG).show()
                }
            } else {
                // Android < 8.0, just try to install
                pendingApkInstall?.let { apk ->
                    pendingApkInstall = null
                    performInstall(apk)
                }
            }
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
                            val childName = if (currentKid == "A") "AM" else "BM"
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

        // Register download completion receiver
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1L
                Log.d(TAG, "Download broadcast received: id=$id, stored downloadId=$downloadId")
                if (id != -1L) {
                    // Check if this is our download or if downloadId matches
                    if (id == downloadId || downloadId == -1L) {
                        downloadId = id // Update in case it was lost
                        handleDownloadComplete()
                    }
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
        val downloadedFile = findDownloadedApkForVersion(latestVersion)
        if (downloadedFile != null && downloadedFile.exists()) {
            val apk = DownloadedApk(uri = Uri.fromFile(downloadedFile), file = downloadedFile)
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    showInstallDialog(apk)
                }
            }
        } else {
            runOnUiThread { forceUpdateDialog(latestVersion, apkUrl) }
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
            Log.d(TAG, "APK for version $targetVersion already downloaded, showing install dialog")
            val downloadedApk = DownloadedApk(uri = Uri.fromFile(existingFile), file = existingFile)
            showInstallDialog(downloadedApk)
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
        
        // Show progress dialog and start polling
        showDownloadProgressDialog()
        startDownloadStatusPolling()
    }
    
    private fun restoreDownloadState() {
        val savedDownloadId = updatePrefs.getLong(KEY_PENDING_DOWNLOAD_ID, -1L)
        if (savedDownloadId != -1L) {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(savedDownloadId)
            val cursor = dm.query(query)
            try {
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            // Download already completed, handle it
                            downloadId = savedDownloadId
                            handleDownloadComplete()
                        }
                        DownloadManager.STATUS_RUNNING,
                        DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_PAUSED -> {
                            // Download still in progress, resume monitoring
                            downloadId = savedDownloadId
                        }
                        else -> {
                            // Download failed or cancelled, clear state
                            clearPendingUpdateState()
                        }
                    }
                } else {
                    // Download no longer exists in DownloadManager
                    clearPendingUpdateState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring download state", e)
                clearPendingUpdateState()
            } finally {
                cursor.close()
            }
        }
    }
    
    private fun showDownloadProgressDialog() {
        // Dismiss existing dialog if any
        downloadProgressDialog?.dismiss()
        
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }
        
        downloadProgressDialog = AlertDialog.Builder(this)
            .setTitle("downloading update…")
            .setMessage("please wait while the update downloads")
            .setView(progressBar)
            .setCancelable(false)
            .create()
        
        downloadProgressDialog?.show()
    }
    
    private fun dismissDownloadProgressDialog() {
        downloadProgressDialog?.dismiss()
        downloadProgressDialog = null
    }
    
    private fun startDownloadStatusPolling() {
        // Stop any existing polling
        stopDownloadStatusPolling()
        
        // Show progress dialog if not already shown
        if (downloadProgressDialog == null || !downloadProgressDialog!!.isShowing) {
            showDownloadProgressDialog()
        }
        
        downloadCheckRunnable = object : Runnable {
            override fun run() {
                if (downloadId == -1L) {
                    stopDownloadStatusPolling()
                    dismissDownloadProgressDialog()
                    return
                }
                
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                
                try {
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                Log.d(TAG, "Download completed via polling")
                                stopDownloadStatusPolling()
                                dismissDownloadProgressDialog()
                                handleDownloadComplete()
                                return
                            }
                            DownloadManager.STATUS_FAILED -> {
                                Log.d(TAG, "Download failed")
                                stopDownloadStatusPolling()
                                dismissDownloadProgressDialog()
                                Toast.makeText(this@MainActivity, "Download failed. Please try again.", Toast.LENGTH_LONG).show()
                                clearPendingUpdateState()
                                return
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                Log.d(TAG, "Download paused")
                            }
                            DownloadManager.STATUS_PENDING -> {
                                Log.d(TAG, "Download pending")
                            }
                            DownloadManager.STATUS_RUNNING -> {
                                // Try to get progress if available
                                try {
                                    val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                    val totalSize = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                    if (totalSize > 0 && downloadProgressDialog != null && downloadProgressDialog!!.isShowing) {
                                        val progress = (bytesDownloaded * 100 / totalSize).toInt()
                                        Log.d(TAG, "Download progress: $progress% ($bytesDownloaded / $totalSize)")
                                    }
                                } catch (e: Exception) {
                                    // Progress columns might not be available, ignore
                                }
                            }
                        }
                    } else {
                        // Download ID not found - might have been cleared
                        Log.w(TAG, "Download ID $downloadId not found in DownloadManager")
                        stopDownloadStatusPolling()
                        dismissDownloadProgressDialog()
                        clearPendingUpdateState()
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling download status", e)
                } finally {
                    cursor.close()
                }
                
                // Continue polling if download is still in progress
                if (downloadId != -1L && downloadCheckRunnable != null) {
                    downloadCheckHandler.postDelayed(this, 500) // Check every 500ms for more responsive UI
                } else {
                    stopDownloadStatusPolling()
                    dismissDownloadProgressDialog()
                }
            }
        }
        
        // Start polling immediately
        downloadCheckHandler.post(downloadCheckRunnable!!)
    }
    
    private fun stopDownloadStatusPolling() {
        downloadCheckRunnable?.let {
            downloadCheckHandler.removeCallbacks(it)
            downloadCheckRunnable = null
        }
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
                    it.longVersionCode.toInt()
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
                packageInfo.longVersionCode.toInt()
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

    private fun resolveDownloadedApk(dm: DownloadManager, cursor: Cursor): DownloadedApk? {
        return try {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
            val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            val uri = when {
                !localUri.isNullOrBlank() -> Uri.parse(localUri)
                else -> dm.getUriForDownloadedFile(id)
            }
            if (uri == null) {
                Log.w(TAG, "Unable to resolve URI for download id=$id")
                null
            } else {
                val file = resolveFileFromCursor(cursor)?.takeIf { it.exists() }
                DownloadedApk(uri = uri, file = file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to resolve downloaded APK", e)
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

    private data class DownloadedApk(val uri: Uri, val file: File?)
    
    private fun checkForCompletedDownload() {
        // Check if there's a completed download of our APK file
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query()
            .setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)
        
        val cursor = dm.query(query)
        try {
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                if (!isUpdateDownloadEntry(localUri, title)) {
                    continue
                }
                val downloadedApk = resolveDownloadedApk(dm, cursor) ?: continue
                val apkFile = downloadedApk.file
                val downloadedVersion = determineDownloadedVersion(apkFile, downloadedApk.uri, title)
                val currentVersion = getCurrentAppVersionCode()
                if (downloadedVersion != null && currentVersion != -1 && downloadedVersion <= currentVersion) {
                    Log.d(TAG, "Skipping stale download (version $downloadedVersion already installed)")
                    apkFile?.delete()
                    dm.remove(id)
                    continue
                }
                downloadId = id
                if (downloadedVersion != null) {
                    val editor = updatePrefs.edit()
                    editor.putInt(KEY_PENDING_VERSION, downloadedVersion)
                    apkFile?.name?.let { editor.putString(KEY_PENDING_FILE_NAME, it) }
                    editor.putLong(KEY_PENDING_DOWNLOAD_ID, id)
                    editor.apply()
                }
                Log.d(TAG, "Found completed download: id=$id, uri=${downloadedApk.uri}")
                if (!isDestroyed && !isFinishing) {
                    showInstallDialog(downloadedApk)
                }
                break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for completed downloads", e)
        } finally {
            cursor.close()
        }
    }

    private fun handleDownloadComplete() {
        if (downloadId == -1L) {
            Log.w(TAG, "Download ID is -1, cannot handle download completion")
            return
        }

        stopDownloadStatusPolling()
        dismissDownloadProgressDialog()

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)

        try {
            if (!cursor.moveToFirst()) {
                Log.w(TAG, "No download found with ID: $downloadId")
                return
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            Log.d(TAG, "Download status: $status")
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Log.d(TAG, "Download not successful, status: $status")
                return
            }

            val downloadedApk = resolveDownloadedApk(dm, cursor)
            if (downloadedApk == null) {
                Log.w(TAG, "Downloaded file reference missing for ID: $downloadId")
                dm.remove(downloadId)
                return
            }

            val apkFile = downloadedApk.file
            val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
            val downloadedVersion = determineDownloadedVersion(apkFile, downloadedApk.uri, title)
            val currentVersion = getCurrentAppVersionCode()
            if (downloadedVersion != null) {
                val editor = updatePrefs.edit()
                editor.putInt(KEY_PENDING_VERSION, downloadedVersion)
                apkFile?.name?.let { editor.putString(KEY_PENDING_FILE_NAME, it) }
                editor.putLong(KEY_PENDING_DOWNLOAD_ID, downloadId)
                editor.apply()
                if (currentVersion != -1 && downloadedVersion <= currentVersion) {
                    Log.d(TAG, "Downloaded version ($downloadedVersion) already installed ($currentVersion)")
                    apkFile?.delete()
                    dm.remove(downloadId)
                    clearPendingUpdateState()
                    return
                }
            }

            val showDialogAction = {
                if (!isDestroyed && !isFinishing) {
                    showInstallDialog(downloadedApk)
                } else {
                    Log.w(TAG, "Activity state changed, cannot show dialog")
                }
            }

            if (Looper.myLooper() == Looper.getMainLooper()) {
                showDialogAction()
            } else {
                runOnUiThread { showDialogAction() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling download completion", e)
        } finally {
            cursor.close()
        }
    }

    private fun handleReadAlongReturnIfNeeded() {
        val startTime = readAlongLaunchTime
        val pendingReward = pendingReadAlongReward
        if (startTime == null || pendingReward == null) {
            return
        }

        val elapsed = System.currentTimeMillis() - startTime
        val secondsSpent = elapsed / 1000

        if (elapsed >= MIN_READ_ALONG_DURATION_MS) {
            layout.handleManualTaskCompletion(
                pendingReward.taskId,
                pendingReward.taskTitle,
                pendingReward.stars,
                pendingReward.sectionId,
                "📚 Google Read Along completed! Great job reading!"
            )
            Toast.makeText(
                this,
                "Great reading! You spent ${secondsSpent}s in Read Along.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
                Toast.makeText(
                this,
                "Stay in Google Read Along for at least 30s to earn rewards (only ${secondsSpent}s).",
                Toast.LENGTH_LONG
            ).show()
        }

        readAlongLaunchTime = null
        pendingReadAlongReward = null
    }

    private fun showInstallDialog(downloadedApk: DownloadedApk) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("install update")
            .setMessage("the update has been downloaded. please install it now to continue.")
            .setCancelable(false)
            .setPositiveButton("install now") { _, _ ->
                installApk(downloadedApk)
            }
            .create()
        
        dialog.show()
    }

    private fun installApk(downloadedApk: DownloadedApk) {
        // Check and request install permission on Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Log.d(TAG, "Requesting install package permission")
                pendingApkInstall = downloadedApk
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    installPackagePermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening install permission settings", e)
                    Toast.makeText(this, "Please enable 'Install unknown apps' for this app in Settings > Apps > Special app access", Toast.LENGTH_LONG).show()
                    pendingApkInstall = null
                }
                return
            }
        }
        
        performInstall(downloadedApk)
    }
    
    private fun performInstall(downloadedApk: DownloadedApk) {
        try {
            val apkFile = downloadedApk.file
            val hasLocalFile = apkFile != null && apkFile.exists()
            Log.d(TAG, "Installing APK. hasLocalFile=$hasLocalFile, uri=${downloadedApk.uri}")
            
            // Check if we're device owner (can install silently without Play Protect prompt)
            if (hasLocalFile && isDeviceOwner()) {
                Log.d(TAG, "Device owner detected, attempting silent install")
                if (installSilentlyAsDeviceOwner(apkFile)) {
                    return // Successfully installed silently
                }
                // Fall through to regular install if silent install fails
            }
            
            // Regular install method (will show Play Protect prompt)
            val finalUri = when {
                hasLocalFile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                    try {
                        val uri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${packageName}.fileprovider",
                            apkFile!!
                        )
                        Log.d(TAG, "FileProvider URI created: $uri")
                        uri
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "FileProvider error: ${e.message}", e)
                        throw e
                    }
                }
                hasLocalFile -> {
                    Log.d(TAG, "Using file:// URI for Android < N: ${apkFile!!.absolutePath}")
                    Uri.fromFile(apkFile)
                }
                else -> {
                    Log.d(TAG, "Using downloaded URI: ${downloadedApk.uri}")
                    downloadedApk.uri
                }
            }
            
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(finalUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                // Grant permission to package installer
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            
            Log.d(TAG, "Starting install intent with URI: $finalUri, scheme: ${finalUri.scheme}")
            
            // Check if intent can be resolved before launching
            val resolver = packageManager.resolveActivity(installIntent, 0)
            if (resolver != null) {
                Log.d(TAG, "Package installer found, starting install activity")
                startActivity(installIntent)
            } else {
                Log.e(TAG, "No app can handle APK install. URI: $finalUri")
                throw IllegalStateException("No app can handle APK install. Please enable 'Install unknown apps' for this app in settings.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
            val errorMsg = "Error installing update: ${e.message}\n\nPlease try installing manually from Downloads folder."
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
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
        android.util.Log.d("MainActivity", "Video result received: resultCode=${result.resultCode}")
        if (result.resultCode == RESULT_OK && result.data != null) {
            val taskId = result.data?.getStringExtra("TASK_ID")
            val taskTitle = result.data?.getStringExtra("TASK_TITLE")
            val stars = result.data?.getIntExtra("TASK_STARS", 0) ?: 0
            val videoFile = result.data?.getStringExtra("VIDEO_FILE")
            val videoIndex = result.data?.getIntExtra("VIDEO_INDEX", -1)

            if (taskId != null && taskTitle != null) {
                val indexToPass = if (videoIndex != -1) videoIndex else null
                layout.handleVideoCompletion(taskId, taskTitle, stars, videoFile, indexToPass)
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
        handleReadAlongReturnIfNeeded()
        
        // Don't trigger reward launch here when email is in flight - wait for ActivityResult callback
        // Only check for other pending rewards if email is not in flight
        if (!rewardEmailInFlight) {
            triggerPendingRewardLaunch()
        } else {
            Log.d(TAG, "onResume: Email is in flight, waiting for ActivityResult callback before launching BaerenLock")
        }
        
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

    fun launchGoogleReadAlong(task: Task, sectionId: String?) {
        android.util.Log.d(TAG, "Launching Google Read Along for task: ${task.title}")
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.seekh")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                readAlongLaunchTime = System.currentTimeMillis()
                pendingReadAlongReward = PendingReadAlongReward(
                    taskId = task.launch ?: "googleReadAlong",
                    taskTitle = task.title ?: "Google Read Along",
                    stars = task.stars ?: 0,
                    sectionId = sectionId
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Google Read Along app is not installed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Google Read Along", e)
            Toast.makeText(this, "Error launching Google Read Along: ${e.message}", Toast.LENGTH_SHORT).show()
            readAlongLaunchTime = null
            pendingReadAlongReward = null
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

            val report = reportGenerator.generateDailyReport(progressReport, childName, ReportGenerator.ReportFormat.EMAIL)

            // Get parent email from settings
            val parentEmail = SettingsManager.readEmail(this)
            
            if (parentEmail.isNullOrBlank()) {
                // If no email is set, just launch reward selection directly
                launchRewardSelectionActivity(rewardMinutes)
                return
            }

            val subject = "Daily Progress Report - $childName - ${progressReport.date}"
            val emailIntent = buildEmailIntent(parentEmail, subject, report)

            try {
                if (emailIntent != null) {
                    // Store pending minutes and set flag BEFORE launching email
                    // This ensures they're saved even if the activity is killed
                    storePendingRewardMinutes(rewardMinutes)
                    rewardEmailInFlight = true
                    emailLaunchTime = System.currentTimeMillis()
                    
                    Log.d(TAG, "Launching Gmail for parent report. Reward minutes pending: $rewardMinutes, emailIntent: ${emailIntent.action}")
                    // Use launcher to wait for email activity to finish before launching BaerenLock
                    emailReportLauncher.launch(emailIntent)
                    Log.d(TAG, "Email launcher called, waiting for result...")
                } else {
                    Log.e(TAG, "buildEmailIntent returned null - no email apps available")
                    throw IllegalStateException("No email apps available")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error opening email", e)
                rewardEmailInFlight = false
                clearPendingRewardState()
                Toast.makeText(this, "Error opening email: ${e.message}. Launching reward selection anyway.", Toast.LENGTH_LONG).show()
                // If email fails, still launch reward selection
                launchRewardSelectionActivity(rewardMinutes)
            }

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error sending progress report for reward time", e)
            rewardEmailInFlight = false
            // If report generation fails, still launch reward selection
            triggerPendingRewardLaunch(force = true)
        }
    }

    /**
     * Launches the RewardSelectionActivity with the specified reward minutes
     */
    private fun launchRewardSelectionActivity(rewardMinutes: Int) {
        val intent = Intent(this, RewardSelectionActivity::class.java).apply {
            putExtra("reward_minutes", rewardMinutes)
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
        // Use mailto: URI - this is the most reliable way and avoids permission issues
        // It will open the default email app (usually Gmail) directly
        val mailtoUri = Uri.parse("mailto:$parentEmail").buildUpon()
            .appendQueryParameter("subject", subject)
            .appendQueryParameter("body", body)
            .build()
        
        val sendToIntent = Intent(Intent.ACTION_SENDTO, mailtoUri)
        
        // Check if mailto: can be handled
        return try {
            if (sendToIntent.resolveActivity(packageManager) != null) {
                sendToIntent
            } else {
                // Fallback to ACTION_SEND chooser if mailto: doesn't work
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(parentEmail))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                Intent.createChooser(shareIntent, "Send Progress Report")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating email intent: ${e.message}", e)
            // Final fallback
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(parentEmail))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            Intent.createChooser(shareIntent, "Send Progress Report")
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
        stopDownloadStatusPolling()
        dismissDownloadProgressDialog()
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
