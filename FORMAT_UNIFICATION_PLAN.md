# Format Unification Plan: Cloud Format Everywhere

## Goal
Use the same format everywhere: `Map<String, TaskProgress>` with task **names** (not IDs) as keys.

## Current State

### Local Storage (SharedPreferences)
- Key: `{profile}_completed_tasks`
- Type: `Map<String, Boolean>` (task IDs → boolean)
- Key: `completed_task_names`  
- Type: `Map<String, String>` (task ID → task name)

### Cloud Storage
- Key: `required_tasks` in CloudUserData
- Type: `Map<String, TaskProgress>` (task names → TaskProgress)

## Target State

### Local Storage (SharedPreferences)
- Key: `{profile}_required_tasks`
- Type: `Map<String, TaskProgress>` (task names → TaskProgress)
- **Same format as cloud!**

### Cloud Storage  
- Key: `required_tasks` in CloudUserData
- Type: `Map<String, TaskProgress>` (task names → TaskProgress)
- **No change needed**

## Migration Strategy

1. **Create Migration Utility**
   - Convert old `Map<taskId, Boolean>` → new `Map<taskName, TaskProgress>`
   - Run once on app launch if flag not set
   - Preserve all existing data

2. **Update DailyProgressManager**
   - Change storage format internally
   - Update `getCompletedTasksMap()` to return `Map<String, TaskProgress>` (or create new method)
   - Update `markTaskCompleted()` to save in new format
   - Keep interface compatible initially, then update callers

3. **Update All Callers**
   - Update code that reads `getCompletedTasksMap()` 
   - Change from `completedTasks[taskId] == true` to `completedTasks[taskName]?.status == "complete"`
   - Update TaskCompletionHandler, Layout, etc.

4. **Simplify Cloud Storage**
   - CloudStorageManager: Direct copy, no conversion
   - Remove CloudDataApplier conversion logic
   - Remove ProgressDataCollector conversion logic

5. **Clean Up**
   - Remove old conversion code
   - Remove CloudDataApplier class (or simplify it)
   - Update ProgressDataCollector to just read/write directly

## Implementation Steps

### Step 1: Create Migration Utility
- `TaskProgressMigration.kt` - Handles one-time migration
- Converts old format to new format
- Sets migration flag

### Step 2: Update DailyProgressManager
- Add migration check on initialization
- Change internal storage to use `Map<String, TaskProgress>`
- Update methods to work with new format
- Keep backward compatibility during transition

### Step 3: Update Interfaces and Callers
- Update IProgressManager interface
- Update all callers to use task names instead of IDs
- Update TaskCompletionHandler
- Update Layout.kt
- Update any other code accessing completed tasks

### Step 4: Simplify Cloud Sync
- Update CloudStorageManager to direct copy
- Remove CloudDataApplier (or simplify to just copy)
- Update ProgressDataCollector to read/write directly

### Step 5: Test and Clean Up
- Test migration path
- Test all task lookups
- Test cloud sync
- Remove old code

## Key Changes

### DailyProgressManager Changes
- `getCompletedTasksMap()` → Returns `Map<String, TaskProgress>`
- Storage key: `{profile}_required_tasks` (instead of `{profile}_completed_tasks`)
- Remove `completed_task_names` (names are now keys)

### CloudStorageManager Changes
- `uploadToCloud()`: Direct copy from local to cloud (no conversion)
- `downloadFromCloud()`: Direct copy from cloud to local (no conversion)
- Remove CloudDataApplier entirely (or simplify to identity function)

### ProgressDataCollector Changes
- `collectLocalData()`: Just read from SharedPreferences, no conversion
- Remove conversion logic

## Benefits After Migration

1. **~550 lines of conversion code eliminated**
2. **Single format everywhere** - same structure
3. **Simpler code** - direct read/write
4. **Fewer bugs** - no conversion logic to break
5. **Easier to understand** - one format, one way to store/read

## Risks and Mitigation

1. **Migration risk** - Mitigate with thorough testing, rollback plan
2. **Task name changes** - Already accepted this risk (cloud uses names)
3. **Code changes** - One-time effort, simpler code forever after
