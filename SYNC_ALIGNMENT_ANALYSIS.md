# Sync Process: Requirements vs Code

This document compares **000Requirements.md** and **Daily Reset Logic.md** with the current code for when and how sync runs.

---

## What the requirements say

### When sync runs (Daily Reset Logic.md)

- **daily_reset_process()** then **cloud_sync()** run only during:
  1. **BaerenEd BattleHub Load**
  2. **BaerenEd Trainer Map Load**

- **Game completion** (000Requirements.md): When a game completes:
  1. Update all local data first (berries, banked_time, game_indices, task status).
  2. Update **last_updated** (synchronously with `commit()`).
  3. **Sync to cloud:** call **update_cloud_with_local()** — **synchronously** (wait for completion).
  4. **Then** redraw/refresh the screen.

- **Settings:** Update local + last_updated; do **not** call update_cloud_with_local() from settings; the next app screen load triggers cloud_sync().

### What data is synced

- **Sync methods are all-or-nothing.**  
  "All or nothing operations. The last_updated was already modified before the sync, so it should not be changed in update_cloud_with_local() or update_local_with_cloud() methods."

- **update_cloud_with_local()** (Daily Reset Logic): set on cloud: last_reset, last_updated, required_tasks, checklist_items, practice_tasks, berries_earned, **banked_mins**, game_indices, pokemon_unlocked, parent_email, parent_pin, active_profile.  
  So **one** full push of all profile data. There is **no** mention of a separate or partial sync (e.g. “sync only banked_mins”).

---

## Where the code does not align

### 1. Partial sync (not in requirements)

**Requirement:** Only full syncs: update_cloud_with_local() or update_local_with_cloud() with full profile data. No partial sync.

**Code:** A **partial** sync exists:

- **DailyProgressManager.setBankedRewardMinutes()** (and thus **addStarsToRewardBank()**, **addRewardMinutes()**) calls **syncBankedMinutesToCloud(minutes)** immediately after saving local banked minutes.
- That path updates **only** `banked_mins` (and previously `last_updated`) in the cloud — i.e. a partial write to the same row that full sync uses.

So the code added a second, partial sync path that the requirements do not describe. That partial sync is what caused the race (partial update advancing cloud last_updated and blocking the full upload).

**Conclusion:** Remove the partial sync. Banked minutes should only reach the cloud via the normal flow: update local, update last_updated, then either (a) full sync at game completion, or (b) full sync when BattleHub/Trainer Map load runs cloud_sync() and pushes with update_cloud_with_local().

---

### 2. When sync is triggered (multiple extra triggers)

**Requirement:** daily_reset_process() + cloud_sync() on **BattleHub Load** and **Trainer Map Load** only. Game completion: one synchronous update_cloud_with_local() after local updates, then redraw.

**Code:**

| Event | Requirement | Code |
|-------|-------------|------|
| BattleHub | Run on “BattleHub Load” | Runs on **initial load**, **onResume()**, and **onActivityResult()** when returning from TrainingMap. So **three** triggers for the same “load” (load + every resume + every return from map). |
| Trainer Map | Run on “Trainer Map Load” | Runs on **initial load** and **onResume()** (with 1s delay). **Two** triggers. |
| Game/task completion | Sync **synchronously**, then redraw | TrainingMapActivity: advanceLocalTimestampForProfile() then **lifecycleScope.launch(Dispatchers.IO)** for updateLocalTimestampAndSyncToCloud(). Sync is **async**; redraw (loadTasksIntoMap()) does **not** wait for sync to complete. So “sync must complete before redraw” is not satisfied. |
| Checklist item marked complete (dialog) | Not explicitly called out (could be “task completion”) | Calls updateLocalTimestampAndSyncToCloud() in a coroutine — extra explicit sync trigger. |

So sync runs more often than “on load” (onResume + onActivityResult), and game completion sync is not synchronous with the redraw.

---

### 3. Summary

| Topic | Requirement | Code |
|-------|-------------|------|
| Partial sync | None; only full sync (all-or-nothing). | **syncBankedMinutesToCloud()** runs on every setBankedRewardMinutes / addStarsToRewardBank / addRewardMinutes — partial cloud update. |
| When sync runs | BattleHub Load, Trainer Map Load; plus once per game completion (synchronous). | Load + onResume + onActivityResult (BattleHub); Load + onResume (TrainingMap); plus async sync on task completion and checklist complete. |
| Game completion sync | Synchronous; redraw only after sync completes. | Sync launched in coroutine; redraw does not wait for sync. |

