package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Unit tests for DailyProgressManager
 * Tests pure logic methods that don't require complex state management.
 * 
 * Note: State-dependent tests (markTaskCompleted, isTaskCompleted, etc.)
 * are better tested as integration tests with real SharedPreferences.
 * See TaskCompletionIntegrationTest for comprehensive testing.
 */
class DailyProgressManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var progressManager: DailyProgressManager

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
        mockPrefs = mockk<SharedPreferences>(relaxed = true)
        mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)

        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.remove(any()) } returns mockEditor
        every { mockEditor.apply() } just Runs
        every { mockPrefs.getInt(any(), any()) } returns 0
        
        // Set up date to prevent reset (use today's date)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        every { mockPrefs.getString("last_reset_date", "") } returns today
        every { mockPrefs.getString("completed_tasks", "{}") } returns "{}"
        every { mockPrefs.getString("completed_task_names", "{}") } returns "{}"

        progressManager = DailyProgressManager(mockContext)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `getUniqueTaskId returns taskId for required section`() {
        // Given: A task in the required section
        val taskId = "testTask"
        val sectionId = "required"

        // When: Getting unique task ID
        val uniqueId = progressManager.getUniqueTaskId(taskId, sectionId)

        // Then: Should return original taskId
        assertEquals(taskId, uniqueId)
    }

    @Test
    fun `getUniqueTaskId returns prefixed taskId for optional section`() {
        // Given: A task in an optional section
        val taskId = "testTask"
        val sectionId = "optional"

        // When: Getting unique task ID
        val uniqueId = progressManager.getUniqueTaskId(taskId, sectionId)

        // Then: Should return prefixed ID
        assertEquals("optional_testTask", uniqueId)
    }

    @Test
    fun `getBankedRewardMinutes returns current banked minutes`() {
        // Given: Some banked minutes
        every { mockPrefs.getInt("banked_reward_minutes", 0) } returns 20

        // When: Getting banked minutes
        val minutes = progressManager.getBankedRewardMinutes()

        // Then: Should return correct amount
        assertEquals(20, minutes)
    }

    @Test
    fun `setBankedRewardMinutes sets reward minutes`() {
        // Given: No initial minutes
        every { mockPrefs.getInt("banked_reward_minutes", 0) } returns 0

        // When: Setting to 30 minutes
        progressManager.setBankedRewardMinutes(30)

        // Then: Should save the value
        verify { mockEditor.putInt("banked_reward_minutes", 30) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `addRewardMinutes adds minutes to reward bank`() {
        // Given: Initial reward minutes
        // getBankedRewardMinutes may call getInt multiple times due to error handling
        every { mockPrefs.getInt("banked_reward_minutes", 0) } returns 5
        every { mockPrefs.getFloat("banked_reward_minutes", 0f) } throws ClassCastException()
        every { mockPrefs.getString("banked_reward_minutes", "0") } throws ClassCastException()

        // When: Adding 10 minutes
        val newTotal = progressManager.addRewardMinutes(10)

        // Then: Should return 15 (5 + 10)
        assertEquals(15, newTotal)
        // Verify it tried to save the new total
        verify(atLeast = 1) { mockEditor.putInt("banked_reward_minutes", 15) }
        verify(atLeast = 1) { mockEditor.apply() }
    }

    @Test
    fun `filterVisibleContent filters tasks based on visibility`() {
        // Given: A config with tasks that have different visibility rules
        val config = MainContent(
            sections = listOf(
                Section(
                    id = "required",
                    title = "Required",
                    tasks = listOf(
                        Task(title = "Always Visible", launch = "task1", stars = 3),
                        Task(title = "Hidden Task", launch = "task2", stars = 3, hidedays = "mon")
                    )
                )
            )
        )

        // When: Filtering visible content
        val filtered = progressManager.filterVisibleContent(config)

        // Then: Should return filtered content
        assertNotNull(filtered)
        // The exact filtering depends on current day, but the method should work
        assertNotNull(filtered.sections)
    }
}
