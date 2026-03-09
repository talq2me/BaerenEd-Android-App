# BaerenEd Daily Reset and Sync Overview

**Mode: ONLINE-ONLY (BaerenEd).** No local storage for progress. All progress is read from and written to the **database** only. Config (task/chore definitions) is read from **GitHub** where specified below.

---

## IMPORTANT CLARIFICATIONS (still apply)

1. **Date comparison**: To determine if it is "today", use the date part only, in EST. All relevant timestamps read and written use EST. Example format: `2026-01-14 11:48:34.401`.

2. **GitHub JSON source of truth**: Always check GitHub first for config JSON. When the app cannot diff GitHub vs existing data, pull from GitHub and overwrite/merge as described (e.g. Trainer Map merge).

3. **Task preservation (merge)**: When merging GitHub config with DB data: remove tasks/items that no longer exist in the JSON; add new tasks/items from the JSON; update only star_count (and similar metadata) for existing tasks; **preserve** existing completion, correct/incorrect, and times_completed in the DB.

4. **Network failures**: Retry a few times if it won’t cause performance issues or crashes. Otherwise don’t retry.

5. **BaerenLock app lists**: The user_data table has columns reward_apps, blacklisted_apps, white_listed_apps for the profile (JSONB). See BaerenLock section below for online-only behavior and what may be stored locally.

6. **Timestamp format**: Store in EST. Example: `2026-01-14 11:48:34.401`.

7. **Chores**: Same as required_tasks/checklist_items for reset: **blank** the chores column on daily reset, then **restore** chores from GitHub (chores config) into the DB (default all `done = false`). Read from DB for display; write to DB when marked complete. Coins_earned: read/write DB; **not** reset on daily reset.

8. **banked_mins** = **reward time** (clock icon in UI). Same value in DB and UI.

9. **game_indices**: Read/write **DB only**, never stored locally. Do **not** reset on daily reset.

10. **last_updated**: Keep it; used in reports and to know when something was modified.

---

## "Reset all progress" (settings menu)

The settings menu item **"Reset all progress"** should set **DB** `last_reset` to **now() at EST minus 1 day** (using only the **date** part for the comparison). That forces the next screen load to see `last_reset` ≠ today (by date) and run the daily reset (DB update) and then load from DB. No local copy of last_reset to update in online-only.

---

## BaerenEd ONLINE-ONLY: When reset and load run

- **Main screen** (display/refresh): Check last_reset → if needed run daily reset in DB → then load from DB and draw.
- **Trainer Map** (display/refresh): Check last_reset → if needed run daily reset in DB → then run Trainer Map load (GitHub merge + read from DB).
- **Battle Hub load**: Same idea: check last_reset, reset in DB if needed, then load from DB.

So: **whenever we try to read content from the DB**, we first check last_reset; if it’s not today we run the DB reset, then load from DB to show.

---

## daily_reset_check_and_run() (ONLINE-ONLY)

When a screen needs to display data:

1. **Read** from DB the current profile row (at least `last_reset`).
2. **If** `last_reset` (date part, EST) **is today**  
   → do nothing, continue to load/display from DB.
3. **Else** (`last_reset` is not today, by **date part** only):
   - **Run a single DB update** that:
     - Sets `last_reset = now()` at EST.
     - **Blanks**: `required_tasks`, `checklist_items`, `practice_tasks`, `berries_earned`, `banked_mins`, **chores** (same as the others – blank the column).
     - Does **not** change `coins_earned`, `pokemon_unlocked`, or `game_indices`.
   - Then **restore from GitHub** where needed: required_tasks/checklist_items/practice_tasks are restored when Trainer Map loads (merge from GitHub into DB); **chores** are restored from GitHub (chores config) into the DB when the app needs to show chores (default all `done = false`).
   - Then **load** the profile row from the DB and use it for display.

There is no "local" copy of these fields; no `reset_local()` that writes to local storage. The reset is done **in the DB** only.

---

## Main screen (ONLINE-ONLY)

- On display/refresh: Run **daily_reset_check_and_run()** (so if last_reset wasn’t today, DB is reset and we have fresh data).
- **Read from DB** for the profile: `berries_earned`, `coins_earned`, `banked_mins` (reward time / clock icon), `pokemon_unlocked`.
- Draw from that DB data only. No local cache for progress.

---

## Trainer Map (ONLINE-ONLY)

**On display/refresh:**

