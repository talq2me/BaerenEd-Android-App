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
    private lateinit var questions: List<GameData>
    private lateinit var config: GameConfig

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any<String>()) } returns 0
        every { android.util.Log.d(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0

        mockContext = mockk<Context>(relaxed = true)
        mockPrefs = mockk<SharedPreferences>(relaxed = true)
        mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)

        val gameProgressPrefs = mockk<SharedPreferences>(relaxed = true)
        val gameProgressEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { gameProgressPrefs.edit() } returns gameProgressEditor
        every { gameProgressEditor.putInt(any(), any()) } returns gameProgressEditor
        every { gameProgressEditor.apply() } just Runs
        every { gameProgressPrefs.getInt(any(), any()) } returns 0

        val settingsPrefs = mockk<SharedPreferences>(relaxed = true)
        every { settingsPrefs.getString("profile", null) } returns "AM"

        every { mockContext.getSharedPreferences("game_progress", any()) } returns gameProgressPrefs
        every { mockContext.getSharedPreferences("baeren_shared_settings", any()) } returns settingsPrefs
        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs

        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just Runs
        every { mockPrefs.getInt(any(), any()) } returns 0

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
    }

    @Test
    fun `getCurrentQuestion returns first question initially`() {
        val engine = GameEngine(mockContext, "testGame", questions, config)
        assertEquals("Question 1", engine.getCurrentQuestion().prompt?.text)
    }

    @Test
    fun `submitAnswer returns true for correct answer`() {
        val engine = GameEngine(mockContext, "testGame", questions, config)
        assertTrue(engine.submitAnswer(listOf("4")))
        assertEquals(1, engine.getCorrectCount())
        assertEquals(0, engine.getIncorrectCount())
    }

    @Test
    fun `submitAnswer returns false for incorrect answer`() {
        val engine = GameEngine(mockContext, "testGame", questions, config)
        assertFalse(engine.submitAnswer(listOf("3")))
        assertEquals(0, engine.getCorrectCount())
        assertEquals(1, engine.getIncorrectCount())
    }

    @Test
    fun `submitAnswer advances to next question on correct answer`() {
        val engine = GameEngine(mockContext, "testGame", questions, config)
        engine.submitAnswer(listOf("4"))
        assertEquals("Question 2", engine.getCurrentQuestion().prompt?.text)
    }

    @Test
    fun `submitAnswer advances to next question on incorrect answer`() {
        val engine = GameEngine(mockContext, "testGame", questions, config)
        engine.submitAnswer(listOf("3"))
        assertEquals("Question 2", engine.getCurrentQuestion().prompt?.text)
    }

    @Test
    fun `shouldEndGame returns true when required correct answers reached`() {
        val engine = GameEngine(mockContext, "testGame", questions, config)
        engine.submitAnswer(listOf("4"))
        engine.submitAnswer(listOf("6"))
        assertTrue(engine.shouldEndGame())
    }

    @Test
    fun `shouldEndGame returns false when required correct answers not reached`() {
        val engine = GameEngine(mockContext, "testGame", questions, config)
        engine.submitAnswer(listOf("4"))
        assertFalse(engine.shouldEndGame())
    }

    @Test
    fun `shouldEndGame returns false when only incorrect answers match required count`() {
        val engine = GameEngine(mockContext, "testGame", questions, GameConfig("testGame", 2))
        engine.submitAnswer(listOf("3"))
        engine.submitAnswer(listOf("5"))
        assertFalse(engine.shouldEndGame())
        assertEquals(2, engine.getIncorrectCount())
        assertEquals(0, engine.getCorrectCount())
    }

    @Test
    fun `five wrong then five right completes when five correct required`() {
        val fiveQuestions = listOf(
            GameData(prompt = Prompt(text = "Q1"), question = Question(text = "1+1?", lang = "en"), correctChoices = listOf(Choice(text = "2")), extraChoices = listOf(Choice(text = "1"))),
            GameData(prompt = Prompt(text = "Q2"), question = Question(text = "2+2?", lang = "en"), correctChoices = listOf(Choice(text = "4")), extraChoices = listOf(Choice(text = "3"))),
            GameData(prompt = Prompt(text = "Q3"), question = Question(text = "3+3?", lang = "en"), correctChoices = listOf(Choice(text = "6")), extraChoices = listOf(Choice(text = "5"))),
            GameData(prompt = Prompt(text = "Q4"), question = Question(text = "4+4?", lang = "en"), correctChoices = listOf(Choice(text = "8")), extraChoices = listOf(Choice(text = "7"))),
            GameData(prompt = Prompt(text = "Q5"), question = Question(text = "5+5?", lang = "en"), correctChoices = listOf(Choice(text = "10")), extraChoices = listOf(Choice(text = "9")))
        )
        val engine = GameEngine(mockContext, "testGame", fiveQuestions, GameConfig("testGame", 5))

        repeat(5) { engine.submitAnswer(listOf("1")) }
        assertFalse(engine.shouldEndGame())
        assertEquals(0, engine.getCorrectCount())
        assertEquals(5, engine.getIncorrectCount())
        assertEquals(5, engine.getQuestionsAnswered())

        engine.submitAnswer(listOf("2"))
        engine.submitAnswer(listOf("4"))
        engine.submitAnswer(listOf("6"))
        engine.submitAnswer(listOf("8"))
        assertFalse(engine.shouldEndGame())

        engine.submitAnswer(listOf("10"))
        assertTrue(engine.shouldEndGame())
        assertEquals(5, engine.getCorrectCount())
        assertEquals(5, engine.getIncorrectCount())
        assertEquals(10, engine.getQuestionsAnswered())
    }

    @Test
    fun `getCurrentQuestion wraps around when index exceeds questions size`() {
        mockkObject(SettingsManager)
        every { SettingsManager.readProfile(any()) } returns "AM"
        try {
            val dpm = DailyProgressManager(mockContext)
            dpm.setProgressDataAfterFetch(
                DbUserData(profile = "AM", gameIndices = mapOf("testGame" to 5))
            )
            val engine = GameEngine(mockContext, "testGame", questions, config)
            assertEquals("Question 3", engine.getCurrentQuestion().prompt?.text)
        } finally {
            DailyProgressManager(mockContext).setProgressDataAfterFetch(null)
            unmockkObject(SettingsManager)
        }
    }

    @Test
    fun `submitAnswer handles multiple correct choices`() {
        val multiChoiceQuestion = GameData(
            prompt = Prompt(text = "Select even numbers"),
            question = Question(text = "Which are even?", lang = "en"),
            correctChoices = listOf(Choice(text = "2"), Choice(text = "4")),
            extraChoices = listOf(Choice(text = "3"), Choice(text = "5"))
        )
        val engine = GameEngine(mockContext, "testGame", listOf(multiChoiceQuestion), config)
        assertTrue(engine.submitAnswer(listOf("2", "4")))
    }

    @Test
    fun `submitAnswer handles partial correct answers as incorrect`() {
        val multiChoiceQuestion = GameData(
            prompt = Prompt(text = "Select even numbers"),
            question = Question(text = "Which are even?", lang = "en"),
            correctChoices = listOf(Choice(text = "2"), Choice(text = "4")),
            extraChoices = listOf(Choice(text = "3"), Choice(text = "5"))
        )
        val engine = GameEngine(mockContext, "testGame", listOf(multiChoiceQuestion), config)
        assertFalse(engine.submitAnswer(listOf("2")))
    }
}
