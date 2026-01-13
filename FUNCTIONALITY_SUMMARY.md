# BaerenEd Functionality Summary

## Overview
BaerenEd is an educational Android app featuring interactive games, tasks, and activities. It integrates with BaerenLock for reward time management and uses cloud storage for progress synchronization. The app loads content dynamically from GitHub and supports multiple task types including games, videos, web games, and reading activities.

---

## Core Features

### 1. MainActivity
**Purpose**: Central activity that orchestrates the entire app experience

**Features**:
- Loads and displays main content from JSON (via ContentUpdateService)
- Manages UI layout through Layout class
- Handles task launches (games, videos, web games, Chrome pages, reading apps)
- Manages Text-to-Speech (TTS)
- Handles app updates (downloads and installs APK updates)
- Pull-to-refresh for content updates
- Handles activity results from launched tasks
- Manages reward time and launches RewardSelectionActivity
- Special handlers for Google Read Along and Boukili

**Dependencies**:
- Layout (for UI creation)
- ContentUpdateService (for content loading)
- DailyProgressManager (for progress tracking)
- CloudStorageManager (for cloud sync)
- SettingsManager (for settings/profile)
- Multiple activity launchers (GameActivity, WebGameActivity, ChromePageActivity, YouTubePlayerActivity, TrainingMapActivity, BattleHubActivity)

**Key Methods**:
- `handleGameCompletion()` - Processes game completion results
- `handleWebGameCompletion()` - Processes web game completion
- `handleVideoCompletion()` - Processes video completion
- `handleChromePageCompletion()` - Processes Chrome page completion
- `launchGameActivity()` - Launches native game activities
- `launchGoogleReadAlong()` / `launchBoukili()` - Launches reading apps
- `handleHeaderButtonClick()` - Handles header button actions

**Issues**:
- Very large file (~2284 lines) with mixed responsibilities
- Directly accesses multiple managers (high coupling)
- Contains both UI logic and business logic
- Hard to test and modify safely

---

### 2. Layout Class
**Purpose**: Creates and manages UI layouts for MainActivity

**Features**:
- Creates task buttons and sections from content JSON
- Creates header buttons
- Creates progress display
- Creates battle hub integration
- Handles task visibility (showdays/hidedays/disable logic)
- Creates checklist items
- Manages task completion UI updates

**Dependencies**:
- MainActivity (direct reference - tight coupling)
- DailyProgressManager (for progress tracking)
- SettingsManager (for profile)

**Key Methods**:
- `displayContent()` - Main entry point for displaying content
- `setupSections()` - Creates section views with tasks
- `createTaskView()` - Creates individual task buttons
- `handleVideoCompletion()` - Updates UI after video completion
- `handleWebGameCompletion()` - Updates UI after web game completion

**Issues**:
- Directly references MainActivity fields (tight coupling)
- Contains business logic (task visibility, completion handling)
- Very large file (~3044 lines)
- Mixed UI and business logic

---

### 3. DailyProgressManager
**Purpose**: Manages daily progress tracking for tasks, games, and activities

**Features**:
- Tracks completed tasks (per profile)
- Tracks earned stars/coins
- Daily reset functionality
- Progress calculations (completion rates, totals)
- Time tracking integration (TimeTracker)
- Comprehensive progress reports
- Pokemon unlock tracking
- Task completion tracking with names
- Config-based total calculations

**Storage**:
- SharedPreferences: `daily_progress_prefs`
- Keys are profile-prefixed: `"${profile}_completed_tasks"`, etc.

**Dependencies**:
- TimeTracker (for activity session tracking)
- MainContent (for config-based calculations)

**Key Methods**:
- `markTaskCompletedWithName()` - Marks task as completed
- `getCompletedTasksMap()` - Gets completion status map
- `calculateTotalsFromConfig()` - Calculates totals from config
- `getComprehensiveProgressReport()` - Generates detailed progress report
- `shouldResetProgress()` / `resetProgress()` - Daily reset logic

**Issues**:
- Mixed responsibilities (progress + reset logic + reporting)
- Direct SharedPreferences access (could use repository pattern)
- Tightly coupled to TimeTracker

