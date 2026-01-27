# BaerenEd Requirements

This document contains all requirements for the BaerenEd application.

## Game Completion Logic

### Task Types
There are two types of tasks defined in the config.json for each profile:
- **required_tasks**: Show on the "earn berries" trainer map
- **practice_tasks**: Show in the "earn extra berries" trainer map, which is only enabled after all required_tasks have been completed

### Game Launch Flow
When a game is launched:
1. Check if the game uses JSON questions
2. If yes, load the question at the index noted for the task in `game_indices` from the cloud
3. If cloud `game_indices` is not available, fall back to local `game_indices`
4. Start the game at that index

### Game Completion Flow
When a game completes, the following steps must be executed **in this exact order**:

1. **Update all local data FIRST:**
   - Grant berries/stars and banked_time
   - Update `game_indices` (increment the index if the game used JSON)
   - For **required tasks**: Set `correct`, `incorrect`, `questions` answered, and `'complete'` status
   - For **practice tasks**: Set `correct`, `incorrect`, `questions` answered, and `times_completed` (additive - add new values to any existing values for that game for today)
   - Note: Practice task values only get reset during a daily reset

2. **Update `last_updated` timestamp:**
   - Set `local.profile.last_updated` to current timestamp in EST
   - Use `commit()` for synchronous write to ensure it's saved before cloud sync

3. **Sync to cloud:**
   - Call `update_cloud_with_local()` to push all local changes to cloud
   - This must complete **synchronously** (wait for completion) before proceeding
   - All data (task completion status, berries, game_indices, correct/incorrect/questions, times_completed) must be synced

4. **Redraw screen:**
   - Only after cloud sync completes, redraw/refresh the screen
   - This ensures the UI shows the latest synced state

### Critical Requirements
- **All local updates must happen BEFORE cloud sync** - no async operations during local updates
- **Cloud sync must be synchronous** - use `runBlocking` or similar to wait for completion
- **Screen redraw only after sync completes** - prevents showing stale data
- **Practice task values are additive** - each completion adds to existing values, only reset on daily reset
- **Required task status is 'complete'** - once marked complete, it stays complete for the day
- **Game indices must be incremented** - when a JSON game completes, the index advances

### Implementation Notes
- Use `commit()` instead of `apply()` for all critical SharedPreferences writes to prevent race conditions
- Ensure `last_updated` timestamp is updated synchronously before triggering cloud sync
- Game indices are loaded from local storage (which should be synced from cloud during onResume)
- All task completion data (correct/incorrect/questions) must be passed to `markTaskCompletedWithName()`
- Practice tasks track cumulative `times_completed` separately in SharedPreferences for database sync
