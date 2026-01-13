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
    // Track which questions have been answered incorrectly (to only count once per question)
    private val questionsWithIncorrectAttempts = mutableSetOf<Int>()

    init {
        android.util.Log.d("GameEngine", "GameEngine initialized for launchId: $launchId, loaded currentIndex: $currentIndex, questions: ${questions.size}, requiredCorrect: ${config.requiredCorrectAnswers}")
    }

    fun getCurrentQuestion(): GameData {
        // Use the full question set, wrapping around when we reach the end
        val wrappedIndex = currentIndex % questions.size
        android.util.Log.d("GameEngine", "getCurrentQuestion - currentIndex: $currentIndex, wrappedIndex: $wrappedIndex, totalQuestions: ${questions.size}")
        return questions[wrappedIndex]
    }

    fun submitAnswer(userAnswers: List<String>): Boolean {
        val currentWrappedIndex = currentIndex % questions.size
        val correct = getCurrentQuestion().correctChoices.map { it.text }
        val isCorrect = userAnswers == correct

        android.util.Log.d("GameEngine", "submitAnswer - currentIndex: $currentIndex, wrappedIndex: $currentWrappedIndex, question: ${questions[currentWrappedIndex].question?.text?.take(50)}...")

        // Count answers for scoring (correct/incorrect)
        if (isCorrect) {
            correctCount++
            // Remove from incorrect set if it was there (user eventually got it right)
            questionsWithIncorrectAttempts.remove(currentWrappedIndex)
            // Only advance to next question when answer is correct
            currentIndex++
            progress.saveIndex(currentIndex)
            android.util.Log.d("GameEngine", "Answer submitted - correct: $isCorrect, correctCount: $correctCount/${config.requiredCorrectAnswers}, advanced currentIndex to: $currentIndex")
        } else {
            // Only count incorrect once per question (first time we get it wrong)
            if (!questionsWithIncorrectAttempts.contains(currentWrappedIndex)) {
                questionsWithIncorrectAttempts.add(currentWrappedIndex)
                incorrectCount++
                android.util.Log.d("GameEngine", "Answer submitted - correct: $isCorrect, first incorrect attempt for this question, incorrectCount: $incorrectCount, staying on currentIndex: $currentIndex")
            } else {
                android.util.Log.d("GameEngine", "Answer submitted - correct: $isCorrect, retry attempt (not counting), staying on currentIndex: $currentIndex")
            }
        }

        return isCorrect
    }

    fun shouldEndGame(): Boolean = correctCount >= config.requiredCorrectAnswers
    fun getIncorrectCount(): Int = incorrectCount
    fun getCorrectCount(): Int = correctCount
}
