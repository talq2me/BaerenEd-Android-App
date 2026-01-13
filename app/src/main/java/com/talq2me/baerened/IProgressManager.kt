package com.talq2me.baerened

/**
 * Interface for progress management
 * This allows for easier testing and dependency injection
 */
interface IProgressManager {
    fun markTaskCompletedWithName(
        taskId: String,
        taskName: String,
        stars: Int,
        isRequiredTask: Boolean = false,
        config: MainContent? = null,
        sectionId: String? = null
    ): Int
    
    fun getCompletedTasksMap(): Map<String, Boolean>
    
    fun isTaskCompleted(taskId: String): Boolean
    
    fun getCurrentKid(): String
    
    fun getEarnedBerries(): Int
    fun setEarnedBerries(amount: Int)
    fun addEarnedBerries(amount: Int)
    fun resetEarnedBerries()
    
    fun getBankedRewardMinutes(): Int
    fun setBankedRewardMinutes(minutes: Int)
    fun addBankedRewardMinutes(minutes: Int)
    fun clearBankedRewardMinutes()
    
    fun calculateTotalsFromConfig(config: MainContent): Pair<Int, Int>
    fun getCurrentProgressWithCoinsAndStars(config: MainContent): Pair<Pair<Int, Int>, Pair<Int, Int>>
    fun getCachedTotalPossibleStars(): Int
    
    fun getUnlockedPokemonCount(): Int
    fun setUnlockedPokemonCount(count: Int)
    fun unlockPokemon(count: Int): Boolean
    
    fun getEarnedStarsAtBattleEnd(): Int
    fun setEarnedStarsAtBattleEnd(amount: Int)
    fun resetBattleEndTracking()
    
    fun resetAllProgress()
    fun filterVisibleContent(originalContent: MainContent): MainContent
}