---

## Recommended changes to align with requirements

1. **Remove the partial sync**  
   - Remove the call to **syncBankedMinutesToCloud()** from **DailyProgressManager.setBankedRewardMinutes()** (and any other callers of that partial sync).  
   - Rely only on full **update_cloud_with_local()** (at game completion and when cloud_sync() runs on BattleHub/Trainer Map load).

2. **Optional (for strict alignment):**  
   - Restrict when daily_reset_process() + cloud_sync() run to “on load” only (e.g. once per BattleHub/Trainer Map entry), not on every onResume/onActivityResult, if you want to match the letter of “BattleHub Load” / “Trainer Map Load”.  
   - Make game completion sync synchronous (e.g. runBlocking or suspend until sync completes) before redrawing the map.

The critical fix for the race and for matching the “no partial sync” requirement is **(1) remove the partial sync**.

---

## Cloud last_updated confirmation

**Q: When we push from local to cloud, do we set cloud last_updated = local last_updated?**

**Yes.** Full upload uses `uploadUserData(data: CloudUserData)`. The payload is `gson.toJson(data)` where `data` is the full `CloudUserData` object, which includes `lastUpdated` (serialized as `last_updated`). So when the PATCH (or POST) succeeds, the cloud row gets `last_updated` = the value we sent from local. There is no DB trigger overwriting it (you removed that). So after a successful push, cloud matches local for `last_updated` and all other fields.

**Q: So how could cloud have been "newer" in the race?**

Because there were **two writers** to the same cloud row:

1. **Full upload** (update_cloud_with_local): collects local data, sets local `last_updated` to e.g. 11:22:07.878, then calls uploadUserData. uploadUserData **first** does a GET to read cloud `last_updated`, then compares, then (if local is newer) PATCHes the full payload.
2. **Partial sync** (syncBankedMinutesToCloud, now removed): ran when banked minutes were updated. It PATCHed the row with only `banked_mins` and (before we changed it) `last_updated` = "now" = e.g. 11:22:07.899.

So the partial sync **wrote to the cloud first** (or its write was visible before the full upload's PATCH). When the full upload then did its GET, it saw cloud `last_updated` = 11:22:07.899. Full upload's local timestamp was 11:22:07.878. So cloud appeared "newer" and the full upload correctly skipped overwriting — but that "newer" cloud had **stale** berries (10) because the partial sync had only updated `banked_mins`, not the full row. So the race was: the **other** writer (partial sync) advanced cloud `last_updated` without pushing full data; the full upload then saw that newer timestamp and didn't push. With the partial sync removed, the only writer is the full upload, so when it pushes, cloud will match local.

---

## Optional next steps — recommendations

**1. When sync runs (BattleHub / Trainer Map "load" only?)**

- **What it means:** Right now we run daily_reset_process() + cloud_sync() on every **onResume** and (for BattleHub) every **onActivityResult** when returning from the map. The requirements say "BattleHub Load" and "Trainer Map Load" — that could mean "when the screen is first loaded" (once per navigation) or "whenever the screen is shown" (including resume/return).
- **Recommendation:** **Leave as-is.** Running sync on onResume/onActivityResult is a reasonable interpretation of "load" (user sees the screen, we sync). It also helps when the user returns from a game or from the map so the UI and cloud stay in sync. Restricting to "only the very first time the activity is created" would mean no sync when returning from Training Map to BattleHub, which would be worse.

**2. Game completion sync: make it blocking?**

- **What it means:** Requirements say sync after game completion must be **synchronous** and the screen must redraw **only after** sync completes. Today we launch sync in a coroutine and redraw immediately.
- **Recommendation:** **Yes — make it blocking (wait for sync before redraw).** That way: (a) we match the requirement; (b) when the user then goes to BattleHub, onResume's cloud_sync() is less likely to see a stale cloud (because the completion sync has already finished pushing); (c) we avoid overlapping syncs (completion sync and a later onResume sync) fighting over timestamps. So: after updating local and last_updated, call updateLocalTimestampAndSyncToCloud in a blocking way (e.g. runBlocking or suspend and await in a coroutine) and only then call loadTasksIntoMap().