1. Run **daily_reset_check_and_run()** (DB reset if last_reset ≠ today).
2. **Pull** required_tasks, checklist_items, practice_tasks **definitions** from **GitHub** (profile config).
3. **Merge** with DB: update DB columns with the config (structure, labels, stars) but **preserve** existing completion and correct/incorrect (and times_completed for practice) for each task/item. Write the **merged** result to the DB.
4. **Read** from DB the latest required_tasks, checklist_items, practice_tasks (completion status).
5. Display complete/incomplete and draw the map from this DB data.

**On task/checklist completion:**

1. **Update the DB** with that task’s/item’s completion status and correct/incorrect (and questions) as applicable.
2. **Read** that task/item (or the full set) from the DB and redraw so the UI shows it as complete.

---

## Games using JSON (ONLINE-ONLY)

- **On load**: Always fetch the **latest** game JSON from **GitHub**. Use it for the game. **game_indices**: read from DB (never local); they are **not** reset on daily reset.
- **On game complete**: **Update the DB** with the task (required or practice) completed, the updated correct/incorrect/questions, and **game_indices** (increment for that game). Then refresh/redraw so UI shows latest from DB.

No “update local first then sync”; write completion and game_indices **directly to the DB**.

---

## Pokemon battle completion (ONLINE-ONLY)

- **Update the DB** to unlock one more pokemon for that profile.
- **Refresh** the page/screen so the app pulls the latest from the DB and displays the updated pokemon.

---

## Chores (ONLINE-ONLY, same pattern as tasks/checklist)

- **Daily reset**: **Blank** the chores column (same as required_tasks/checklist_items). Then **restore** chores from **GitHub** (chores config) into the DB when we need to show chores – default all `done = false`. Do **not** reset `coins_earned`.
- **Display**: Read chores (list + done state) and coins from **DB** when displaying.
- **Mark complete/uncomplete**: **Write to DB** (chore `done` and `coins_earned`). Then refresh/read from DB to redraw.
- **Chores list and coin values**: Read from **GitHub** (chores config); write into DB when restoring after reset or when first loading.

---

## Settings changes (ONLINE-ONLY)

When the user changes settings (e.g. active profile, parent pin, parent email, reward time), update the **DB** (or wherever those settings are stored in online-only). No “update local then sync”; no local copy. When the settings screen closes and an app screen loads, that screen will load from DB as above.

---

## BaerenLock (ONLINE-ONLY, same principles)

BaerenLock also treats **DB and GitHub as source of truth**. It should **not** store progress (or reward time) locally.

- **May store locally** (and not read every time, since they rarely change): **profile in use**, **admin password**, **parent email**, **whitelist** (white_listed_apps), **reward apps**, **blocked apps** (blacklisted_apps).
- **Reward time (banked_mins)**: **Read from DB** when displaying; **set in DB** when the user changes it. Do **not** store reward time locally.
- **Daily reset**: BaerenLock can detect when a daily reset is needed (last_reset date ≠ today) and run the **same reset logic** as BaerenEd: blank required_tasks, checklist_items, practice_tasks, berries_earned, banked_mins, chores in the DB; set last_reset = now() EST; do not change coins_earned, pokemon_unlocked, game_indices. Then restore from GitHub as needed (e.g. when loading screens that need tasks or chores).

---

## Removed / no longer used in BaerenEd ONLINE-ONLY

- **Local storage** for progress: no `local.profile.required_tasks`, `local.profile.checklist_items`, `local.profile.practice_tasks`, `local.profile.berries_earned`, `local.profile.banked_mins`, `local.profile.game_indices`, `local.profile.pokemon_unlocked`, etc. DB only. **game_indices**: read/write DB only; do **not** reset on daily reset.
- **cloud_sync()** (compare last_updated and call update_cloud_with_local / update_local_with_cloud): not used. We read/write DB directly.
- **update_cloud_with_local()** / **update_local_with_cloud()**: not used for BaerenEd in online-only mode. Writes go straight to the DB.
- **reset_local()** that sets local variables and get_content_from_json() that “populates local”: replaced by “DB reset then load from DB” and “Trainer Map: merge GitHub into DB then read from DB.”

---

## Resolved

- **game_indices**: Read/write DB only; never stored locally. Do **not** reset on daily reset.
- **last_updated**: Kept; used in reports and to know when something was modified.
- **banked_mins** = **reward time** (same in DB and UI).
