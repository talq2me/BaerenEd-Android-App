package com.talq2me.baerened

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Unit tests for TimeTracker
 * Tests time tracking calculations and data structures.
 * 
 * Note: Complex state-dependent tests are better tested as integration tests.
 * See TaskCompletionIntegrationTest for comprehensive testing.
 */
class TimeTrackerTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var timeTracker: TimeTracker

    @Before
    fun setup() {
        // Mock Android Log class
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        
        mockContext = mockk<Context>(relaxed = true)
        mockPrefs = mockk<SharedPreferences>(relaxed = true)
        mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)

        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just Runs
        
        // Set up date to prevent reset (use today's date)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        every { mockPrefs.getString("time_tracker_last_reset", "") } returns today
        every { mockPrefs.getString("daily_sessions", "[]") } returns "[]"
        every { mockPrefs.getString("session_start_times", "{}") } returns "{}"

        timeTracker = TimeTracker(mockContext)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `ActivitySession durationMinutes calculates correctly`() {
        // Given: A session with 120 seconds
        val session = TimeTracker.ActivitySession(
            activityId = "test",
            activityType = "game",
            activityName = "Test",
            startTime = 0,
            endTime = 120000,
            durationSeconds = 120,
            date = "2025-01-01",
            completed = true
        )

        // When: Getting duration in minutes
        val minutes = session.durationMinutes

        // Then: Should be 2.0 minutes
        assertEquals(2.0, minutes, 0.01)
    }

    @Test
    fun `ActivitySession formattedDuration formats correctly`() {
        // Given: A session with 125 seconds
        val session = TimeTracker.ActivitySession(
            activityId = "test",
            activityType = "game",
            activityName = "Test",
            startTime = 0,
            endTime = 125000,
            durationSeconds = 125,
            date = "2025-01-01",
            completed = true
        )

        // When: Getting formatted duration
        val formatted = session.formattedDuration

        // Then: Should be "2m5s"
        assertEquals("2m5s", formatted)
    }
}
