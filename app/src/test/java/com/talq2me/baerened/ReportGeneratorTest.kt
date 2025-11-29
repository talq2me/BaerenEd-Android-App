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
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
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
        assertTrue(textReport.contains("Reward Time Used: 30 minutes"))
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
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
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
}

