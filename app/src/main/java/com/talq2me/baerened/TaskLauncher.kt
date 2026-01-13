package com.talq2me.baerened

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.Calendar

/**
 * Centralized task launcher
 * Handles launching different types of tasks (games, videos, web games, Chrome pages, etc.)
 * 
 * Note: This is a helper class that works with the current activity structure.
 * Different activities use different result handling mechanisms (ActivityResultLauncher vs startActivityForResult),
 * so this class provides common logic that can be used by all activities.
 */
class TaskLauncher(
    private val context: Context,
    private val contentUpdateService: ContentUpdateService
) {
    companion object {
        private const val TAG = "TaskLauncher"
    }

    /**
     * Interface for handling activity results
     * Activities can implement this to handle results differently
     */
    interface ActivityResultHandler {
        fun launchActivity(intent: Intent, requestCode: Int? = null)
        fun launchActivityForResult(intent: Intent, requestCode: Int)
    }

    /**
     * Launch a task
     * @param task The task to launch
     * @param sectionId The section ID (required, optional, bonus)
     * @param sourceTaskId Optional source task ID (for battle hub, gym map, etc.)
     * @param resultHandler Handler for activity results (null if not using result launchers)
     */
    fun launchTask(
        task: Task,
        sectionId: String,
        sourceTaskId: String? = null,
        resultHandler: ActivityResultHandler? = null
    ) {
        val gameType = task.launch ?: "unknown"
        val gameTitle = task.title ?: "Task"

        // Check task types in priority order
        when {
            // Video sequence tasks (handle first)
            task.videoSequence != null -> {
                handleVideoSequenceTask(task, sectionId, resultHandler)
            }
            // Chrome page tasks
            task.chromePage == true -> {
                launchChromePageTask(task, sectionId, resultHandler)
            }
            // Web game tasks
            task.webGame == true && !task.url.isNullOrEmpty() -> {
                launchWebGameTask(task, sectionId, sourceTaskId, resultHandler)
            }
            // Playlist tasks
            task.playlistId != null -> {
                launchPlaylistTask(task, resultHandler)
            }
            // Google Read Along
            task.launch == "googleReadAlong" -> {
                launchGoogleReadAlong(task, sectionId)
            }
            // Boukili
            task.launch == "boukili" -> {
                launchBoukili(task, sectionId)
            }
            // French Book Reader
            task.launch == "frenchBookReader" -> {
                launchFrenchBookReader(resultHandler)
            }
            // Regular game tasks
            else -> {
                launchGameTask(task, sectionId, sourceTaskId, resultHandler)
            }
        }
    }

    /**
     * Launch Chrome page task
     */
    private fun launchChromePageTask(task: Task, sectionId: String, resultHandler: ActivityResultHandler?) {
        val pageUrl = task.url
        if (pageUrl.isNullOrBlank()) {
            Toast.makeText(context, "No page configured for this task.", Toast.LENGTH_SHORT).show()
            return
        }

        val taskLaunchId = task.launch ?: "unknown"
        val intent = Intent(context, ChromePageActivity::class.java).apply {
            putExtra(ChromePageActivity.EXTRA_URL, pageUrl)
            putExtra(ChromePageActivity.EXTRA_TASK_ID, taskLaunchId)
            putExtra(ChromePageActivity.EXTRA_STARS, task.stars ?: 0)
            putExtra(ChromePageActivity.EXTRA_TASK_TITLE, task.title ?: taskLaunchId)
            putExtra(ChromePageActivity.EXTRA_SECTION_ID, sectionId)
        }

        resultHandler?.launchActivity(intent, 1001) ?: (context as? Activity)?.startActivityForResult(intent, 1001)
    }

    /**
     * Launch web game task
     */
    private fun launchWebGameTask(task: Task, sectionId: String, sourceTaskId: String?, resultHandler: ActivityResultHandler?) {
        val taskLaunchId = task.launch ?: "unknown"
        var gameUrl = getGameModeUrl(task.url ?: "", task.easydays, task.harddays, task.extremedays)
        
        // Append totalQuestions parameter if specified in config
        if (task.totalQuestions != null) {
            val separator = if (gameUrl.contains("?")) "&" else "?"
            gameUrl = "$gameUrl${separator}totalQuestions=${task.totalQuestions}"
            Log.d(TAG, "Added totalQuestions=${task.totalQuestions} to URL: $gameUrl")
        }
        
        // For diagramLabeler, make taskId unique if diagram parameter exists
        var uniqueTaskId = taskLaunchId
        if (taskLaunchId == "diagramLabeler" && gameUrl.contains("diagram=")) {
            val diagramParam = gameUrl.substringAfter("diagram=").substringBefore("&").substringBefore("#")
            if (diagramParam.isNotEmpty()) {
                uniqueTaskId = "${taskLaunchId}_$diagramParam"
                Log.d(TAG, "Made diagramLabeler taskId unique: $uniqueTaskId")
            }
        }

        val intent = Intent(context, WebGameActivity::class.java).apply {
            putExtra(WebGameActivity.EXTRA_GAME_URL, gameUrl)
            putExtra(WebGameActivity.EXTRA_TASK_ID, sourceTaskId ?: uniqueTaskId)
            putExtra(WebGameActivity.EXTRA_SECTION_ID, sectionId)
            putExtra(WebGameActivity.EXTRA_STARS, task.stars ?: 0)
            putExtra(WebGameActivity.EXTRA_TASK_TITLE, task.title ?: taskLaunchId)
        }

        resultHandler?.launchActivity(intent, 1002) ?: (context as? Activity)?.startActivityForResult(intent, 1002)
    }

    /**
     * Launch playlist task
     */
    private fun launchPlaylistTask(task: Task, resultHandler: ActivityResultHandler?) {
        val playlistId = task.playlistId ?: return
        val playlistTitle = task.title ?: "Playlist"
        
        Log.d(TAG, "Launching playlist: $playlistId")
        val intent = Intent(context, YouTubePlayerActivity::class.java).apply {
            putExtra(YouTubePlayerActivity.EXTRA_PLAYLIST_ID, playlistId)
            putExtra(YouTubePlayerActivity.EXTRA_VIDEO_TITLE, playlistTitle)
        }

        resultHandler?.launchActivity(intent) ?: (context as? Activity)?.startActivity(intent)
    }

    /**
     * Launch Google Read Along
     */
    private fun launchGoogleReadAlong(task: Task, sectionId: String?) {
        Log.d(TAG, "Launching Google Read Along: ${task.title}")
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.seekh")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                
                // Save tracking info
                val prefs = context.getSharedPreferences("read_along_session", Context.MODE_PRIVATE)
                val taskId = task.launch ?: "googleReadAlong"
                val taskTitle = task.title ?: "Google Read Along"
                val stars = task.stars ?: 0
                
                prefs.edit()
                    .putString("read_along_task_id", taskId)
                    .putString("read_along_task_title", taskTitle)
                    .putInt("read_along_stars", stars)
                    .putString("read_along_section_id", sectionId ?: "")
                    .putLong("read_along_start_time", System.currentTimeMillis())
                    .apply()
                
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "Google Read Along app is not installed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Google Read Along", e)
            Toast.makeText(context, "Error launching Google Read Along: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launch Boukili
     */
    private fun launchBoukili(task: Task, sectionId: String?) {
        Log.d(TAG, "Launching Boukili: ${task.title}")
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.boukili.app")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                
                // Save tracking info
                val prefs = context.getSharedPreferences("boukili_session", Context.MODE_PRIVATE)
                val taskId = task.launch ?: "boukili"
                val taskTitle = task.title ?: "Boukili"
                val stars = task.stars ?: 0
                
                prefs.edit()
                    .putString("boukili_task_id", taskId)
                    .putString("boukili_task_title", taskTitle)
                    .putInt("boukili_stars", stars)
                    .putString("boukili_section_id", sectionId ?: "")
                    .putLong("boukili_start_time", System.currentTimeMillis())
                    .apply()
                
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "Boukili app is not installed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Boukili", e)
            Toast.makeText(context, "Error launching Boukili: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launch French Book Reader
     */
    private fun launchFrenchBookReader(resultHandler: ActivityResultHandler?) {
        Log.d(TAG, "Launching French Book Reader")
        val intent = Intent(context, FrenchBookReaderActivity::class.java)
        resultHandler?.launchActivity(intent) ?: (context as? Activity)?.startActivity(intent)
    }

    /**
     * Launch game task (fetch content and launch GameActivity)
     */
    private fun launchGameTask(task: Task, sectionId: String, sourceTaskId: String?, resultHandler: ActivityResultHandler?) {
        val gameType = task.launch ?: "unknown"
        val gameTitle = task.title ?: "Task"

        // Use GlobalScope since we don't have lifecycle scope here
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val gameContent = contentUpdateService.fetchGameContent(context, gameType)

                withContext(Dispatchers.Main) {
                    val game = Game(
                        id = gameType,
                        title = gameTitle,
                        description = "Educational activity",
                        type = gameType,
                        iconUrl = "",
                        requiresRewardTime = false,
                        difficulty = "Easy",
                        estimatedTime = task.stars ?: 1,
                        totalQuestions = task.totalQuestions,
                        blockOutlines = task.blockOutlines ?: false
                    )

                    launchGameActivity(game, gameContent, sectionId, sourceTaskId, resultHandler)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching game content for $gameType", e)
                withContext(Dispatchers.Main) {
                    val game = Game(
                        id = gameType,
                        title = gameTitle,
                        description = "Educational activity",
                        type = gameType,
                        iconUrl = "",
                        requiresRewardTime = false,
                        difficulty = "Easy",
                        estimatedTime = task.stars ?: 1,
                        totalQuestions = task.totalQuestions,
                        blockOutlines = task.blockOutlines ?: false
                    )
                    Toast.makeText(context, "$gameType content not available", Toast.LENGTH_SHORT).show()
                    launchGameActivity(game, null, sectionId, sourceTaskId, resultHandler)
                }
            }
        }
    }

    /**
     * Launch GameActivity
     */
    private fun launchGameActivity(
        game: Game,
        gameContent: String?,
        sectionId: String,
        sourceTaskId: String?,
        resultHandler: ActivityResultHandler?
    ) {
        if (gameContent == null) {
            Toast.makeText(context, "${game.type} content not available", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val gson = Gson()
            val questions = gson.fromJson(gameContent, Array<GameData>::class.java)

            val totalQuestions = when {
                game.totalQuestions != null -> game.totalQuestions!!
                questions.isNotEmpty() -> questions.size
                else -> 5
            }

            // Determine if game is required (would need config, but simplifying for now)
            val intent = Intent(context, GameActivity::class.java).apply {
                putExtra("GAME_CONTENT", gameContent)
                putExtra("GAME_TYPE", game.type)
                putExtra("GAME_TITLE", game.title)
                putExtra("TOTAL_QUESTIONS", totalQuestions)
                putExtra("GAME_STARS", game.estimatedTime)
                putExtra("IS_REQUIRED_GAME", false) // Would need config to determine
                putExtra("BLOCK_OUTLINES", game.blockOutlines)
                putExtra("SECTION_ID", sectionId)
                sourceTaskId?.let { putExtra("BATTLE_HUB_TASK_ID", it) }
            }

            resultHandler?.launchActivity(intent, 1004) ?: (context as? Activity)?.startActivityForResult(intent, 1004)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing game content for ${game.type}", e)
            Toast.makeText(context, "Error loading game content", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Handle video sequence task
     */
    private fun handleVideoSequenceTask(task: Task, sectionId: String, resultHandler: ActivityResultHandler?) {
        val videoSequence = task.videoSequence ?: return
        val videoFile = task.launch ?: return

        when (videoSequence) {
            "playlist" -> {
                val playlistId = task.playlistId ?: task.launch
                if (playlistId != null) {
                    launchPlaylistTask(task, resultHandler)
                }
            }
            else -> {
                // Handle video sequences that need JSON files
                GlobalScope.launch(Dispatchers.IO) {
                    var videoJson: String? = null
                    try {
                        videoJson = contentUpdateService.fetchVideoContent(context, videoFile)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching video content for $videoFile", e)
                    }

                    // Fallback to assets
                    if (videoJson == null) {
                        try {
                            videoJson = context.assets.open("videos/$videoFile.json").bufferedReader().use { it.readText() }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading video content from assets", e)
                        }
                    }

                    if (videoJson == null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error loading video content", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        when (videoSequence) {
                            "exact" -> {
                                val videoName = task.video ?: return@withContext
                                playYouTubeVideo(videoJson, videoName, task, sectionId, resultHandler)
                            }
                            "sequential" -> {
                                playNextVideoInSequence(videoJson, videoFile, task, sectionId, resultHandler)
                            }
                            else -> {
                                Toast.makeText(context, "Unknown video sequence type", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Play YouTube video
     */
    private fun playYouTubeVideo(videoJson: String, videoName: String, task: Task, sectionId: String, resultHandler: ActivityResultHandler?) {
        try {
            val gson = Gson()
            val videoMap = gson.fromJson(videoJson, Map::class.java) as Map<String, String>
            val videoId = videoMap[videoName]

            if (videoId != null) {
                val intent = Intent(context, YouTubePlayerActivity::class.java).apply {
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_ID, videoId)
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_TITLE, videoName)
                    putExtra("TASK_ID", task.launch ?: "unknown")
                    putExtra("TASK_TITLE", task.title ?: "Video Task")
                    putExtra("TASK_STARS", task.stars ?: 0)
                    putExtra("SECTION_ID", sectionId)
                }

                resultHandler?.launchActivity(intent, 1003) ?: (context as? Activity)?.startActivityForResult(intent, 1003)
            } else {
                Toast.makeText(context, "Video not found: $videoName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing video JSON", e)
            Toast.makeText(context, "Error playing video", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Play next video in sequence
     */
    private fun playNextVideoInSequence(videoJson: String, videoFile: String, task: Task, sectionId: String, resultHandler: ActivityResultHandler?) {
        try {
            val gson = Gson()
            val videoMap = gson.fromJson(videoJson, Map::class.java) as Map<String, String>
            val videoList = videoMap.keys.toList()

            if (videoList.isEmpty()) {
                Toast.makeText(context, "No videos available", Toast.LENGTH_SHORT).show()
                return
            }

            val prefs = context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)
            val currentKid = SettingsManager.readProfile(context) ?: "AM"
            var lastVideoIndex = prefs.getInt("${currentKid}_${videoFile}_index", -1)

            if (lastVideoIndex >= videoList.size) {
                lastVideoIndex = -1
                prefs.edit().putInt("${currentKid}_${videoFile}_index", -1).apply()
            }

            val videoIndexToPlay = if (lastVideoIndex == -1) 0 else (lastVideoIndex + 1) % videoList.size
            val videoNameToPlay = videoList[videoIndexToPlay]

            // Play video with sequence tracking
            val gson2 = Gson()
            val videoMap2 = gson2.fromJson(videoJson, Map::class.java) as Map<String, String>
            val videoId = videoMap2[videoNameToPlay]

            if (videoId != null) {
                val intent = Intent(context, YouTubePlayerActivity::class.java).apply {
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_ID, videoId)
                    putExtra(YouTubePlayerActivity.EXTRA_VIDEO_TITLE, videoNameToPlay)
                    putExtra("TASK_ID", task.launch ?: "unknown")
                    putExtra("TASK_TITLE", task.title ?: "Video Task")
                    putExtra("TASK_STARS", task.stars ?: 0)
                    putExtra("SECTION_ID", sectionId)
                    putExtra("VIDEO_FILE", videoFile)
                    putExtra("VIDEO_INDEX", videoIndexToPlay)
                    putExtra("IS_SEQUENTIAL", true)
                }

                resultHandler?.launchActivity(intent, 1003) ?: (context as? Activity)?.startActivityForResult(intent, 1003)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling sequential video", e)
            Toast.makeText(context, "Error playing video sequence", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Get game mode URL (handles easydays/harddays/extremedays)
     */
    private fun getGameModeUrl(originalUrl: String, easydays: String?, harddays: String?, extremedays: String?): String {
        val uri = Uri.parse(originalUrl)
        val originalMode = uri.getQueryParameter("mode")

        val hasDayFields = !easydays.isNullOrEmpty() || !harddays.isNullOrEmpty() || !extremedays.isNullOrEmpty()
        if (!hasDayFields) {
            val mode = originalMode ?: "hard"
            val builder = uri.buildUpon()
            builder.clearQuery()
            uri.queryParameterNames.forEach { name ->
                if (name != "mode") {
                    builder.appendQueryParameter(name, uri.getQueryParameter(name))
                }
            }
            builder.appendQueryParameter("mode", mode)
            return builder.build().toString()
        }

        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val todayShort = when (today) {
            Calendar.MONDAY -> "mon"
            Calendar.TUESDAY -> "tue"
            Calendar.WEDNESDAY -> "wed"
            Calendar.THURSDAY -> "thu"
            Calendar.FRIDAY -> "fri"
            Calendar.SATURDAY -> "sat"
            Calendar.SUNDAY -> "sun"
            else -> ""
        }

        var mode = originalMode ?: "hard"

        if (!easydays.isNullOrEmpty() && easydays.split(",").contains(todayShort)) {
            mode = "easy"
        } else if (!harddays.isNullOrEmpty() && harddays.split(",").contains(todayShort)) {
            mode = "hard"
        } else if (!extremedays.isNullOrEmpty() && extremedays.split(",").contains(todayShort)) {
            mode = "extreme"
        }

        val builder = uri.buildUpon()
        builder.clearQuery()
        uri.queryParameterNames.forEach { name ->
            if (name != "mode") {
                builder.appendQueryParameter(name, uri.getQueryParameter(name))
            }
        }
        builder.appendQueryParameter("mode", mode)
        return builder.build().toString()
    }
}
