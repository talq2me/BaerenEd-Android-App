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

        // When: Answering multiple questions (3 different questions)
        engine.submitAnswer(listOf("4")) // Correct for question 1 -> moves to question 2
        engine.submitAnswer(listOf("6")) // Correct for question 2 -> moves to question 3
        engine.submitAnswer(listOf("3")) // Incorrect for question 3 (correct is "8") -> stays on question 3

        // Then: Counts should be accurate
        assertEquals(2, engine.getCorrectCount())
        assertEquals(1, engine.getIncorrectCount())
    }

    @Test
    fun `only first answer per question is counted - wrong multiple times then correct`() {
        // Given: A game engine with a single question
        val singleQuestion = listOf(questions[0])
        val engine = GameEngine(mockContext, "testGame", singleQuestion, GameConfig("testGame", 1))

        // When: Answering wrong 3 times, then correctly
        engine.submitAnswer(listOf("3")) // Wrong - first answer, should count as incorrect
        engine.submitAnswer(listOf("5")) // Wrong again - should NOT count
        engine.submitAnswer(listOf("7")) // Wrong again - should NOT count
        engine.submitAnswer(listOf("4")) // Correct - should NOT count as correct since first was wrong

        // Then: Should only count the first answer (wrong)
        assertEquals(0, engine.getCorrectCount())
        assertEquals(1, engine.getIncorrectCount())
        // Total should equal 1 (the one question answered)
        assertEquals(1, engine.getCorrectCount() + engine.getIncorrectCount())
    }

    @Test
    fun `only first answer per question is counted - correct on first try`() {
        // Given: A game engine with a single question
        val singleQuestion = listOf(questions[0])
        val engine = GameEngine(mockContext, "testGame", singleQuestion, GameConfig("testGame", 1))

        // When: Answering correctly on first try
        engine.submitAnswer(listOf("4")) // Correct - first answer

        // Then: Should count as correct
        assertEquals(1, engine.getCorrectCount())
        assertEquals(0, engine.getIncorrectCount())
        // Total should equal 1
        assertEquals(1, engine.getCorrectCount() + engine.getIncorrectCount())
    }

    @Test
    fun `answer counts add up to total questions answered - 5 questions scenario`() {
        // Given: A game engine with 5 questions, requiring all 5 correct
        val fiveQuestions = listOf(
            GameData(prompt = Prompt(text = "Q1"), question = Question(text = "1+1?", lang = "en"), correctChoices = listOf(Choice(text = "2")), extraChoices = listOf()),
            GameData(prompt = Prompt(text = "Q2"), question = Question(text = "2+2?", lang = "en"), correctChoices = listOf(Choice(text = "4")), extraChoices = listOf()),
            GameData(prompt = Prompt(text = "Q3"), question = Question(text = "3+3?", lang = "en"), correctChoices = listOf(Choice(text = "6")), extraChoices = listOf()),
            GameData(prompt = Prompt(text = "Q4"), question = Question(text = "4+4?", lang = "en"), correctChoices = listOf(Choice(text = "8")), extraChoices = listOf()),
            GameData(prompt = Prompt(text = "Q5"), question = Question(text = "5+5?", lang = "en"), correctChoices = listOf(Choice(text = "10")), extraChoices = listOf())
        )
        val engine = GameEngine(mockContext, "testGame", fiveQuestions, GameConfig("testGame", 5))

        // When: Answering all 5 questions (some wrong first, then correct)
        engine.submitAnswer(listOf("2")) // Q1: Correct
        engine.submitAnswer(listOf("3")) // Q2: Wrong first
        engine.submitAnswer(listOf("4")) // Q2: Correct (shouldn't count - first was wrong)
        engine.submitAnswer(listOf("6")) // Q3: Correct
        engine.submitAnswer(listOf("7")) // Q4: Wrong first
        engine.submitAnswer(listOf("8")) // Q4: Correct (shouldn't count)
        engine.submitAnswer(listOf("10")) // Q5: Correct

        // Then: Should have answered 5 questions total
        // Q1: 1 correct, Q2: 1 incorrect, Q3: 1 correct, Q4: 1 incorrect, Q5: 1 correct
        assertEquals(3, engine.getCorrectCount())
        assertEquals(2, engine.getIncorrectCount())
        // Total should equal 5 (one answer per question)
        assertEquals(5, engine.getCorrectCount() + engine.getIncorrectCount())
    }

    @Test
    fun `multiple wrong attempts on same question only count once as incorrect`() {
        // Given: A game engine with one question
        val singleQuestion = listOf(questions[0])
        val engine = GameEngine(mockContext, "testGame", singleQuestion, GameConfig("testGame", 1))

        // When: Answering wrong multiple times on the same question
        engine.submitAnswer(listOf("3")) // Wrong attempt 1 - should count
        engine.submitAnswer(listOf("5")) // Wrong attempt 2 - should NOT count
        engine.submitAnswer(listOf("7")) // Wrong attempt 3 - should NOT count

        // Then: Should only count 1 incorrect (the first answer)
        assertEquals(0, engine.getCorrectCount())
        assertEquals(1, engine.getIncorrectCount())
    }
}