---

### 4. CloudStorageManager
**Purpose**: Manages cloud storage synchronization with Supabase

**Features**:
- Uploads progress to cloud (per profile)
- Downloads progress from cloud
- Syncs required tasks, practice tasks, checklist items
- Syncs game indices, stars, berries, banked minutes
- Syncs Pokemon unlock status
- Syncs app lists (reward apps, blacklisted apps, whitelisted apps)
- Handles cloud enable/disable toggle
- Background sync operations

**Storage**:
- Supabase database (settings table, user_data table)
- Local SharedPreferences: `cloud_storage_prefs` (for sync status)

**Dependencies**:
- DailyProgressManager (reads local progress to upload)
- SettingsManager (for profile)
- MainContent (for config-based data collection)

**Key Methods**:
- `uploadToCloud()` - Uploads all user data to cloud
- `downloadFromCloud()` - Downloads user data from cloud
- `saveIfEnabled()` - Saves to cloud if enabled
- `collectRequiredTasksData()` - Collects task data for upload
- `collectPracticeTasksData()` - Collects practice task data
- `collectChecklistItemsData()` - Collects checklist data

**Issues**:
- Very large file (~1960 lines) - does too much
- Directly accesses DailyProgressManager internals
- Mixed data collection and sync logic
- Tight coupling to MainContent structure
- Complex data transformation logic embedded

---

### 5. SettingsManager (Object)
**Purpose**: Manages app settings (PIN, parent email, profile)

**Features**:
- Profile management (A/B ↔ AM/BM conversion)
- PIN storage and retrieval
- Parent email storage
- Cloud sync for settings
- Local caching
- Preloading on startup

**Storage**:
- Supabase: `settings` table
- Local SharedPreferences: `baeren_shared_settings`

**Dependencies**:
- CloudStorageManager (indirectly - uses Supabase API directly)
- BuildConfig (for Supabase credentials)

**Key Methods**:
- `readProfile()` - Gets current profile
- `setProfile()` - Sets profile
- `getPin()` / `setPin()` - PIN management
- `getParentEmail()` / `setParentEmail()` - Email management
- `preloadSettings()` - Preloads settings on startup

**Issues**:
- Object singleton (hard to test, inject dependencies)
- Mixed local and cloud storage logic
- Direct Supabase API calls (should use CloudStorageManager)

---

### 6. ContentUpdateService
**Purpose**: Downloads and caches content from GitHub

**Features**:
- Fetches main_content.json from GitHub
- Fetches game content files
- Fetches video JSON files
- Caches content locally
- Version checking
- Fallback to cache on network errors
- Asset fallback

**Key Methods**:
- `fetchMainContent()` - Fetches main content JSON
- `getCachedMainContent()` - Gets cached content
- `fetchGameContent()` - Fetches game JSON files
- `fetchVideoContent()` - Fetches video JSON files

**Dependencies**:
- OkHttp (for network requests)
- Local file storage (for caching)

**Issues**:
- Service class but doesn't actually run as Android Service (misnamed)
- Could be a simple utility class or repository

---

### 7. GameActivity
**Purpose**: Handles native Android game activities (quiz, memory, word, math games)

**Features**:
- Multiple game types (quiz, memory, word, math)
- Game engine integration
- Progress tracking (GameProgress)
- Completion reporting back to MainActivity
- Stars/coins tracking

**Dependencies**:
- GameEngine (for game logic)
- GameProgress (for progress tracking)
- DailyProgressManager (via GameProgress)

---

### 8. WebGameActivity
**Purpose**: Displays web-based games in WebView

**Features**:
- WebView for HTML5 games
- JavaScript interface for game completion
- Task completion tracking
- Stars tracking
- Unique task ID handling (for diagramLabeler with diagram parameter)
- Section ID handling

**Dependencies**:
- DailyProgressManager (for completion tracking)
- CloudStorageManager (for cloud sync)

---

### 9. BattleHubActivity
**Purpose**: Displays battle hub with embedded WebView showing Pokemon-style battle interface

