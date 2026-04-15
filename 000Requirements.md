# BaerenEd Requirements

This document contains all requirements for the BaerenEd application.

## Data Architecture: Online-Only, No Local Storage

- **The app runs in online-only mode only.** Storing progress locally leads to stale data; do not use SharedPreferences (or any local persistence) for progress data.
- **Read/write:** All progress and display data is read from or written to the **database** only.
- **Config:** Task/chore definitions (what exists, stars, labels, etc.) are read from **GitHub** (config JSON, chores JSON). Where config is “stored in the DB,” it means we write the merged or fetched config into DB columns so the DB remains the single source of truth for what the app displays.
- **Terminology:** **banked_mins** in the DB is the same as **reward time** in the UI (clock icon; “ask for time”). Same value everywhere.

---

## Task Types (from profile config on GitHub)

- **required_tasks:** Tasks listed as required in the profile’s config JSON. Can earn berries and reward_time (banked_mins).
- **practice_tasks:** Tasks listed as practice in the profile’s config JSON. Can earn berries and reward_time (banked_mins).
- **checklist_items:** Checklist items from config; completion and details stored in DB only.
- Only tasks/items that are marked in the config to **display for today** are relevant for these columns / display.

---

## Daily Reset

- When we try to read content from the DB and **last_reset was not today** (compare **date part** only, Toronto time):
  1. Run a **DB update** that **blanks**: **required_tasks**, **checklist_items**, **practice_tasks**, **berries_earned**, **banked_mins**, **chores**. Set **last_reset** to now() in `America/Toronto`.
  2. **Do not** change **coins_earned**, **pokemon_unlocked**, or **game_indices**.
  3. **Restore from GitHub** as needed: tasks/checklist when Trainer Map loads; **chores** from chores config when the app needs to show chores (default all `done = false`).
  4. Then **load data from the DB** and show it.

---

## Main Screen

- On display/refresh: **Check last_reset** to determine if a daily reset is needed; if so, run the DB reset above, then load from DB.
- **Read from DB** for the profile: **berries_earned**, **coins_earned**, **banked_mins** (reward time), **pokemon_unlocked**.
- Draw those elements from the loaded DB data only (no local cache for this).

---

## Reward Time Usage Flow (BaerenLock + Parent Add Time)

- **banked_mins remains the stored reward pool** in DB. Kids earn it from tasks; parents can add minutes from BaerenEd or BaerenLock.
- **BaerenLock does not auto-start reward sessions.** A child must press **Use Reward Time**.
- **BaerenLock reward button visibility:**
  - Show **Pause Reward Time** when `reward_time_expiry` exists and is still in the future (active session).
  - Show **Use Reward Time** when there is no active session and `banked_mins > 0`.
  - Show no reward action button when there is no active session and `banked_mins = 0`.
- **Start/Unpause (`af_reward_time_use`):**
  - DB function reads `banked_mins`, sets `reward_time_expiry = now(America/Toronto) + banked_mins`, then sets `banked_mins = 0`.
  - BaerenLock then allows reward apps.
- **Pause (`af_reward_time_pause`):**
  - BaerenLock removes/blocks reward apps immediately.
  - DB function calculates remaining time as `reward_time_expiry - now(America/Toronto)` in whole minutes, writes that back to `banked_mins`, and clears `reward_time_expiry`.
- **Active session check:**
  - While a reward session is active, BaerenLock checks DB every minute.
  - If `now(America/Toronto) >= reward_time_expiry`, BaerenLock blocks reward apps and calls `af_reward_time_expire` to clear `reward_time_expiry`.
  - If `now(America/Toronto) < reward_time_expiry`, BaerenLock keeps reward apps available.
- **Parent add-time behavior (`af_reward_time_add`):**
  - If `reward_time_expiry` is active/future, add minutes to `reward_time_expiry`.
  - If no active expiry, add minutes to `banked_mins`.

---

## Trainer Map

- **On display/refresh:**
  1. Pull **required_tasks**, **checklist_items**, **practice_tasks** definitions from **GitHub** (profile config).
  2. **Merge** with the DB: update DB columns with the config (structure/labels/stars) while **preserving** any completion status and correct/incorrect (and similar) details already stored for each task/checklist item.
  3. Pull the **latest** completion status of **required_tasks**, **checklist_items**, **practice_tasks** from the DB.
  4. Display complete/incomplete and draw the map from this DB data.

- **On task/checklist completion:**
  1. **Update the DB** with the completion status and/or correct/incorrect (and related) details for that required_task, checklist_item, or practice_task.
  2. Pull the details for that task/item from the DB and display it as complete (since we just wrote it).

---

## Pokemon Battle Completion

- On battle completion: **Update the DB** to unlock one more pokemon for that profile.
- Then **refresh the page** (or reload) so the app pulls the latest from the DB and displays the updated pokemon count/list.

---

## Games Using JSON

- **When loading the game:** Always fetch the **latest** JSON for that game from **GitHub**. **game_indices**: read from **DB** only (never local); do **not** reset on daily reset.
- **When the game completes:** Update the **DB** with the task (required or practice) marked completed, the updated correct/incorrect (and questions), and **game_indices** (increment for that game). No local storage; DB only.

---

## Chores (same pattern as tasks/checklist)

- **Daily reset:** **Blank** the chores column (same as required_tasks/checklist_items). Then **restore** chores from **GitHub** (chores config) into the DB when we need to show chores – default all `done = false`.
- **Display:** Chores list and completion state are **read from the DB** when displayed.
- **Marking complete:** When the user marks a chore complete in the UI, **write to the DB** (that chore’s `done = true`, and add its `coins_reward` to **coins_earned**). Unchecking: set `done = false` and deduct that chore’s `coins_reward` from **coins_earned** in the DB.
- **Coins:** **Read** coins from the DB for display. **Write** coins to the DB when a child marks a chore complete (or unchecks) as above.
- **Chores list and coin values:** Read from **GitHub** (chores config); write into DB when restoring after reset or when first loading.

---

## Summary Table

| Data              | Read from     | Write to | Reset on daily reset |
|-------------------|---------------|----------|----------------------|
| required_tasks    | DB (after merge from GitHub on Trainer Map) | DB       | Yes (blank)          |
| checklist_items   | DB            | DB       | Yes (blank)          |
| practice_tasks    | DB (after merge from GitHub on Trainer Map) | DB       | Yes (blank)          |
| berries_earned    | DB            | DB       | Yes (zero)           |
| banked_mins       | DB            | DB       | Yes (zero)           |
| coins_earned      | DB            | DB (on chore complete) | No  |
| chores            | DB            | DB       | Yes (blank, then restore from GitHub) |
| pokemon_unlocked  | DB            | DB (on battle complete) | No  |
| game_indices      | DB            | DB       | No                   |
| Task/chore definitions | GitHub    | DB (merge/store) | No  |

**Note:** banked_mins = reward time (clock icon in UI). last_updated is kept for reports and to know when something was modified.
