package com.talq2me.baerened

import com.google.gson.annotations.SerializedName

/**
 * Data classes for cloud storage
 * Extracted from CloudStorageManager for better organization
 */

/**
 * Data class representing all user data stored in cloud
 */
data class CloudUserData(
    val profile: String, // "AM" or "BM"

    // Daily reset timestamp
    @SerializedName("last_reset") val lastReset: String? = null,

    // Required tasks progress (name: complete/incomplete, #correct/#incorrect, #questions)
    @SerializedName("required_tasks") val requiredTasks: Map<String, TaskProgress> = emptyMap(),

    // Practice tasks progress (name: # times_completed, #correct/#incorrect, #questions_answered)
    @SerializedName("practice_tasks") val practiceTasks: Map<String, PracticeProgress> = emptyMap(),

    // Checklist items progress (name: done status, stars, display days)
    @SerializedName("checklist_items") val checklistItems: Map<String, ChecklistItemProgress> = emptyMap(),

    // Progress metrics
    @SerializedName("possible_stars") val possibleStars: Int = 0,
    @SerializedName("banked_mins") val bankedMins: Int = 0,
    @SerializedName("berries_earned") val berriesEarned: Int = 0,

    // Pokemon data
    @SerializedName("pokemon_unlocked") val pokemonUnlocked: Int = 0,

    // Game indices for all game types (name: index)
    @SerializedName("game_indices") val gameIndices: Map<String, Int> = emptyMap(),

    // App lists (from BaerenLock) - stored as JSON strings in database
    @SerializedName("reward_apps") val rewardApps: String? = null, // JSON array string
    @SerializedName("blacklisted_apps") val blacklistedApps: String? = null, // JSON array string
    @SerializedName("white_listed_apps") val whiteListedApps: String? = null, // JSON array string

    // Metadata
    @SerializedName("last_updated") val lastUpdated: String? = null,
)

/**
 * Data class for required task progress
 * Fields are nullable to allow omitting them from JSON when not applicable
 */
data class TaskProgress(
    @SerializedName("status") val status: String = "incomplete", // "complete" or "incomplete"
    @SerializedName("correct") val correct: Int? = null,
    @SerializedName("incorrect") val incorrect: Int? = null,
    @SerializedName("questions") val questions: Int? = null,
    @SerializedName("stars") val stars: Int? = null, // Star value for this task (from config)
    @SerializedName("showdays") val showdays: String? = null, // Visibility: show on these days
    @SerializedName("hidedays") val hidedays: String? = null, // Visibility: hide on these days
    @SerializedName("displayDays") val displayDays: String? = null, // Visibility: display only on these days
    @SerializedName("disable") val disable: String? = null // Visibility: disable before this date
)

/**
 * Data class for practice task progress
 * Fields are nullable to allow omitting them from JSON when not applicable
 */
data class PracticeProgress(
    @SerializedName("times_completed") val timesCompleted: Int = 0,
    @SerializedName("correct") val correct: Int? = null,
    @SerializedName("incorrect") val incorrect: Int? = null,
    @SerializedName("questions_answered") val questionsAnswered: Int? = null,
    @SerializedName("showdays") val showdays: String? = null, // Visibility: show on these days
    @SerializedName("hidedays") val hidedays: String? = null, // Visibility: hide on these days
    @SerializedName("displayDays") val displayDays: String? = null, // Visibility: display only on these days
    @SerializedName("disable") val disable: String? = null // Visibility: disable before this date
)

/**
 * Data class for checklist item progress
 * Fields are nullable to allow omitting them from JSON when not applicable
 */
data class ChecklistItemProgress(
    @SerializedName("done") val done: Boolean = false, // Whether the checklist item is done
    @SerializedName("stars") val stars: Int = 0, // Star count for this item
    @SerializedName("displayDays") val displayDays: String? = null // Visibility: display only on these days
)