**Features**:
- Embedded WebView with battle interface
- Task buttons (required, optional, bonus tasks)
- Task completion handling
- Pokemon unlock management
- Battle completion tracking
- Stars/berries tracking

**Dependencies**:
- DailyProgressManager (for task completion)
- CloudStorageManager (for cloud sync)
- Layout (for task button creation - code duplication!)

**Issues**:
- Duplicates task button creation logic from Layout
- Direct manager access
- Mixed UI and business logic

---

### 10. TrainingMapActivity
**Purpose**: Displays tasks as "gyms" on a map interface

**Features**:
- Map background with task buttons positioned as "gyms"
- Grid-based or random positioning (prevents overlaps)
- Task completion tracking
- Path lines connecting gyms
- Different map types (required, optional, bonus)

**Dependencies**:
- DailyProgressManager (for completion tracking)
- CloudStorageManager (for cloud sync)
- ContentUpdateService (for content loading)

**Issues**:
- Duplicates task launch logic from MainActivity/Layout
- Direct manager access

---

### 11. ChromePageActivity
**Purpose**: Displays Chrome pages as tasks

**Features**:
- WebView for displaying web pages
- Task completion tracking
- Stars tracking

**Dependencies**:
- DailyProgressManager (for completion tracking)

---

### 12. YouTubePlayerActivity
**Purpose**: Plays YouTube videos as tasks

**Features**:
- YouTube video playback
- Playlist support
- Task completion tracking
- Stars tracking

**Dependencies**:
- DailyProgressManager (for completion tracking)
- YouTube Android Player API

---

### 13. FrenchBookReaderActivity
**Purpose**: French book reading interface

**Features**:
- Book reading interface
- Page navigation
- (Implementation details not fully examined)

---

### 14. PokemonActivity
**Purpose**: Pokemon selection/management interface

**Features**:
- Pokemon selection
- Pokemon unlock management
- Pokemon file management

**Dependencies**:
- CloudStorageManager (for Pokemon unlock sync)

---

### 15. TimeTracker
**Purpose**: Tracks time spent in activities

**Features**:
- Activity session tracking
- Time calculations
- Session storage

**Dependencies**:
- SharedPreferences (for session storage)

---

### 16. ReportGenerator
**Purpose**: Generates progress reports (HTML/text)

**Features**:
- HTML report generation
- Text report generation
- Progress analytics
- Task completion summaries

**Dependencies**:
- DailyProgressManager (for progress data)
- TimeTracker (for time data)

---

### 17. RewardSelectionActivity
**Purpose**: Allows selection of reward apps after earning reward time

**Features**:
- Reward app selection UI
- Integrates with BaerenLock reward system

---

### 18. BootReceiver
**Purpose**: Handles boot events (for daily reset or other boot-time operations)

---

## Data Classes

### MainContent (in MainActivity.kt)
- Root content structure
- Contains: title, header, progress, sections, games
- Sections contain tasks and checklist items

### Task (in MainActivity.kt)
- Task definition with: title, launch, stars, url, videoSequence, etc.
- Visibility fields: showdays, hidedays, displayDays, disable

### Game (in MainActivity.kt)
- Game definition with: id, title, type, totalQuestions, etc.

### GameData / GameConfig (separate files)
- Game content structure for native games

---

## Feature Coupling & Dependencies

### High Coupling Areas:

1. **MainActivity ↔ Layout ↔ DailyProgressManager**
   - MainActivity creates Layout, Layout uses DailyProgressManager
   - Layout directly accesses MainActivity fields
   - Tight circular dependency

2. **CloudStorageManager ↔ DailyProgressManager ↔ MainContent**
   - CloudStorageManager reads DailyProgressManager internals
   - CloudStorageManager needs MainContent for data collection
   - Complex data transformation logic

3. **Multiple Activities ↔ DailyProgressManager**
   - GameActivity, WebGameActivity, ChromePageActivity, YouTubePlayerActivity, BattleHubActivity, TrainingMapActivity all directly use DailyProgressManager
   - Each handles completion differently
   - Code duplication

