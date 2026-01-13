# Layout Class Refactoring Plan

## Current State
- **File**: `Layout.kt`
- **Size**: ~3044 lines
- **Dependencies**: Tightly coupled to MainActivity
- **Complexity**: High - handles UI creation, task launching, progress updates, etc.

## Analysis

### Main Responsibilities in Layout Class:
1. **Progress Display** - `setupProgress()`, `updateProgressDisplay()`, progress buttons
2. **Section View Creation** - `createSectionView()`, `setupSections()`
3. **Task View Creation** - `createTaskView()`, task button creation
4. **Checklist View Creation** - `createChecklistItemView()`
5. **Task Launching** - `launchTask()`, `handleVideoSequenceTask()`, `playYouTubePlaylist()`, etc.
6. **Header Buttons** - `setupHeaderButtons()`, `setupDefaultHeaderButtons()`
7. **Battle Hub** - `setupBattleHub()`, `refreshBattleHub()`
8. **Profile Selection** - `displayProfileSelection()`
9. **Content Display** - `displayContent()` - main orchestration

### Key Dependencies:
- MainActivity (for UI elements, launchers, methods)
- DailyProgressManager
- TaskVisibilityChecker (should use this, currently has duplicate `isTaskVisible`)
- TaskLauncher (should use this, currently has duplicate launch logic)
- ContentUpdateService
- SettingsManager

## Recommended Refactoring Approach

### Phase 1: Use Existing Helper Classes ✅ (Quick Win)
1. **Update Layout to use TaskLauncher**
   - Replace inline task launch logic with TaskLauncher
   - Requires passing ActivityResultLaunchers from MainActivity
   - Reduces ~200+ lines of duplicate code

2. **Update Layout to use TaskVisibilityChecker**
   - Replace `isTaskVisible()` method with TaskVisibilityChecker
   - Reduces ~100 lines of duplicate code

### Phase 2: Extract View Factories (Incremental)
Extract in order of complexity:

1. **IconConfigLoader** (Simplest)
   - Extract `loadIconConfig()` to a separate utility class
   - ~15 lines

2. **ProgressViewFactory** (Self-contained)
   - Extract progress display logic
   - Methods: `setupProgress()`, `updateProgressDisplay()`, button management
   - Dependencies: DailyProgressManager, MainActivity (for UI elements)
   - ~200-300 lines

3. **ChecklistItemViewFactory** (Simple)
   - Extract `createChecklistItemView()`
   - Dependencies: MainActivity, DailyProgressManager
   - ~150 lines

4. **TaskViewFactory** (Complex - depends on TaskLauncher)
   - Extract `createTaskView()`
   - Requires TaskLauncher integration first
   - Dependencies: MainActivity, TaskLauncher, IconConfigLoader
   - ~200 lines

5. **SectionViewFactory** (Most Complex)
   - Extract `createSectionView()`, `setupSections()`
   - Uses TaskViewFactory and ChecklistItemViewFactory
   - Dependencies: MainActivity, TaskViewFactory, ChecklistItemViewFactory
   - ~300-400 lines

6. **LayoutCoordinator** (Orchestration)
   - Main `displayContent()` method
   - Coordinates all factories
   - Dependencies: All factories, MainActivity
   - Remaining ~500 lines

## Challenges

1. **Tight Coupling to MainActivity**
   - Layout accesses MainActivity properties directly (headerLayout, titleText, etc.)
   - Layout calls MainActivity methods (getCurrentMainContent(), launchGoogleReadAlong(), etc.)
   - Solution: Pass dependencies via constructor, or create interface for MainActivity access

2. **ActivityResultLaunchers**
   - TaskLauncher needs ActivityResultLaunchers
   - Currently in MainActivity, need to pass to Layout then to TaskLauncher
   - Solution: Pass launchers through constructor chain

3. **State Management**
   - Layout maintains state (pendingSectionsSetup, iconConfig)
   - Need to decide where this state lives
   - Solution: Keep in LayoutCoordinator or pass as dependencies

4. **Testing**
   - Each factory will need its dependencies mocked
   - Complex setup required
   - Solution: Create interfaces for dependencies

## Recommended Next Steps

### Option 1: Incremental (Recommended)
1. Start with Phase 1 (use TaskLauncher and TaskVisibilityChecker) - reduces duplication immediately
2. Extract IconConfigLoader (simple, quick win)
3. Extract ProgressViewFactory (self-contained)
4. Continue incrementally with testing at each step

### Option 2: Full Refactor (High Risk)
- Extract all factories at once
- Requires extensive testing
- High risk of breaking functionality
- Not recommended without thorough testing infrastructure

## Estimated Impact

After full refactoring:
- **Layout.kt**: ~3044 lines → ~500-800 lines (LayoutCoordinator)
- **New Files**: 5-6 factory classes (~200-400 lines each)
- **Total Lines**: Similar, but better organized
- **Benefits**: Better testability, reusability, maintainability

## Current Status

- ✅ TaskLauncher exists and can be used
- ✅ TaskVisibilityChecker exists and can be used  
- ⏳ Layout class still uses duplicate code
- ⏳ View factories not yet created

## Recommendation

Given the complexity and tight coupling, recommend:
1. **First**: Update Layout to use TaskLauncher and TaskVisibilityChecker (Phase 1)
2. **Then**: Extract factories incrementally with testing at each step
3. **Or**: Leave Layout as-is for now and focus on other improvements

The refactoring completed so far (CloudStorageManager split, helper classes) provides significant value. Layout split can be done incrementally over time.
