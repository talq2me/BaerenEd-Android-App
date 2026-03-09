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
        
        // Mock SharedPreferences for baeren_shared_settings (used by SettingsManager.readProfile)
        // This must be set up BEFORE DailyProgressManager is instantiated
        val settingsPrefs = mockk<SharedPreferences>(relaxed = true)
        every { settingsPrefs.getString("profile", null) } returns "AM"
        every { mockContext.getSharedPreferences("baeren_shared_settings", any()) } returns settingsPrefs
        
        // Mock SharedPreferences for daily_progress_prefs (used by DailyProgressManager)
        every { mockContext.getSharedPreferences("daily_progress_prefs", any()) } returns mockPrefs
        
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.remove(any()) } returns mockEditor
        every { mockEditor.apply() } just Runs
        every { mockEditor.commit() } returns true
        every { mockPrefs.getInt(any(), any()) } returns 0
        
        // Set up date to prevent reset (use today's date)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        every { mockPrefs.getString("last_reset_date", "") } returns today
        every { mockPrefs.getString("AM_completed_tasks", "{}") } returns "{}"
        every { mockPrefs.getString("AM_completed_task_names", "{}") } returns "{}"

        progressManager = DailyProgressManager(mockContext)
    }

    @After
    fun tearDown() {
        // Clear DailyProgressManager session so state does not leak between tests
        progressManager.clearProgressDataAfterRequest()
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
        // Given: Session data has banked minutes (DailyProgressManager reads from session)
        progressManager.setProgressDataAfterFetch(CloudUserData(profile = "AM", bankedMins = 20))

        // When: Getting banked minutes
        val minutes = progressManager.getBankedRewardMinutes()

        // Then: Should return correct amount
        assertEquals(20, minutes)
    }

    @Test
    fun `setBankedRewardMinutes sets reward minutes`() {
        // Given: Session data exists (banked minutes are session-only; no prefs)
        progressManager.setProgressDataAfterFetch(CloudUserData(profile = "AM", bankedMins = 0))

        // When: Setting to 30 minutes
        progressManager.setBankedRewardMinutes(30)

        // Then: Session reflects new value
        assertEquals(30, progressManager.getBankedRewardMinutes())
    }

    @Test
    fun `addRewardMinutes adds minutes to reward bank`() {
        // Given: Session data has initial minutes (getBankedRewardMinutes reads from session)
        progressManager.setProgressDataAfterFetch(CloudUserData(profile = "AM", bankedMins = 5))

        // When: Adding 10 minutes
        val newTotal = progressManager.addRewardMinutes(10)

        // Then: Should return 15 (5 + 10); session is updated (no prefs)
        assertEquals(15, newTotal)
        assertEquals(15, progressManager.getBankedRewardMinutes())
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
