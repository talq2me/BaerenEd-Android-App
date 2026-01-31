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

## Chores 4 $$ Feature

### Database (user_data table)

- **coins_earned** (INTEGER): Total coins earned from chores. Never reset during daily reset; accumulates until a future feature allows spending. Synced both ways (update_cloud_with_local / update_local_with_cloud).
- **chores** (JSONB): Array of chore state per profile. Each element: `chore_id`, `description`, `coins_reward` (from chores.json), `done` (true/false). Populated from `app/src/main/assets/config/chores.json`. On daily reset (BaerenEd or BaerenLock), set every `done` to `false`; do not reset `coins_earned`.

Chore JSONB structure (per item): `{ "chore_id": 1, "description": "Wash laundry", "coins_reward": 1, "done": false }`.

Upgrade scripts for existing Supabase DBs: see comments at top of `supabase_setup.sql`.

### Battle Hub – Chores 4 $$

- Add a button **"Chores 4 $$"** on the Battle Hub. On click, open a screen that lists all chores (from chores.json merged with stored chores state) with a checkbox per chore for the day.
- Checking a chore: set that chore’s `done` to `true`, add `coins_reward` to `coins_earned`, save locally, set `last_updated` so cloud sync runs on return to Battle Hub (resume).
- Unchecking a chore: set `done` to `false` and **deduct** that chore’s `coins_reward` from `coins_earned`, then save and update `last_updated`.
- On open, show the list with current `done` state so the child can continue from where they left off. Sync: include `chores` and `coins_earned` in profile sync both ways (same as other profile columns).

### Coins – Single Source (Chores Only)

- **Coins are only granted for chore completion** (Chores 4 $$). Remove all coin grants from tasks, required_tasks, practice_tasks, and checklist_items. Those continue to grant berries and banked time only.
- Battle Hub coin display shows **coins_earned** only (lifetime chore-earned total).
- Remove all other coin associations from the app (e.g. “all coins earned” / Pokemon button logic tied to coins; task/checklist coin calculations and UI). Coins in the app are solely from the chores feature.

### Daily Reset (BaerenEd and BaerenLock)

- When daily reset runs: set `chores[].done = false` for the profile; **do not** reset `coins_earned`.
- BaerenLock must know about the `chores` column so that when it runs daily reset it sets `chores[].done = false` (when applying or pushing reset data).

### Parent Report – Chores (per profile)

- New parent report (or section) **per profile**: list chores the kid completed **that day** (where `done == true` in current chores state for the day) and the **coins they earned that day** (sum of `coins_reward` for chores marked done today). This can be a separate “Chores report” or a section in the existing daily progress report.
