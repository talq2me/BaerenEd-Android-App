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
    // Track which questions have been answered (to only count first answer per question)
    private val answeredQuestions = mutableSetOf<Int>()

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

        // Only count the FIRST answer per question (correct or incorrect)
        val isFirstAnswer = !answeredQuestions.contains(currentWrappedIndex)
        
        if (isFirstAnswer) {
            // This is the first answer for this question - count it
            answeredQuestions.add(currentWrappedIndex)
            if (isCorrect) {
                correctCount++
                android.util.Log.d("GameEngine", "Answer submitted - correct on first try, correctCount: $correctCount/${config.requiredCorrectAnswers}")
            } else {
                incorrectCount++
                android.util.Log.d("GameEngine", "Answer submitted - incorrect on first try, incorrectCount: $incorrectCount")
            }
        } else {
            android.util.Log.d("GameEngine", "Answer submitted - question already answered (not counting), isCorrect: $isCorrect")
        }

        // Only advance to next question when answer is correct
        if (isCorrect) {
            currentIndex++
            progress.saveIndex(currentIndex)
            android.util.Log.d("GameEngine", "Advanced currentIndex to: $currentIndex")
        } else {
            android.util.Log.d("GameEngine", "Staying on currentIndex: $currentIndex (answer was incorrect)")
        }

        return isCorrect
    }

    fun shouldEndGame(): Boolean = correctCount >= config.requiredCorrectAnswers
    fun getIncorrectCount(): Int = incorrectCount
    fun getCorrectCount(): Int = correctCount
    fun getCurrentIndex(): Int = currentIndex
}
