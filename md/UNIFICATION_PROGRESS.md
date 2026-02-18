# Format Unification Progress

## Status: IN PROGRESS

### Completed ✅
1. ✅ **Migration Utility Created** - `TaskProgressMigration.kt` handles one-time conversion
2. ✅ **DailyProgressManager Updated** - Now uses `Map<String, TaskProgress>` with task names as keys
   - Changed storage key from `completed_tasks` to `required_tasks`
   - Updated `getRequiredTasks()` to return new format
   - Updated `markTaskCompletedWithName()` to save in new format
   - Updated `isTaskCompleted()` to look up by task name
   - Updated all progress calculation methods to use task names
   - Migration runs automatically on initialization

### In Progress ⏳
3. ⏳ **Update All Callers** - Need to update code that uses `getCompletedTasksMap()`
   - Layout.kt
   - TaskCompletionHandler
   - CloudStorageManager
   - ProgressDataCollector
   - Other files that access completed tasks

### Pending 📋
4. 📋 **Simplify CloudStorageManager** - Direct copy, remove conversion
5. 📋 **Remove Conversion Code** - Delete CloudDataApplier conversion logic
6. 📋 **Test Migration** - Verify migration works correctly

## Key Changes Made

### DailyProgressManager.kt
- Storage: `Map<String, Boolean>` (task IDs) → `Map<String, TaskProgress>` (task names)
- Key: `{profile}_completed_tasks` → `{profile}_required_tasks`
- All lookups now use task names instead of task IDs
- Migration runs on initialization

### TaskProgressMigration.kt
- Converts old format to new format
- Preserves all existing data
- Runs once per profile

## Next Steps

1. Update all callers to use task names instead of task IDs
2. Simplify CloudStorageManager sync (direct copy)
3. Remove conversion code
4. Test thoroughly
