package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tracks time spent in games and activities for reporting purposes
 */
class TimeTracker(private val context: Context) {

    companion object {
        private const val PREF_NAME = "time_tracking_prefs"
        private const val KEY_DAILY_SESSIONS = "daily_sessions"
        private const val KEY_SESSION_START_TIMES = "session_start_times"
        private const val KEY_LAST_RESET_DATE = "time_tracker_last_reset"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Data class representing a single activity session
     */
    data class ActivitySession(
        val activityId: String,
        val activityType: String, // "game", "video", "youtube", etc.
        val activityName: String,
        val startTime: Long,
        val endTime: Long? = null,
        val durationSeconds: Long = 0,
        val date: String,
        val completed: Boolean = false,
        val starsEarned: Int = 0,
        val correctAnswers: Int = 0,
        val incorrectAnswers: Int = 0
    ) {
        val durationMinutes: Double
            get() = durationSeconds / 60.0

        val formattedDuration: String
            get() {
                val minutes = (durationSeconds / 60).toInt()
                val seconds = (durationSeconds % 60).toInt()
                return if (minutes > 0) "${minutes}m${seconds}s" else "${seconds}s"
            }
    }

    /**
     * Data class for daily activity summary
     */
    data class DailyActivitySummary(
        val date: String,
        val totalTimeMinutes: Double,
        val sessions: List<ActivitySession>,
        val completedActivities: List<String>,
        val totalStarsEarned: Int,
        val gamesPlayed: Int,
        val videosWatched: Int,
        val gameSessions: List<ActivitySession>, // Individual games with their data
        val completedGameSessions: List<ActivitySession> // Only completed games
    )

    /**
     * Gets current date as string for daily tracking
     */
    private fun getCurrentDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    /**
     * Checks if we need to reset for a new day
     */
    private fun shouldResetForNewDay(): Boolean {
        val lastResetDate = prefs.getString(KEY_LAST_RESET_DATE, "")
        val currentDate = getCurrentDateString()
        return lastResetDate != currentDate
    }

    /**
     * Gets all sessions for today
     */
    private fun getTodaySessions(): MutableList<ActivitySession> {
        if (shouldResetForNewDay()) {
            resetForNewDay()
        }

        val json = prefs.getString(KEY_DAILY_SESSIONS, "[]")
        val type = object : TypeToken<MutableList<ActivitySession>>() {}.type
        val sessions: MutableList<ActivitySession> = gson.fromJson(json, type) ?: mutableListOf()

        return sessions
    }

    /**
     * Saves sessions for today
     */
    private fun saveTodaySessions(sessions: List<ActivitySession>) {
        // CRITICAL: Use commit() for synchronous write to prevent race conditions
        val success = prefs.edit()
            .putString(KEY_DAILY_SESSIONS, gson.toJson(sessions))
            .commit()
        if (!success) {
            Log.e("TimeTracker", "CRITICAL ERROR: Failed to save TimeTracker sessions!")
        }
    }

    /**
     * Resets tracking data for a new day
     */
    private fun resetForNewDay() {
        prefs.edit()
            .putString(KEY_DAILY_SESSIONS, gson.toJson(emptyList<ActivitySession>()))
            .putString(KEY_SESSION_START_TIMES, gson.toJson(emptyMap<String, Long>()))
            .putString(KEY_LAST_RESET_DATE, getCurrentDateString())
            .apply()

        Log.d("TimeTracker", "Reset time tracking for new day: ${getCurrentDateString()}")
    }

    /**
     * Starts tracking time for an activity
     */
    fun startActivity(activityId: String, activityType: String, activityName: String) {
        val currentTime = System.currentTimeMillis()
        val sessions = getTodaySessions()

        // End any currently running session for the same activity type
        endActivity(activityType)

        // Create new session
        val session = ActivitySession(
            activityId = activityId,
            activityType = activityType,
            activityName = activityName,
            startTime = currentTime,
            endTime = null,
            durationSeconds = 0,
            date = getCurrentDateString(),
            completed = false,
            starsEarned = 0,
            correctAnswers = 0,
            incorrectAnswers = 0
        )

        sessions.add(session)
        saveTodaySessions(sessions)

        Log.d("TimeTracker", "Started tracking $activityType: $activityName")
    }

    /**
     * Ends tracking time for an activity
     */
    fun endActivity(activityType: String): ActivitySession? {
        val sessions = getTodaySessions()
        val currentTime = System.currentTimeMillis()

        // Find the most recent session of this type that's still running
        val runningSession = sessions.lastOrNull { it.activityType == activityType && it.endTime == null }

        return if (runningSession != null) {
            val endedSession = runningSession.copy(
                endTime = currentTime,
                durationSeconds = (currentTime - runningSession.startTime) / 1000,
                completed = true
            )

            // Replace the running session with the ended one
            val index = sessions.indexOf(runningSession)
            if (index >= 0) {
                sessions[index] = endedSession
                saveTodaySessions(sessions)
            }

            Log.d("TimeTracker", "Ended tracking $activityType: ${runningSession.activityName}, duration: ${endedSession.durationMinutes} minutes")
            endedSession
        } else {
            Log.d("TimeTracker", "No running session found for $activityType")
            null
        }
    }

    /**
     * Updates the stars earned for the most recent session of an activity
     */
    fun updateStarsEarned(activityType: String, starsEarned: Int) {
        val sessions = getTodaySessions()
        val currentTime = System.currentTimeMillis()

        // Find the most recent session of this type (could be running or recently ended)
        val recentSession = sessions.lastOrNull { it.activityType == activityType }

        if (recentSession != null) {
            val updatedSession = recentSession.copy(starsEarned = starsEarned)

            val index = sessions.indexOf(recentSession)
            if (index >= 0) {
                sessions[index] = updatedSession
                saveTodaySessions(sessions)
            }

            Log.d("TimeTracker", "Updated stars for $activityType: $starsEarned")
        }
    }

    /**
     * Updates the answer counts for the most recent session of an activity
     */
    fun updateAnswerCounts(activityType: String, correctAnswers: Int, incorrectAnswers: Int) {
        val sessions = getTodaySessions()

        // Find the most recent session of this type (could be running or recently ended)
        val recentSession = sessions.lastOrNull { it.activityType == activityType }

        if (recentSession != null) {
            val updatedSession = recentSession.copy(
                correctAnswers = correctAnswers,
                incorrectAnswers = incorrectAnswers
            )

            val index = sessions.indexOf(recentSession)
            if (index >= 0) {
                sessions[index] = updatedSession
                saveTodaySessions(sessions)
            }

            Log.d("TimeTracker", "Updated answer counts for $activityType: $correctAnswers correct, $incorrectAnswers incorrect")
        }
    }

    /**
     * Gets today's activity summary
     */
    fun getTodaySummary(): DailyActivitySummary {
        val sessions = getTodaySessions()
        val currentDate = getCurrentDateString()

        val totalTimeMinutes = sessions.sumOf { it.durationMinutes }
        val completedActivities = sessions.filter { it.completed }.map { it.activityName }
        val totalStarsEarned = sessions.sumOf { it.starsEarned }
        val gamesPlayed = sessions.count { it.activityType == "game" }
        val videosWatched = sessions.count { it.activityType in listOf("video", "youtube") }

        val gameSessions = sessions.filter { it.activityType == "game" }
        val completedGameSessions = gameSessions.filter { it.completed }

        return DailyActivitySummary(
            date = currentDate,
            totalTimeMinutes = totalTimeMinutes,
            sessions = sessions,
            completedActivities = completedActivities,
            totalStarsEarned = totalStarsEarned,
            gamesPlayed = gamesPlayed,
            videosWatched = videosWatched,
            gameSessions = gameSessions,
            completedGameSessions = completedGameSessions
        )
    }

    /**
     * Gets all sessions for today (for detailed reporting)
     */
    fun getTodaySessionsList(): List<ActivitySession> {
        return getTodaySessions()
    }

    /**
     * Gets session history for the last N days
     */
    fun getSessionHistory(days: Int = 7): List<DailyActivitySummary> {
        // For now, we'll just return today's summary
        // In a more complete implementation, we'd store history in a more persistent way
        return listOf(getTodaySummary())
    }

    /**
     * Gets the total time spent on a specific activity type today
     */
    fun getTimeSpentToday(activityType: String): Double {
        return getTodaySessions()
            .filter { it.activityType == activityType }
            .sumOf { it.durationMinutes }
    }

    /**
     * Gets the most recently played activity
     */
    fun getLastActivity(): ActivitySession? {
        return getTodaySessions().lastOrNull()
    }

    /**
     * Clears all time tracking data (for testing/debugging)
     */
    fun clearAllData() {
        prefs.edit().clear().apply()
        Log.d("TimeTracker", "Cleared all time tracking data")
    }
    
    /**
     * Clears sessions for specific activity IDs (e.g., all optional tasks)
     * @param activityIdPrefix The prefix to match (e.g., "optional_" to clear all optional task sessions)
     */
    fun clearSessionsForActivityPrefix(activityIdPrefix: String) {
        val sessions = getTodaySessions()
        val filteredSessions = sessions.filter { !it.activityId.startsWith(activityIdPrefix) }
        saveTodaySessions(filteredSessions)
        val clearedCount = sessions.size - filteredSessions.size
        Log.d("TimeTracker", "Cleared $clearedCount sessions with prefix '$activityIdPrefix' (kept ${filteredSessions.size} sessions)")
    }
}
