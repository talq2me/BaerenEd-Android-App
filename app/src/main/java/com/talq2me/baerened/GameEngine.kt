package com.talq2me.baerened

import android.content.Context

// GameEngine.kt
class GameEngine(
    private val context: Context,
    private val launchId: String,
    private val questions: List<GameData>,
    private val config: GameConfig
) {
    private val progress = GameProgress(context, launchId)
    private var currentIndex = progress.getCurrentIndex()
    private var correctCount = 0
    private var incorrectCount = 0

    fun getCurrentQuestion(): GameData {
        // Use modulo to wrap around, but keep the index so we continue from where we left off
        val wrappedIndex = currentIndex % questions.size
        return questions[wrappedIndex]
    }

    fun submitAnswer(userAnswers: List<String>): Boolean {
        val correct = getCurrentQuestion().correctChoices.map { it.text }
        val isCorrect = userAnswers == correct

        if (isCorrect) {
            correctCount++
            currentIndex++
            // Save the next index so we continue from there next time
            progress.saveIndex(currentIndex)
        } else {
            incorrectCount++
            // Still save current index so we stay on the same question if they restart
            progress.saveIndex(currentIndex)
        }

        return isCorrect
    }

    fun shouldEndGame(): Boolean = correctCount >= config.requiredCorrectAnswers
    fun getIncorrectCount(): Int = incorrectCount
    fun getCorrectCount(): Int = correctCount
}
