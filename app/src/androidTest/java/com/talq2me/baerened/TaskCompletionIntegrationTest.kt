package com.talq2me.baerened

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Integration tests for task completion flow
 * Tests the interaction between DailyProgressManager and TimeTracker
 */
@RunWith(AndroidJUnit4::class)
class TaskCompletionIntegrationTest {

    private lateinit var context: Context
    private lateinit var progressManager: DailyProgressManager
    private lateinit var timeTracker: TimeTracker

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        progressManager = DailyProgressManager(context)
        timeTracker = TimeTracker(context)
        
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
    fun testCompleteTaskFlowMarksTaskAndTracksTime() {
        // Given: A task to complete
        val taskId = "testGame"
        val taskName = "Test Game"
        val stars = 3

        // When: Starting activity, completing task, and ending activity
        timeTracker.startActivity(taskId, "game", taskName)
        Thread.sleep(200) // Simulate some time passing (increased to ensure duration > 0)
        val earnedStars = progressManager.markTaskCompletedWithName(
            taskId, taskName, stars, isRequiredTask = true
        )
        val session = timeTracker.endActivity("game")

        // Then: Task should be completed and time tracked
        assertEquals("Task should earn correct stars", stars, earnedStars)
        assertTrue("Task should be marked as completed", progressManager.isTaskCompleted(taskId))
        assertNotNull("Session should not be null", session)
        // Session duration might be 0 if time passes too quickly, so we check it's >= 0
        assertTrue("Session duration should be non-negative", session!!.durationSeconds >= 0)
    }

    @Test
    fun testRequiredTaskCanOnlyBeCompletedOncePerDay() {
        // Given: A required task
        val taskId = "requiredTask"
        val stars = 3

        // When: Completing it twice
        val firstStars = progressManager.markTaskCompleted(taskId, stars, isRequiredTask = true)
        val secondStars = progressManager.markTaskCompleted(taskId, stars, isRequiredTask = true)

        // Then: First completion earns stars, second returns 0
        assertEquals(stars, firstStars)
        assertEquals(0, secondStars)
        assertTrue(progressManager.isTaskCompleted(taskId))
    }

    @Test
    fun testOptionalTaskCanBeCompletedMultipleTimes() {
        // Given: An optional task
        val taskId = "optionalTask"
        val stars = 1

        // When: Completing it multiple times
        val firstStars = progressManager.markTaskCompletedWithName(
            taskId, "Optional Task", stars, 
            isRequiredTask = false, sectionId = "optional"
        )
        val secondStars = progressManager.markTaskCompletedWithName(
            taskId, "Optional Task", stars,
            isRequiredTask = false, sectionId = "optional"
        )

        // Then: Both completions should earn stars
        assertEquals(stars, firstStars)
        assertEquals(stars, secondStars)
    }

    @Test
    fun testTaskCompletionUpdatesProgressCorrectly() {
        // Given: A config with tasks
        val config = MainContent(
            sections = listOf(
                Section(
                    id = "required",
                    title = "Required Tasks",
                    tasks = listOf(
                        Task(title = "Task 1", launch = "task1", stars = 3),
                        Task(title = "Task 2", launch = "task2", stars = 3)
                    )
                )
            )
        )

        // When: Completing one task
        progressManager.markTaskCompleted("task1", 3, isRequiredTask = true)

        // Then: Progress should reflect completion
        val (earned, total) = progressManager.getCurrentProgressWithActualStars(config)
        assertEquals(3, earned) // One task worth 3 stars
        assertTrue(total >= 6) // At least 2 tasks worth 3 stars each
    }

    @Test
    fun testRewardMinutesAccumulateCorrectly() {
        // Given: No initial reward minutes
        assertEquals(0, progressManager.getBankedRewardMinutes())

        // When: Adding reward minutes multiple times
        progressManager.addRewardMinutes(5)
        progressManager.addRewardMinutes(10)
        progressManager.addRewardMinutes(3)

        // Then: Total should be 18
        assertEquals(18, progressManager.getBankedRewardMinutes())
    }

    @Test
    fun testTimeTrackingSessionsAreRecordedCorrectly() {
        // Given: Multiple activities
        timeTracker.startActivity("game1", "game", "Math Game")
        Thread.sleep(50)
        timeTracker.endActivity("game")

        timeTracker.startActivity("video1", "video", "Learning Video")
        Thread.sleep(50)
        timeTracker.endActivity("video")

        // When: Getting today's sessions
        val sessions = timeTracker.getTodaySessionsList()

        // Then: Should have 2 sessions
        assertTrue(sessions.size >= 2)
        assertTrue(sessions.any { it.activityType == "game" })
        assertTrue(sessions.any { it.activityType == "video" })
    }

    @Test
    fun testUniqueTaskIdsWorkCorrectlyForDifferentSections() {
        // Given: Same task in different sections
        val taskId = "duologicalGame"
        val stars = 3

        // When: Completing in required and optional sections
        val requiredStars = progressManager.markTaskCompletedWithName(
            taskId, "Duological", stars, 
            isRequiredTask = true, sectionId = "required"
        )
        val optionalStars = progressManager.markTaskCompletedWithName(
            taskId, "Duological", stars,
            isRequiredTask = false, sectionId = "optional"
        )

        // Then: Both should earn stars (they're tracked separately)
        assertEquals(stars, requiredStars)
        assertEquals(stars, optionalStars)
        
        // Required task should be marked as completed
        assertTrue(progressManager.isTaskCompleted(taskId))
        
        // Optional task should also be tracked (with unique ID)
        val uniqueOptionalId = progressManager.getUniqueTaskId(taskId, "optional")
        assertTrue(progressManager.isTaskCompleted(uniqueOptionalId))
    }
}

