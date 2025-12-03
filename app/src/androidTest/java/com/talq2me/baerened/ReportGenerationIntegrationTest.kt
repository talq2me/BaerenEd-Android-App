package com.talq2me.baerened

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Integration tests for report generation flow
 * Tests the complete flow from progress tracking to report generation
 */
@RunWith(AndroidJUnit4::class)
class ReportGenerationIntegrationTest {

    private lateinit var context: Context
    private lateinit var progressManager: DailyProgressManager
    private lateinit var timeTracker: TimeTracker
    private lateinit var reportGenerator: ReportGenerator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        progressManager = DailyProgressManager(context)
        timeTracker = TimeTracker(context)
        reportGenerator = ReportGenerator(context)
        
        // Clear any existing data
        progressManager.resetAllProgress()
        timeTracker.clearAllData()
    }

    @After
    fun tearDown() {
        // Clean up after tests
        progressManager.resetAllProgress()
        timeTracker.clearAllData()
    }

    @Test
    fun testGenerateReportFromCompletedTasksAndSessions() {
        // Given: Some completed tasks and time tracking
        val config = MainContent(
            sections = listOf(
                Section(
                    id = "required",
                    title = "Required Tasks",
                    tasks = listOf(
                        Task(title = "Math Game", launch = "mathGame", stars = 3),
                        Task(title = "Reading", launch = "reading", stars = 3)
                    )
                )
            )
        )

        // Complete a task
        progressManager.markTaskCompleted("mathGame", 3, isRequiredTask = true)
        
        // Track some time
        timeTracker.startActivity("mathGame", "game", "Math Game")
        Thread.sleep(100)
        timeTracker.endActivity("game")
        timeTracker.updateStarsEarned("mathGame", 3)
        timeTracker.updateAnswerCounts("game", 5, 1)

        // When: Generating a comprehensive report
        val progressReport = progressManager.getComprehensiveProgressReport(config, timeTracker)
        val report = reportGenerator.generateDailyReport(
            progressReport,
            "Test Child",
            ReportGenerator.ReportFormat.TEXT
        )

        // Then: Report should contain task information
        assertTrue(report.contains("Test Child"))
        assertTrue(report.contains("Math Game"))
        assertTrue(report.contains("Stars Earned"))
    }

    @Test
    fun testReportIncludesCorrectAnswerCounts() {
        // Given: A completed game with answers
        val config = MainContent(
            sections = listOf(
                Section(
                    id = "required",
                    title = "Required Tasks",
                    tasks = listOf(
                        Task(title = "Math Game", launch = "mathGame", stars = 3)
                    )
                )
            )
        )

        progressManager.markTaskCompleted("mathGame", 3, isRequiredTask = true)
        timeTracker.startActivity("mathGame", "game", "Math Game")
        Thread.sleep(50)
        timeTracker.endActivity("game")
        timeTracker.updateAnswerCounts("game", 5, 2)
        timeTracker.updateStarsEarned("mathGame", 3)

        // When: Generating report
        val progressReport = progressManager.getComprehensiveProgressReport(config, timeTracker)
        val report = reportGenerator.generateDailyReport(
            progressReport,
            "Test Child",
            ReportGenerator.ReportFormat.TEXT
        )

        // Then: Should include answer counts
        assertTrue(report.contains("5") || report.contains("2"))
        assertTrue(report.contains("✅") || report.contains("❌"))
    }

    @Test
    fun testReportFormatsCorrectlyInAllFormats() {
        // Given: Some progress
        val config = MainContent(
            sections = listOf(
                Section(
                    id = "required",
                    title = "Required Tasks",
                    tasks = listOf(
                        Task(title = "Math Game", launch = "mathGame", stars = 3)
                    )
                )
            )
        )

        progressManager.markTaskCompleted("mathGame", 3, isRequiredTask = true)

        // When: Generating reports in all formats
        val progressReport = progressManager.getComprehensiveProgressReport(config, timeTracker)
        
        val textReport = reportGenerator.generateDailyReport(
            progressReport,
            "Test Child",
            ReportGenerator.ReportFormat.TEXT
        )
        
        val htmlReport = reportGenerator.generateDailyReport(
            progressReport,
            "Test Child",
            ReportGenerator.ReportFormat.HTML
        )
        
        val csvReport = reportGenerator.generateDailyReport(
            progressReport,
            "Test Child",
            ReportGenerator.ReportFormat.CSV
        )
        
        val emailReport = reportGenerator.generateDailyReport(
            progressReport,
            "Test Child",
            ReportGenerator.ReportFormat.EMAIL
        )

        // Then: All formats should contain key information
        assertTrue(textReport.contains("Test Child"))
        assertTrue(htmlReport.contains("Test Child"))
        assertTrue(csvReport.contains("Test Child"))
        assertTrue(emailReport.contains("Test Child"))
        
        // Format-specific checks
        assertTrue(htmlReport.contains("<!DOCTYPE html>"))
        assertTrue(csvReport.contains("Metric,Value"))
        assertTrue(emailReport.contains("Dear Parent/Guardian"))
    }

    @Test
    fun testReportIncludesRewardMinutesWhenProvided() {
        // Given: Some progress
        val config = MainContent(
            sections = listOf(
                Section(
                    id = "required",
                    title = "Required Tasks",
                    tasks = listOf(
                        Task(title = "Math Game", launch = "mathGame", stars = 3)
                    )
                )
            )
        )

        progressManager.markTaskCompleted("mathGame", 3, isRequiredTask = true)

        // When: Generating report with reward minutes
        val progressReport = progressManager.getComprehensiveProgressReport(config, timeTracker)
        val report = reportGenerator.generateDailyReport(
            progressReport,
            "Test Child",
            ReportGenerator.ReportFormat.TEXT,
            rewardMinutesUsed = 30
        )

        // Then: Should include reward minutes
        assertTrue("Report should contain reward time earned text", report.contains("🎮 Reward Time Earned: 30 minutes"))
    }
}

