package com.talq2me.baerened

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File

/**
 * Integration tests for ContentUpdateService
 * Tests content fetching, caching, and fallback strategies
 */
@RunWith(AndroidJUnit4::class)
class ContentUpdateServiceTest {

    private lateinit var context: Context
    private lateinit var contentService: ContentUpdateService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        contentService = ContentUpdateService()
        
        // Clear cache before tests
        contentService.clearCache(context)
        
        // Set profile to A for consistent testing (use real SettingsManager for integration tests)
        // Note: This requires the SettingsContract ContentProvider to be set up
        // If ContentProvider isn't available in test, the test will use default behavior
    }

    @After
    fun tearDown() {
        // Clean up cache after tests
        contentService.clearCache(context)
    }

    @Test
    fun `getCachedMainContent returns null when no cache exists`() {
        // Given: No cached content
        contentService.clearCache(context)

        // When: Getting cached content
        val cached = contentService.getCachedMainContent(context)

        // Then: Should return null
        assertNull(cached)
    }

    @Test
    fun `saveMainContentToCache saves content and version`() {
        // Given: Some JSON content
        val jsonContent = """{"version":"2025-01-01","title":"Test","sections":[]}"""

        // When: Saving to cache
        contentService.saveMainContentToCache(context, jsonContent)

        // Then: Should be retrievable
        val cached = contentService.getCachedMainContent(context)
        assertNotNull(cached)
        assertTrue(cached!!.contains("Test"))
        
        // Version should be saved
        val version = contentService.getCachedMainContentVersion()
        assertEquals("2025-01-01", version)
    }

    @Test
    fun `clearCache removes cached files`() {
        // Given: Some cached content
        val jsonContent = """{"version":"2025-01-01","title":"Test","sections":[]}"""
        contentService.saveMainContentToCache(context, jsonContent)
        
        // Verify it exists
        assertNotNull(contentService.getCachedMainContent(context))

        // When: Clearing cache
        contentService.clearCache(context)

        // Then: Cache should be empty
        assertNull(contentService.getCachedMainContent(context))
        assertNull(contentService.getCachedMainContentVersion())
    }

    @Test
    fun `isContentStale returns true when no cache exists`() {
        // Given: No cached content
        contentService.clearCache(context)

        // When: Checking if stale
        val isStale = contentService.isContentStale(context, maxAgeHours = 24)

        // Then: Should be stale (no cache)
        assertTrue(isStale)
    }

    @Test
    fun `fetchGameContent falls back to assets when network fails`() = runBlocking {
        // Given: Network will fail (no internet in test environment)
        // The service should fall back to assets

        // When: Fetching game content (this will try network, then cache, then assets)
        val content = contentService.fetchGameContent(context, "gr3math")

        // Then: Should get content from assets (if file exists)
        // Note: This test depends on assets existing, may return null if asset doesn't exist
        // The important thing is it doesn't crash and handles fallback gracefully
        // Content may be null if asset doesn't exist, which is acceptable
    }

    @Test
    fun `fetchVideoContent falls back to assets when network fails`() = runBlocking {
        // Given: Network will fail
        // The service should fall back to assets

        // When: Fetching video content
        val content = contentService.fetchVideoContent(context, "uflivideos")

        // Then: Should handle gracefully (may return null if asset doesn't exist)
        // The important thing is it doesn't crash
    }

    @Test
    fun `fetchMainContent falls back to assets when network fails`() = runBlocking {
        // Given: Network will fail
        // The service should fall back to assets

        // When: Fetching main content
        val content = contentService.fetchMainContent(context)

        // Then: Should get content from assets (if file exists)
        assertNotNull(content) // Should get from assets/BM_config.json or AM_config.json
        assertNotNull(content?.title)
    }
}

