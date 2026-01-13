package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Repository interface for progress storage
 * This abstracts the storage layer from business logic
 */
interface ProgressRepository {
    fun getCompletedTasks(profile: String): Map<String, Boolean>
    fun saveCompletedTasks(profile: String, completedTasks: Map<String, Boolean>)
    
    fun getCompletedTaskNames(profile: String): Map<String, String>
    fun saveCompletedTaskName(profile: String, taskId: String, taskName: String)
    
    fun getLastResetDate(): String
    fun setLastResetDate(date: String)
    
    fun getBankedRewardMinutes(profile: String): Int
    fun setBankedRewardMinutes(profile: String, minutes: Int)
    
    fun getTotalPossibleStars(profile: String): Int
    fun setTotalPossibleStars(profile: String, stars: Int)
    
    fun getUnlockedPokemonCount(): Int
    fun setUnlockedPokemonCount(count: Int)
    
    fun getLastPokemonUnlockDate(): String
    fun setLastPokemonUnlockDate(date: String)
    
    fun getEarnedStarsAtBattleEnd(): Int
    fun setEarnedStarsAtBattleEnd(amount: Int)
    
    fun getEarnedBerries(profile: String): Int
    fun setEarnedBerries(profile: String, amount: Int)
}

/**
 * SharedPreferences implementation of ProgressRepository
 */
class SharedPreferencesProgressRepository(private val context: Context) : ProgressRepository {
    
    companion object {
        private const val TAG = "ProgressRepository"
        private const val PREF_NAME = "daily_progress_prefs"
        private const val KEY_COMPLETED_TASKS = "completed_tasks"
        private const val KEY_COMPLETED_TASK_NAMES = "completed_task_names"
        private const val KEY_LAST_RESET_DATE = "last_reset_date"
        private const val KEY_TOTAL_POSSIBLE_STARS = "total_possible_stars"
        private const val KEY_POKEMON_UNLOCKED = "pokemon_unlocked"
        private const val KEY_LAST_POKEMON_UNLOCK_DATE = "last_pokemon_unlock_date"
        private const val KEY_EARNED_STARS_AT_BATTLE_END = "earned_stars_at_battle_end"
        private const val POKEMON_PREF_NAME = "pokemonBattleHub"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val pokemonPrefs: SharedPreferences = context.getSharedPreferences(POKEMON_PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    override fun getCompletedTasks(profile: String): Map<String, Boolean> {
        val key = "${profile}_$KEY_COMPLETED_TASKS"
        val json = prefs.getString(key, "{}") ?: "{}"
        val type = object : TypeToken<Map<String, Boolean>>() {}.type
        return gson.fromJson(json, type) ?: emptyMap()
    }

    override fun saveCompletedTasks(profile: String, completedTasks: Map<String, Boolean>) {
        val key = "${profile}_$KEY_COMPLETED_TASKS"
        prefs.edit()
            .putString(key, gson.toJson(completedTasks))
            .apply()
    }

    override fun getCompletedTaskNames(profile: String): Map<String, String> {
        val key = "${profile}_$KEY_COMPLETED_TASK_NAMES"
        val json = prefs.getString(key, "{}") ?: "{}"
        val type = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(json, type) ?: emptyMap()
    }

    override fun saveCompletedTaskName(profile: String, taskId: String, taskName: String) {
        val taskNames = getCompletedTaskNames(profile).toMutableMap()
        taskNames[taskId] = taskName
        val key = "${profile}_$KEY_COMPLETED_TASK_NAMES"
        prefs.edit()
            .putString(key, gson.toJson(taskNames))
            .apply()
    }

    override fun getLastResetDate(): String {
        return prefs.getString(KEY_LAST_RESET_DATE, "") ?: ""
    }

    override fun setLastResetDate(date: String) {
        prefs.edit()
            .putString(KEY_LAST_RESET_DATE, date)
            .apply()
    }

    override fun getBankedRewardMinutes(profile: String): Int {
        val key = "${profile}_banked_reward_minutes"
        return prefs.getInt(key, 0)
    }

    override fun setBankedRewardMinutes(profile: String, minutes: Int) {
        val key = "${profile}_banked_reward_minutes"
        prefs.edit()
            .putInt(key, minutes)
            .apply()
    }

    override fun getTotalPossibleStars(profile: String): Int {
        val key = "${profile}_$KEY_TOTAL_POSSIBLE_STARS"
        return prefs.getInt(key, 0)
    }

    override fun setTotalPossibleStars(profile: String, stars: Int) {
        val key = "${profile}_$KEY_TOTAL_POSSIBLE_STARS"
        prefs.edit()
            .putInt(key, stars)
            .apply()
    }

    override fun getUnlockedPokemonCount(): Int {
        return prefs.getInt(KEY_POKEMON_UNLOCKED, 0)
    }

    override fun setUnlockedPokemonCount(count: Int) {
        prefs.edit()
            .putInt(KEY_POKEMON_UNLOCKED, count)
            .apply()
    }

    override fun getLastPokemonUnlockDate(): String {
        return prefs.getString(KEY_LAST_POKEMON_UNLOCK_DATE, "") ?: ""
    }

    override fun setLastPokemonUnlockDate(date: String) {
        prefs.edit()
            .putString(KEY_LAST_POKEMON_UNLOCK_DATE, date)
            .apply()
    }

    override fun getEarnedStarsAtBattleEnd(): Int {
        return prefs.getInt(KEY_EARNED_STARS_AT_BATTLE_END, 0)
    }

    override fun setEarnedStarsAtBattleEnd(amount: Int) {
        prefs.edit()
            .putInt(KEY_EARNED_STARS_AT_BATTLE_END, amount)
            .apply()
    }

    override fun getEarnedBerries(profile: String): Int {
        val key = "${profile}_earnedBerries"
        return pokemonPrefs.getInt(key, 0)
    }

    override fun setEarnedBerries(profile: String, amount: Int) {
        val key = "${profile}_earnedBerries"
        pokemonPrefs.edit()
            .putInt(key, amount)
            .apply()
    }
}