4. **SettingsManager ↔ CloudStorageManager**
   - SettingsManager makes direct Supabase calls instead of using CloudStorageManager
   - Inconsistent sync patterns

5. **Task Launch Logic Duplication**
   - MainActivity has task launch logic
   - Layout has task launch logic
   - BattleHubActivity has task launch logic
   - TrainingMapActivity has task launch logic
   - Each handles task types differently

6. **Task Completion Handling Duplication**
   - Each activity handles completion differently
   - MainActivity has multiple completion handlers
   - Layout has completion handlers
   - Inconsistent patterns

### Well-Isolated:
- GameEngine (standalone game logic)
- TimeTracker (standalone time tracking)
- ReportGenerator (mostly isolated, just reads data)
- ContentUpdateService (mostly isolated, just fetches content)

---

## Critical Issues

### 1. Code Duplication
- Task launch logic duplicated across 4+ places
- Task button creation duplicated (Layout, BattleHubActivity)
- Completion handling duplicated across activities
- Data collection logic duplicated in CloudStorageManager

### 2. Tight Coupling
- Layout directly references MainActivity fields
- CloudStorageManager accesses DailyProgressManager internals
- Activities directly use managers instead of interfaces
- Hard to test and modify

### 3. Large Files
- MainActivity: ~2284 lines
- Layout: ~3044 lines
- CloudStorageManager: ~1960 lines
- DailyProgressManager: ~1194 lines
- Hard to understand and maintain

### 4. Mixed Responsibilities
- MainActivity: UI + business logic + orchestration
- Layout: UI creation + business logic (task visibility, completion)
- CloudStorageManager: Data sync + data collection + transformation
- DailyProgressManager: Progress tracking + reset + reporting

### 5. Inconsistent Patterns
- SettingsManager uses object singleton, others use instances
- Some activities use DailyProgressManager directly, others go through MainActivity
- Inconsistent error handling
- Inconsistent cloud sync patterns

### 6. Hard to Test
- Tight coupling makes unit testing difficult
- Direct SharedPreferences access
- Direct manager instantiation
- No dependency injection

---

## Recommendations for Code Cleanup and Isolation

### Priority 1: Extract Task Launch Logic

**Problem**: Task launch logic is duplicated across MainActivity, Layout, BattleHubActivity, and TrainingMapActivity.

**Solution**: Create `TaskLauncher` class/service
```kotlin
class TaskLauncher(
    private val context: Context,
    private val progressManager: DailyProgressManager,
    private val contentUpdateService: ContentUpdateService
) {
    suspend fun launchTask(task: Task, sectionId: String): TaskLaunchResult
    fun launchGameActivity(...)
    fun launchWebGameActivity(...)
    fun launchVideoActivity(...)
    fun launchChromePageActivity(...)
    fun launchGoogleReadAlong(...)
    fun launchBoukili(...)
}
```

**Benefits**:
- Single source of truth for task launching
- Easier to test
- Consistent behavior across activities
- Easier to add new task types

---

### Priority 2: Extract Task Completion Handler

**Problem**: Task completion is handled differently in multiple places with inconsistent patterns.

**Solution**: Create `TaskCompletionHandler` class
```kotlin
class TaskCompletionHandler(
    private val progressManager: DailyProgressManager,
    private val cloudStorageManager: CloudStorageManager,
    private val settingsManager: SettingsManager
) {
    suspend fun handleCompletion(
        taskId: String,
        taskTitle: String,
        stars: Int,
        sectionId: String,
        config: MainContent?
    ): CompletionResult
}
```

**Benefits**:
- Consistent completion handling
- Centralized cloud sync logic
- Easier to modify completion behavior
- Better error handling

---

### Priority 3: Split CloudStorageManager

**Problem**: CloudStorageManager is too large (1960 lines) and does too much (data collection + sync + transformation).

**Solution**: Split into multiple classes:
1. **CloudSyncService**: Handles actual sync operations (upload/download)
2. **ProgressDataCollector**: Collects progress data from DailyProgressManager
3. **CloudDataMapper**: Maps between local and cloud data structures

