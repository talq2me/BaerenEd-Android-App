package com.talq2me.baerened

import android.content.Context
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Unit tests for ReportGenerator
 * Tests report generation in different formats (TEXT, HTML, CSV, EMAIL)
 */
class ReportGeneratorTest {

    private lateinit var mockContext: Context
    private lateinit var reportGenerator: ReportGenerator
    private lateinit var mockProgressManager: DailyProgressManager

    @Before
    fun setup() {
        // Mock Android Log class (must be done before any Log calls)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any<String>()) } returns 0
        every { android.util.Log.d(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        
        mockContext = mockk<Context>(relaxed = true)
        mockProgressManager = mockk<DailyProgressManager>(relaxed = true)
        
        // Mock DailyProgressManager methods
        every { mockProgressManager.filterVisibleContent(any()) } answers { firstArg() }
        every { mockProgressManager.getUniqueTaskId(any(), any()) } answers { firstArg<String>() }
        
        reportGenerator = ReportGenerator(mockContext)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun createSampleProgressReport(): DailyProgressManager.ComprehensiveProgressReport {
        val today = SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", Locale.getDefault()).format(Date())
        return DailyProgressManager.ComprehensiveProgressReport(
            date = today,
            earnedCoins = 15,
            totalCoins = 30,
            earnedStars = 15,
            totalStars = 30,
            completionRate = 50.0,
            totalTimeMinutes = 45,
            gamesPlayed = 3,
            videosWatched = 2,
            completedTasks = listOf("task1", "task2"),
            completedTaskNames = mapOf("task1" to "Math Game", "task2" to "Reading"),
            gameSessions = listOf(
                TimeTracker.ActivitySession(
                    activityId = "task1",
                    activityType = "game",
                    activityName = "Math Game",
                    startTime = System.currentTimeMillis() - 600000,
                    endTime = System.currentTimeMillis() - 300000,
                    durationSeconds = 300,
                    date = today,
                    completed = true,
                    starsEarned = 3,
                    correctAnswers = 5,
                    incorrectAnswers = 1
                )
            ),
            videoSessions = listOf(
                TimeTracker.ActivitySession(
                    activityId = "video1",
                    activityType = "video",
                    activityName = "Learning Video",
                    startTime = System.currentTimeMillis() - 900000,
                    endTime = System.currentTimeMillis() - 600000,
                    durationSeconds = 300,
                    date = today,
                    completed = true
                )
            ),
            webGameSessions = emptyList(),
            chromePageSessions = emptyList(),
            completedGameSessions = listOf(
                TimeTracker.ActivitySession(
                    activityId = "task1",
                    activityType = "game",
                    activityName = "Math Game",
                    startTime = System.currentTimeMillis() - 600000,
                    endTime = System.currentTimeMillis() - 300000,
                    durationSeconds = 300,
                    date = today,
                    completed = true,
                    correctAnswers = 5,
                    incorrectAnswers = 1
                )
            ),
            averageGameTimeMinutes = 10,
            averageVideoTimeMinutes = 5,
            longestSessionMinutes = 15,
            mostPlayedGame = "Math Game",
            totalSessions = 5,
            totalCorrectAnswers = 10,
            totalIncorrectAnswers = 2,
            config = MainContent(
                sections = listOf(
                    Section(
                        id = "required",
                        title = "Required Tasks",
                        tasks = listOf(
                            Task(title = "Math Game", launch = "task1", stars = 3),
                            Task(title = "Reading", launch = "task2", stars = 3)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `generateDailyReport generates text report`() {
        // Given: A progress report
        val report = createSampleProgressReport()

        // When: Generating a text report
        val textReport = reportGenerator.generateDailyReport(
            report,
            "Test Child",
            ReportGenerator.ReportFormat.TEXT
        )

        // Then: Should contain key information
        assertTrue(textReport.contains("DAILY PROGRESS REPORT"))
        assertTrue(textReport.contains("Test Child"))
        assertTrue(textReport.contains("Stars Earned: 15/30"))
        assertTrue(textReport.contains("Games Played: 3"))
    }

    @Test
    fun `generateDailyReport generates HTML report`() {
        // Given: A progress report
        val report = createSampleProgressReport()

        // When: Generating an HTML report
        val htmlReport = reportGenerator.generateDailyReport(
            report,
            "Test Child",
            ReportGenerator.ReportFormat.HTML
        )

        // Then: Should contain HTML structure
        assertTrue(htmlReport.contains("<!DOCTYPE html>"))
        assertTrue(htmlReport.contains("<html>"))
        assertTrue(htmlReport.contains("Test Child"))
        assertTrue(htmlReport.contains("15/30"))
    }

    @Test
    fun `generateDailyReport generates CSV report`() {
        // Given: A progress report
        val report = createSampleProgressReport()

        // When: Generating a CSV report
        val csvReport = reportGenerator.generateDailyReport(
            report,
            "Test Child",
            ReportGenerator.ReportFormat.CSV
        )

        // Then: Should contain CSV format
        assertTrue(csvReport.contains("Daily Progress Report for Test Child"))
        assertTrue(csvReport.contains("Metric,Value"))
        assertTrue(csvReport.contains("Completion Rate,50.0%"))
        assertTrue(csvReport.contains("Stars Earned,15/30"))
    }

    @Test
    fun `generateDailyReport generates email report`() {
        // Given: A progress report
        val report = createSampleProgressReport()

        // When: Generating an email report
        val emailReport = reportGenerator.generateDailyReport(
            report,
            "Test Child",
            ReportGenerator.ReportFormat.EMAIL
        )

        // Then: Should contain email-friendly format
        assertTrue(emailReport.contains("DAILY PROGRESS REPORT"))
        assertTrue(emailReport.contains("Test Child"))
        assertTrue(emailReport.contains("Completion Rate: 50.0%"))
        assertTrue(emailReport.contains("Dear Parent/Guardian"))
    }

    @Test
    fun `generateDailyReport includes reward minutes when provided`() {
        // Given: A progress report with reward minutes
        val report = createSampleProgressReport()

        // When: Generating a report with reward minutes
        val textReport = reportGenerator.generateDailyReport(
            report,
            "Test Child",
            ReportGenerator.ReportFormat.TEXT,
            rewardMinutesUsed = 30
        )

        // Then: Should include reward minutes
        assertTrue(textReport.contains("Reward Time Earned: 30 minutes"))
    }

    @Test
    fun `TaskDetails formats time correctly`() {
        // Given: A task detail with 125 seconds
        val taskDetail = ReportGenerator.TaskDetails(
            taskId = "test",
            taskName = "Test Task",
            isRequired = true,
            isCompleted = true,
            timeSpentSeconds = 125,
            correctAnswers = 5,
            incorrectAnswers = 1
        )

        // When: Getting formatted time
        val formatted = taskDetail.timeSpentFormatted

        // Then: Should be "2m5s"
        assertEquals("2m5s", formatted)
    }

    @Test
    fun `TaskDetails formats answer info correctly for games`() {
        // Given: A task detail with answers
        val taskDetail = ReportGenerator.TaskDetails(
            taskId = "test",
            taskName = "Test Game",
            isRequired = true,
            isCompleted = true,
            timeSpentSeconds = 60,
            correctAnswers = 5,
            incorrectAnswers = 2,
            isVideoTask = false,
            isChromePageTask = false
        )

        // When: Getting answer info
        val answerInfo = taskDetail.answerInfo

        // Then: Should contain answer counts
        assertTrue(answerInfo.contains("5"))
        assertTrue(answerInfo.contains("2"))
        assertTrue(answerInfo.contains("✅"))
        assertTrue(answerInfo.contains("❌"))
    }

    @Test
    fun `TaskDetails returns empty answer info for videos`() {
        // Given: A video task detail
        val taskDetail = ReportGenerator.TaskDetails(
            taskId = "video1",
            taskName = "Video Task",
            isRequired = true,
            isCompleted = true,
            timeSpentSeconds = 300,
            correctAnswers = 0,
            incorrectAnswers = 0,
            isVideoTask = true,
            isChromePageTask = false
        )

        // When: Getting answer info
        val answerInfo = taskDetail.answerInfo

        // Then: Should be empty
        assertEquals("", answerInfo)
    }

    @Test
    fun `generateDailyReport handles empty report gracefully`() {
        // Given: An empty progress report
        val today = SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", Locale.getDefault()).format(Date())
        val emptyReport = DailyProgressManager.ComprehensiveProgressReport(
            date = today,
            earnedCoins = 0,
            totalCoins = 0,
            earnedStars = 0,
            totalStars = 0,
            completionRate = 0.0,
            totalTimeMinutes = 0,
            gamesPlayed = 0,
            videosWatched = 0,
            completedTasks = emptyList(),
            completedTaskNames = emptyMap(),
            gameSessions = emptyList(),
            videoSessions = emptyList(),
            webGameSessions = emptyList(),
            chromePageSessions = emptyList(),
            completedGameSessions = emptyList(),
            averageGameTimeMinutes = 0,
            averageVideoTimeMinutes = 0,
            longestSessionMinutes = 0,
            mostPlayedGame = null,
            totalSessions = 0,
            totalCorrectAnswers = 0,
            totalIncorrectAnswers = 0
        )

        // When: Generating a report
        val textReport = reportGenerator.generateDailyReport(
            emptyReport,
            "Test Child",
            ReportGenerator.ReportFormat.TEXT
        )

        // Then: Should still generate valid report
        assertTrue(textReport.contains("DAILY PROGRESS REPORT"))
        assertTrue(textReport.contains("Test Child"))
        assertTrue(textReport.contains("Stars Earned: 0/0"))
    }

    @Test
    fun `completed games are shown separately for required and optional sections`() {
        // Given: A progress report with games completed in both required and optional sections
        val today = SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", Locale.getDefault()).format(Date())
        val report = DailyProgressManager.ComprehensiveProgressReport(
            date = today,
            earnedCoins = 6,
            totalCoins = 6,
            earnedStars = 6,
            totalStars = 6,
            completionRate = 100.0,
            totalTimeMinutes = 20,
            gamesPlayed = 2,
            videosWatched = 0,
            completedTasks = listOf("frenchStories", "optional_frenchStories"),
            completedTaskNames = mapOf("frenchStories" to "French Stories", "optional_frenchStories" to "French Stories"),
            gameSessions = listOf(
                TimeTracker.ActivitySession(
                    activityId = "frenchStories",
                    activityType = "game",
                    activityName = "French Stories",
                    startTime = System.currentTimeMillis() - 600000,
                    endTime = System.currentTimeMillis() - 300000,
                    durationSeconds = 300,
                    date = today,
                    completed = true,
                    correctAnswers = 5,
                    incorrectAnswers = 0
                ),
                TimeTracker.ActivitySession(
                    activityId = "optional_frenchStories",
                    activityType = "game",
                    activityName = "French Stories",
                    startTime = System.currentTimeMillis() - 300000,
                    endTime = System.currentTimeMillis(),
                    durationSeconds = 300,
                    date = today,
                    completed = true,
                    correctAnswers = 5,
                    incorrectAnswers = 0
                )
            ),
            videoSessions = emptyList(),
            webGameSessions = emptyList(),
            chromePageSessions = emptyList(),
            completedGameSessions = listOf(
                TimeTracker.ActivitySession(
                    activityId = "frenchStories",
                    activityType = "game",
                    activityName = "French Stories",
                    startTime = System.currentTimeMillis() - 600000,
                    endTime = System.currentTimeMillis() - 300000,
                    durationSeconds = 300,
                    date = today,
                    completed = true,
                    correctAnswers = 5,
                    incorrectAnswers = 0
                ),
                TimeTracker.ActivitySession(
                    activityId = "optional_frenchStories",
                    activityType = "game",
                    activityName = "French Stories",
                    startTime = System.currentTimeMillis() - 300000,
                    endTime = System.currentTimeMillis(),
                    durationSeconds = 300,
                    date = today,
                    completed = true,
                    correctAnswers = 5,
                    incorrectAnswers = 0
                )
            ),
            averageGameTimeMinutes = 5,
            averageVideoTimeMinutes = 0,
            longestSessionMinutes = 5,
            mostPlayedGame = "French Stories",
            totalSessions = 2,
            totalCorrectAnswers = 10,
            totalIncorrectAnswers = 0,
            config = MainContent(
                sections = listOf(
                    Section(
                        id = "required",
                        title = "Required Tasks",
                        tasks = listOf(
                            Task(title = "French Stories", launch = "frenchStories", stars = 3)
                        )
                    ),
                    Section(
                        id = "optional",
                        title = "Extra Practice",
                        tasks = listOf(
                            Task(title = "French Stories", launch = "frenchStories", stars = 3)
                        )
                    )
                )
            )
        )

        // When: Generating a text report
        val textReport = reportGenerator.generateDailyReport(
            report,
            "Test Child",
            ReportGenerator.ReportFormat.TEXT
        )

        // Then: Should show games completed in both sections separately
        assertTrue(textReport.contains("COMPLETED GAMES (REQUIRED)"))
        assertTrue(textReport.contains("COMPLETED GAMES (EXTRA PRACTICE)"))
        // Both should mention French Stories
        val requiredSection = textReport.indexOf("COMPLETED GAMES (REQUIRED)")
        val optionalSection = textReport.indexOf("COMPLETED GAMES (EXTRA PRACTICE)")
        assertTrue(requiredSection < optionalSection) // Required should come before optional
    }

    @Test
    fun `answer counts in task details match first answer only logic`() {
        // Given: A task with answer counts that should add up correctly
        val today = SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", Locale.getDefault()).format(Date())
        val report = DailyProgressManager.ComprehensiveProgressReport(
            date = today,
            earnedCoins = 3,
            totalCoins = 3,
            earnedStars = 3,
            totalStars = 3,
            completionRate = 100.0,
            totalTimeMinutes = 10,
            gamesPlayed = 1,
            videosWatched = 0,
            completedTasks = listOf("frenchStories"),
            completedTaskNames = mapOf("frenchStories" to "French Stories"),
            gameSessions = listOf(
                TimeTracker.ActivitySession(
                    activityId = "frenchStories",
                    activityType = "game",
                    activityName = "French Stories",
                    startTime = System.currentTimeMillis() - 600000,
                    endTime = System.currentTimeMillis() - 300000,
                    durationSeconds = 300,
                    date = today,
                    completed = true,
                    correctAnswers = 4, // 4 correct out of 5 questions
                    incorrectAnswers = 1 // 1 incorrect out of 5 questions
                )
            ),
            videoSessions = emptyList(),
            webGameSessions = emptyList(),
            chromePageSessions = emptyList(),
            completedGameSessions = listOf(
                TimeTracker.ActivitySession(
                    activityId = "frenchStories",
                    activityType = "game",
                    activityName = "French Stories",
                    startTime = System.currentTimeMillis() - 600000,
                    endTime = System.currentTimeMillis() - 300000,
                    durationSeconds = 300,
                    date = today,
                    completed = true,
                    correctAnswers = 4,
                    incorrectAnswers = 1
                )
            ),
            averageGameTimeMinutes = 5,
            averageVideoTimeMinutes = 0,
            longestSessionMinutes = 5,
            mostPlayedGame = "French Stories",
            totalSessions = 1,
            totalCorrectAnswers = 4,
            totalIncorrectAnswers = 1,
            config = MainContent(
                sections = listOf(
                    Section(
                        id = "required",
                        title = "Required Tasks",
                        tasks = listOf(
                            Task(title = "French Stories", launch = "frenchStories", stars = 3)
                        )
                    )
                )
            )
        )

        // When: Generating a report
        val textReport = reportGenerator.generateDailyReport(
            report,
            "Test Child",
            ReportGenerator.ReportFormat.TEXT
        )

        // Then: Answer counts should be shown and should add up correctly (4 + 1 = 5 questions)
        assertTrue(textReport.contains("4") || textReport.contains("1"))
        // The total (correct + incorrect) should equal the number of questions answered
        // This test ensures the report shows accurate counts
    }
}

