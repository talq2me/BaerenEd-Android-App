package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for GameEngine
 * Tests game logic, answer submission, and progress tracking
 */
class GameEngineTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockSettingsManager: SettingsManager
    private lateinit var questions: List<GameData>
    private lateinit var config: GameConfig

    @Before
    fun setup() {
        mockContext = mockk<Context>(relaxed = true)
        mockPrefs = mockk<SharedPreferences>(relaxed = true)
        mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)

        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just Runs
        every { mockPrefs.getInt(any(), any()) } returns 0

        // Mock SettingsManager
        mockkObject(SettingsManager)
        every { SettingsManager.readProfile(any()) } returns "A"

        // Create sample questions
        questions = listOf(
            GameData(
                prompt = Prompt(text = "Question 1"),
                question = Question(text = "What is 2+2?", lang = "en"),
                correctChoices = listOf(Choice(text = "4")),
                extraChoices = listOf(Choice(text = "3"), Choice(text = "5"))
            ),
            GameData(
                prompt = Prompt(text = "Question 2"),
                question = Question(text = "What is 3+3?", lang = "en"),
                correctChoices = listOf(Choice(text = "6")),
                extraChoices = listOf(Choice(text = "5"), Choice(text = "7"))
            ),
            GameData(
                prompt = Prompt(text = "Question 3"),
                question = Question(text = "What is 4+4?", lang = "en"),
                correctChoices = listOf(Choice(text = "8")),
                extraChoices = listOf(Choice(text = "7"), Choice(text = "9"))
            )
        )

        config = GameConfig(
            launch = "testGame",
            requiredCorrectAnswers = 2
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkObject(SettingsManager)
    }

    @Test
    fun `getCurrentQuestion returns first question initially`() {
        // Given: A new game engine
        val engine = GameEngine(mockContext, "testGame", questions, config)

        // When: Getting current question
        val question = engine.getCurrentQuestion()

        // Then: Should return first question
        assertEquals("Question 1", question.prompt?.text)
    }

    @Test
    fun `submitAnswer returns true for correct answer`() {
        // Given: A game engine
        val engine = GameEngine(mockContext, "testGame", questions, config)

        // When: Submitting correct answer
        val isCorrect = engine.submitAnswer(listOf("4"))

        // Then: Should return true
        assertTrue(isCorrect)
        assertEquals(1, engine.getCorrectCount())
        assertEquals(0, engine.getIncorrectCount())
    }

    @Test
    fun `submitAnswer returns false for incorrect answer`() {
        // Given: A game engine
        val engine = GameEngine(mockContext, "testGame", questions, config)

        // When: Submitting incorrect answer
        val isCorrect = engine.submitAnswer(listOf("3"))

        // Then: Should return false
        assertFalse(isCorrect)
        assertEquals(0, engine.getCorrectCount())
        assertEquals(1, engine.getIncorrectCount())
    }

    @Test
    fun `submitAnswer advances to next question on correct answer`() {
        // Given: A game engine
        val engine = GameEngine(mockContext, "testGame", questions, config)

        // When: Answering first question correctly
        engine.submitAnswer(listOf("4"))

        // Then: Next question should be question 2
        val nextQuestion = engine.getCurrentQuestion()
        assertEquals("Question 2", nextQuestion.prompt?.text)
    }

    @Test
    fun `submitAnswer stays on same question on incorrect answer`() {
        // Given: A game engine
        val engine = GameEngine(mockContext, "testGame", questions, config)

        // When: Answering incorrectly
        engine.submitAnswer(listOf("3"))

        // Then: Should still be on first question
        val currentQuestion = engine.getCurrentQuestion()
        assertEquals("Question 1", currentQuestion.prompt?.text)
    }

    @Test
    fun `shouldEndGame returns true when required correct answers reached`() {
        // Given: A game engine with config requiring 2 correct answers
        val engine = GameEngine(mockContext, "testGame", questions, config)

        // When: Answering 2 questions correctly
        engine.submitAnswer(listOf("4"))
        engine.submitAnswer(listOf("6"))

        // Then: Should end game
        assertTrue(engine.shouldEndGame())
    }

    @Test
    fun `shouldEndGame returns false when required correct answers not reached`() {
        // Given: A game engine with config requiring 2 correct answers
        val engine = GameEngine(mockContext, "testGame", questions, config)

        // When: Answering only 1 question correctly
        engine.submitAnswer(listOf("4"))

        // Then: Should not end game
        assertFalse(engine.shouldEndGame())
    }

    @Test
    fun `getCurrentQuestion wraps around when index exceeds questions size`() {
        // Given: A game engine with progress at index 5 (beyond questions size)
        every { mockPrefs.getInt(any(), any()) } returns 5
        val engine = GameEngine(mockContext, "testGame", questions, config)

        // When: Getting current question
        val question = engine.getCurrentQuestion()

        // Then: Should wrap around (5 % 3 = 2, so question 3)
        assertEquals("Question 3", question.prompt?.text)
    }

    @Test
    fun `submitAnswer handles multiple correct choices`() {
        // Given: A question with multiple correct answers
        val multiChoiceQuestion = GameData(
            prompt = Prompt(text = "Select even numbers"),
            question = Question(text = "Which are even?", lang = "en"),
            correctChoices = listOf(Choice(text = "2"), Choice(text = "4")),
            extraChoices = listOf(Choice(text = "3"), Choice(text = "5"))
        )
        val engine = GameEngine(mockContext, "testGame", listOf(multiChoiceQuestion), config)

        // When: Submitting all correct answers
        val isCorrect = engine.submitAnswer(listOf("2", "4"))

        // Then: Should be correct
        assertTrue(isCorrect)
    }

    @Test
    fun `submitAnswer handles partial correct answers as incorrect`() {
        // Given: A question with multiple correct answers
        val multiChoiceQuestion = GameData(
            prompt = Prompt(text = "Select even numbers"),
            question = Question(text = "Which are even?", lang = "en"),
            correctChoices = listOf(Choice(text = "2"), Choice(text = "4")),
            extraChoices = listOf(Choice(text = "3"), Choice(text = "5"))
        )
        val engine = GameEngine(mockContext, "testGame", listOf(multiChoiceQuestion), config)

        // When: Submitting only one correct answer
        val isCorrect = engine.submitAnswer(listOf("2"))

        // Then: Should be incorrect (missing "4")
        assertFalse(isCorrect)
    }

    @Test
    fun `getCorrectCount and getIncorrectCount track answers correctly`() {
        // Given: A game engine
        val engine = GameEngine(mockContext, "testGame", questions, config)

        // When: Answering multiple questions
        engine.submitAnswer(listOf("4")) // Correct
        engine.submitAnswer(listOf("3")) // Incorrect
        engine.submitAnswer(listOf("6")) // Correct

        // Then: Counts should be accurate
        assertEquals(2, engine.getCorrectCount())
        assertEquals(1, engine.getIncorrectCount())
    }
}