```kotlin
class CloudSyncService(private val context: Context) {
    suspend fun uploadUserData(data: CloudUserData): Result<Unit>
    suspend fun downloadUserData(profile: String): Result<CloudUserData>
}

class ProgressDataCollector(
    private val progressManager: DailyProgressManager,
    private val config: MainContent
) {
    fun collectRequiredTasksData(): Map<String, TaskProgress>
    fun collectPracticeTasksData(): Map<String, PracticeProgress>
    fun collectChecklistItemsData(): Map<String, ChecklistItemProgress>
}

class CloudDataMapper {
    fun toCloudUserData(progressData: ProgressData, profile: String): CloudUserData
    fun fromCloudUserData(data: CloudUserData): ProgressData
}
```

**Benefits**:
- Smaller, focused classes
- Easier to test
- Clear separation of concerns
- Can reuse data collection logic

---

### Priority 4: Extract UI Creation from Layout

**Problem**: Layout class is huge (3044 lines) and mixes UI creation with business logic.

**Solution**: Split into multiple classes:
1. **TaskViewFactory**: Creates task button views
2. **SectionViewFactory**: Creates section views
3. **ProgressViewFactory**: Creates progress views
4. **LayoutCoordinator**: Orchestrates view creation

```kotlin
class TaskViewFactory(private val context: Context) {
    fun createTaskView(task: Task, sectionId: String, isCompleted: Boolean): View
}

class SectionViewFactory(
    private val context: Context,
    private val taskViewFactory: TaskViewFactory
) {
    fun createSectionView(section: Section, completedTasks: Map<String, Boolean>): View
}

class LayoutCoordinator(
    private val activity: MainActivity,
    private val sectionViewFactory: SectionViewFactory,
    private val progressViewFactory: ProgressViewFactory
) {
    fun displayContent(content: MainContent)
}
```

**Benefits**:
- Smaller, focused classes
- Easier to test individual components
- Can reuse view factories in other activities (BattleHubActivity, etc.)
- Clear separation of UI and business logic

---

### Priority 5: Create Progress Repository

**Problem**: DailyProgressManager directly accesses SharedPreferences, making it hard to test and modify.

**Solution**: Create repository pattern
```kotlin
interface ProgressRepository {
    fun getCompletedTasks(profile: String): Map<String, Boolean>
    fun markTaskCompleted(profile: String, taskId: String, taskName: String, stars: Int)
    fun getEarnedStars(profile: String): Int
    // ... other progress operations
}

class SharedPreferencesProgressRepository(private val context: Context) : ProgressRepository {
    // Implementation using SharedPreferences
}

class DailyProgressManager(private val repository: ProgressRepository) {
    // Business logic only, no direct SharedPreferences access
}
```

**Benefits**:
- Easier to test (can mock repository)
- Can swap storage implementations
- Clear separation of storage and business logic

---

### Priority 6: Extract Task Visibility Logic

**Problem**: Task visibility logic (showdays/hidedays/disable) is scattered across Layout and DailyProgressManager.

**Solution**: Create `TaskVisibilityChecker` class
```kotlin
class TaskVisibilityChecker {
    fun isTaskVisible(
        task: Task,
        currentDate: Calendar = Calendar.getInstance()
    ): Boolean {
        // Centralized visibility logic
    }
}
```

**Benefits**:
- Single source of truth for visibility logic
- Easier to test
- Consistent behavior
- Can be reused everywhere

---

### Priority 7: Create Manager Interfaces

**Problem**: Activities directly instantiate managers, making testing difficult.

**Solution**: Create interfaces for managers
```kotlin
interface IProgressManager {
    fun markTaskCompleted(...)
    fun getCompletedTasks(...)
    // ... other methods
}

interface ICloudStorageManager {
    suspend fun uploadToCloud(...)
    suspend fun downloadFromCloud(...)
    // ... other methods
}

// Activities use interfaces, not concrete classes
class GameActivity(
    private val progressManager: IProgressManager
)
```

**Benefits**:
- Easier to test (can mock interfaces)
- Can swap implementations
- Better dependency injection support

---

### Priority 8: Refactor SettingsManager

