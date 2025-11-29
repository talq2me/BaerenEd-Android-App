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
    fun `complete task flow marks task and tracks time`() {
        // Given: A task to complete
        val taskId = "testGame"
        val taskName = "Test Game"
        val stars = 3

        // When: Starting activity, completing task, and ending activity
        timeTracker.startActivity(taskId, "game", taskName)
        Thread.sleep(100) // Simulate some time passing
        val earnedStars = progressManager.markTaskCompletedWithName(
            taskId, taskName, stars, isRequiredTask = true
        )
        val session = timeTracker.endActivity("game")

        // Then: Task should be completed and time tracked
        assertEquals(stars, earnedStars)
        assertTrue(progressManager.isTaskCompleted(taskId))
        assertNotNull(session)
        assertTrue(session!!.durationSeconds > 0)
    }

    @Test
    fun `required task can only be completed once per day`() {
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
    fun `optional task can be completed multiple times`() {
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
    fun `task completion updates progress correctly`() {
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
    fun `reward minutes accumulate correctly`() {
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
    fun `time tracking sessions are recorded correctly`() {
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
    fun `unique task IDs work correctly for different sections`() {
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

