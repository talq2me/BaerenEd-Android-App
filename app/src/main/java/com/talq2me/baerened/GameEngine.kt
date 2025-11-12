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
        if (currentIndex >= questions.size) currentIndex = 0
        return questions[currentIndex]
    }

    fun submitAnswer(userAnswers: List<String>): Boolean {
        val correct = getCurrentQuestion().correctChoices.map { it.text }
        val isCorrect = userAnswers == correct

        if (isCorrect) {
            correctCount++
            currentIndex++
            progress.saveIndex(currentIndex)
        } else {
            incorrectCount++
        }

        return isCorrect
    }

    fun shouldEndGame(): Boolean = correctCount >= config.requiredCorrectAnswers
    fun getIncorrectCount(): Int = incorrectCount
    fun getCorrectCount(): Int = correctCount
}