**Problem**: SettingsManager is an object singleton, making testing difficult. Also makes direct Supabase calls instead of using CloudStorageManager.

**Solution**: 
1. Convert to class with interface
2. Use CloudStorageManager for cloud operations
3. Inject dependencies

```kotlin
interface ISettingsManager {
    fun getProfile(): String
    fun setProfile(profile: String)
    // ... other methods
}

class SettingsManager(
    private val context: Context,
    private val cloudStorageManager: CloudStorageManager
) : ISettingsManager {
    // Implementation
}
```

**Benefits**:
- Easier to test
- Consistent cloud sync pattern
- Can inject dependencies

---

### Priority 9: Extract Content Loading Logic

**Problem**: ContentUpdateService is misnamed (not a Service) and MainActivity has content loading logic.

**Solution**: Create `ContentRepository`
```kotlin
interface ContentRepository {
    suspend fun getMainContent(): Result<MainContent>
    suspend fun getGameContent(gameId: String): Result<String>
    suspend fun getVideoContent(videoFile: String): Result<String>
}

class GitHubContentRepository(
    private val contentUpdateService: ContentUpdateService
) : ContentRepository {
    // Implementation
}
```

**Benefits**:
- Clear naming
- Easier to test
- Can swap content sources
- Separates content fetching from caching

---

### Priority 10: Create Dependency Injection Setup

**Problem**: Classes directly instantiate dependencies, making testing and modification difficult.

**Solution**: Use simple dependency injection (manual DI or lightweight library like Koin)
```kotlin
// Application class or DI container
object AppContainer {
    val progressRepository: ProgressRepository = SharedPreferencesProgressRepository(context)
    val progressManager: DailyProgressManager = DailyProgressManager(progressRepository)
    val cloudStorageManager: CloudStorageManager = CloudStorageManager(context)
    val taskLauncher: TaskLauncher = TaskLauncher(context, progressManager, ...)
    // ... other dependencies
}

// Activities get dependencies from container
class GameActivity : AppCompatActivity() {
    private val progressManager = AppContainer.progressManager
}
```

**Benefits**:
- Easier to test (can swap implementations)
- Centralized dependency management
- Easier to modify dependencies
- Better lifecycle management

---

## Implementation Strategy

### Phase 1: Extract and Isolate (Low Risk)
1. Create TaskVisibilityChecker
2. Extract TaskLaunch logic to TaskLauncher
3. Extract TaskCompletionHandler
4. Create ProgressRepository interface

### Phase 2: Split Large Classes (Medium Risk)
1. Split CloudStorageManager
2. Split Layout class
3. Refactor SettingsManager

### Phase 3: Refactor Activities (Higher Risk)
1. Update activities to use new interfaces
2. Remove duplicated code
3. Add dependency injection

### Phase 4: Testing and Cleanup (Ongoing)
1. Add unit tests for isolated components
2. Integration tests for critical paths
3. Remove unused code
4. Document interfaces and patterns

---

## Benefits of Refactoring

1. **Easier to Modify**: Changes in one area don't break others
2. **Easier to Test**: Isolated components can be unit tested
3. **Easier to Understand**: Smaller, focused classes
4. **Less Code Duplication**: Single source of truth for common logic
5. **Better Error Handling**: Centralized error handling
6. **Consistent Patterns**: Same patterns throughout codebase
7. **Easier to Add Features**: Clear extension points
8. **Better Performance**: Can optimize isolated components

---

## Summary

BaerenEd is a feature-rich educational app with complex interactions between components. The main issues are:

1. **Code Duplication**: Task launch/completion logic duplicated across activities
2. **Tight Coupling**: Classes directly reference each other, making changes risky
3. **Large Files**: Several files are too large to easily understand/maintain
4. **Mixed Responsibilities**: Classes do too many things
5. **Inconsistent Patterns**: Different approaches to similar problems

The recommended refactoring focuses on:
- Extracting common logic (task launch, completion, visibility)
- Creating interfaces for better testability
- Splitting large classes into focused components
- Using dependency injection for flexibility
- Creating clear separation of concerns

This will make the codebase more maintainable and reduce the risk of breaking things when making changes.
