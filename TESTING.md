# Automated Testing Guide

This document explains how to run the automated tests for BaerenEd.

## Test Structure

The test suite is organized into three categories:

### 1. Unit Tests (`app/src/test/`)
Fast, isolated tests that run on the JVM without an Android device/emulator.

**Files:**
- `DailyProgressManagerTest.kt` - Tests progress tracking, task completion, daily resets
- `TimeTrackerTest.kt` - Tests time tracking and session management
- `GameEngineTest.kt` - Tests game logic, answer submission, progress tracking
- `ReportGeneratorTest.kt` - Tests report generation in all formats

**What they test:**
- Business logic in isolation
- Data persistence (using mocked SharedPreferences)
- Task completion rules (required vs optional)
- Reward minute calculations
- Time tracking calculations

### 2. Integration Tests (`app/src/androidTest/`)
Tests that run on Android devices/emulators and test component interactions.

**Files:**
- `TaskCompletionIntegrationTest.kt` - Tests the full task completion flow
- `ContentUpdateServiceTest.kt` - Tests content fetching and caching
- `SettingsManagerTest.kt` - Tests settings read/write operations
- `ReportGenerationIntegrationTest.kt` - Tests complete report generation flow

**What they test:**
- Interaction between DailyProgressManager and TimeTracker
- End-to-end task completion workflows
- Progress updates
- Reward accumulation

### 3. UI Tests (`app/src/androidTest/`)
Tests that verify UI elements and user interactions using Espresso.

**Files:**
- `MainActivityUITest.kt` - Tests MainActivity UI elements

**What they test:**
- UI elements are displayed correctly
- Basic screen structure
- View visibility

## Running Tests

### Run All Tests

```bash
# Run all unit tests (fast, no device needed)
./gradlew test

# Run all Android tests (requires device/emulator)
./gradlew connectedAndroidTest

# Run all tests
./gradlew test connectedAndroidTest
```

### Run Specific Test Classes

```bash
# Run a specific unit test class
./gradlew test --tests "com.talq2me.baerened.DailyProgressManagerTest"

# Run a specific Android test class
./gradlew connectedAndroidTest --tests "com.talq2me.baerened.TaskCompletionIntegrationTest"
```

### Run Tests from Android Studio

1. **Unit Tests**: Right-click on `app/src/test` → Run Tests
2. **Android Tests**: Right-click on `app/src/androidTest` → Run Tests
3. **Single Test**: Click the green arrow next to a test method

## Test Coverage

### Currently Tested

✅ **DailyProgressManager** (Unit Tests)
- Task completion (required and optional)
- Progress tracking
- Reward minute management
- Daily reset logic
- Task visibility filtering

✅ **TimeTracker** (Unit Tests)
- Activity session tracking
- Time duration calculations
- Session management
- Daily reset

✅ **GameEngine** (Unit Tests)
- Game logic and question handling
- Answer submission (correct/incorrect)
- Progress tracking within games
- Game completion detection
- Multiple choice handling

✅ **ReportGenerator** (Unit Tests)
- Text report generation
- HTML report generation
- CSV report generation
- Email report generation
- Task details formatting
- Answer info formatting

✅ **ContentUpdateService** (Integration Tests)
- Content caching
- Cache retrieval
- Cache clearing
- Content staleness checking
- Fallback to assets

✅ **SettingsManager** (Integration Tests)
- Profile read/write
- PIN read/write
- Email read/write
- Settings caching

✅ **Integration Tests**
- Complete task completion flow
- Progress updates
- Reward accumulation
- Report generation from progress data
- Time tracking integration
- Reward time Intent passing to BaerenLock

✅ **UI Tests**
- MainActivity basic structure
- View visibility

### HTML/Web Game Tests

✅ **Times Tables Game** (Manual/HTML Test)
- Table parameter locking (`?table=8` locks to table 8)
- Random table selection (`?table=random` picks random and locks)
- Selector button visibility (hidden when locked)
- Table change prevention when locked
- **Fixed Bug**: When `table=` parameter is provided, tiles now render correctly (was showing 0 tiles before)

**Test File:** `https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/html/timesTables_test.html`
- Open in browser to run interactive tests
- Tests verify selector button is hidden when locked
- Tests verify table cannot be changed when parameter is provided
- Tests verify all 10 tiles render when `table=` parameter is provided

### Not Yet Tested (Future Work)

- GameActivity UI interactions
- Video/Web game activities
- Profile switching UI
- Settings dialogs UI
- Complex UI interactions
- Network error handling edge cases

## Adding New Tests

### Unit Test Example

```kotlin
@Test
fun `my new feature test`() {
    // Given: Setup test data
    val manager = DailyProgressManager(mockContext)
    
    // When: Execute the feature
    val result = manager.someMethod()
    
    // Then: Verify the result
    assertEquals(expected, result)
}
```

### Integration Test Example

```kotlin
@Test
fun `test feature integration`() {
    val context = ApplicationProvider.getApplicationContext()
    val manager = DailyProgressManager(context)
    
    // Test with real Android components
    manager.markTaskCompleted("test", 3)
    assertTrue(manager.isTaskCompleted("test"))
}
```

### UI Test Example

```kotlin
@Test
fun `test button click`() {
    activityScenario = ActivityScenario.launch(MainActivity::class.java)
    
    onView(withId(R.id.myButton))
        .perform(click())
    
    onView(withText("Expected Result"))
        .check(matches(isDisplayed()))
}
```

## Continuous Integration

To run tests on each build, add to your CI/CD pipeline:

```yaml
# Example GitHub Actions
- name: Run Unit Tests
  run: ./gradlew test

- name: Run Android Tests
  run: ./gradlew connectedAndroidTest  #requires emulator
```

## Troubleshooting

### Tests fail with "No tests found"
- Ensure test files are in `app/src/test/` (unit) or `app/src/androidTest/` (Android)
- Check that test methods are annotated with `@Test`
- Verify test class is in the correct package

### Android tests require device
- Start an emulator: `Tools > Device Manager` in Android Studio
- Or connect a physical device with USB debugging enabled

### Mockk errors in unit tests
- Ensure `mockk` dependency is in `build.gradle.kts`
- Check that mocks are properly set up in `@Before` methods

## Best Practices

1. **Keep tests fast** - Unit tests should run in milliseconds
2. **Test behavior, not implementation** - Focus on what the code does, not how
3. **Use descriptive test names** - `test feature does expected thing` format
4. **Isolate tests** - Each test should be independent
5. **Clean up** - Use `@After` methods to reset state

## Dependencies

The test suite uses:
- **JUnit 4** - Test framework
- **MockK** - Mocking library for unit tests
- **Espresso** - UI testing framework
- **AndroidX Test** - Android testing utilities

All dependencies are already configured in `app/build.gradle.kts`.

