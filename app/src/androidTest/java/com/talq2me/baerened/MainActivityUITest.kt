package com.talq2me.baerened

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.anyOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for MainActivity using Espresso
 * Tests basic UI elements and interactions
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUITest {

    private lateinit var activityScenario: ActivityScenario<MainActivity>

    @Before
    fun setup() {
        // Clear any existing data before tests
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DailyProgressManager(context).resetAllProgress()
        TimeTracker(context).clearAllData()
    }

    @After
    fun tearDown() {
        // Clean up activity scenario
        if (::activityScenario.isInitialized) {
            activityScenario.close()
        }
    }

    @Test
    fun `main activity displays title`() {
        // Given: MainActivity is launched
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then: Title should be visible (either "Loading..." or actual title)
        onView(withId(R.id.titleText))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `main activity has progress section`() {
        // Given: MainActivity is launched
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then: Progress layout should exist (may be visible or gone)
        onView(withId(R.id.progressLayout))
            .check(matches(anyOf(withEffectiveVisibility(Visibility.VISIBLE), withEffectiveVisibility(Visibility.GONE))))
        
        // Progress text should exist
        onView(withId(R.id.progressText))
            .check(matches(anyOf(withEffectiveVisibility(Visibility.VISIBLE), withEffectiveVisibility(Visibility.GONE))))
        
        // Progress bar should exist
        onView(withId(R.id.progressBar))
            .check(matches(anyOf(withEffectiveVisibility(Visibility.VISIBLE), withEffectiveVisibility(Visibility.GONE))))
    }

    @Test
    fun `main activity has sections container`() {
        // Given: MainActivity is launched
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then: Sections container should exist
        onView(withId(R.id.sectionsContainer))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `main activity has header layout`() {
        // Given: MainActivity is launched
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then: Header layout should exist
        onView(withId(R.id.headerLayout))
            .check(matches(anyOf(withEffectiveVisibility(Visibility.VISIBLE), withEffectiveVisibility(Visibility.GONE))))
    }

    @Test
    fun `main activity has swipe refresh layout`() {
        // Given: MainActivity is launched
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then: Swipe refresh layout should exist
        onView(withId(R.id.mainSwipeRefresh))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `loading progress bar exists`() {
        // Given: MainActivity is launched
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then: Loading progress bar should exist (may be visible or gone)
        onView(withId(R.id.loadingProgressBar))
            .check(matches(anyOf(withEffectiveVisibility(Visibility.VISIBLE), withEffectiveVisibility(Visibility.GONE))))
    }
}
