##DbProfileSessionLoader
**Main job (BaerenEd):** After RPCs, mirror `user_data` → prefs + session ([CloudDataApplier] + [DailyProgressManager.setProgressDataAfterFetch]).
- `loadAfterDailyResetRpcThenApply(profile)` — **triggered by:** screen load, refresh, post-task refetch. **DB:** `UserDataRepository.fetchUserData` → `af_daily_reset` then `af_get_user_data`.
- `runGithubTaskConfigRpcsThenRefetch(profile)` — **triggered by:** main/trainer map config refresh. **DB:** five `af_update_*_from_config` / chores RPCs, then same fetch+apply.
- `refetchUserDataAndApply(profile)` — alias for load after task writes.
**Reset decision:** In Postgres only (`af_daily_reset`), not in this class.
*Desired flow (unchanged):* Extend `af_daily_reset` to chain `af_update_*_from_config` when a reset runs. Migrate legacy RPC names per sql + DEPLOY_ORDER.

**Trainer map / config path:** Run a chain of Postgres RPCs (af_required_tasks_from_config, practice, bonus, checklist, chores-from-GitHub), then refetch and apply again.
*Desired flow:* Still call the af_update_blah_from_config functions to ensure latest config is in db, then pull the data for the specific trainer map by calling a new postgres function af_get_required_tasks(p_profile) that returns the data needed to display the required tasks on the required trainer map or af_get_practice_tasks(p_profile) for the practice trainer map, bonus would also have another function. Then it displays what it received from the db.

**Also contains:** Older/unused-style helpers (merge from GitHub in Kotlin, legacy cloud_sync / reset_local paths, timestamp helpers). Active online path is “fetch → apply,” not the old compare-timestamps sync.
*Desired flow:* None of this seems relevant anymore.


##CloudSyncService
**Main job:** All Supabase REST/RPC I/O — user_data GET/PATCH/POST, af_daily_reset, task/checklist/chore/game-index/pokemon RPCs, config-from-GitHub RPCs, reward-time RPCs (af_reward_time_use, af_reward_time_add), generateESTTimestamp, etc.
*Desired flow:* I don't like the name of this anymore, cloudsync is not a thing under this new model of all the logic being in the db and the app just displays what the db has. If this kt is still needed, perhaps it is just SupabaseInterface.kt?


##DailyProgressManager
**Main job:** App-side progress orchestration — keeps currentSessionData after DB fetches, single-item RPC chains + refetch (applyRpcChainThenRefetch), task completion helpers, visibility/totals, berries/banked/chores/coins UI-facing getters, migration init, prefs mirror for offline-shaped reads. **Removed:** client-side daily-reset decisions and manual cloud reset patches — daily reset is **only** `af_daily_reset` on fetch ([DbProfileSessionLoader] / [UserDataRepository.fetchUserData]). `resetAllProgress()` is local cache wipe + empty session for tests only.
*Desired flow:* perhaps this is replaced with a TaskCompletion.kt that handles what happens when a task is completed as this is the 'write' flow most of the time. When a task is completed, it should call the appropriate af_update_required_task, af_update_practice_task, af_update_chore, ... same for game index, bonus task


#Perhaps missing?
**battlehub** I dont see any info on the battle hub, but when it displays, it needs to pull the stars, time, coins, berries and bank money from rpc calls, perhaps a new af_get_battle_hub_counts(p_profile). Perhaps this is a BattleHub.kt (though we might already have one). It would also handle updating the unlocked pokemon via the settings menu by calling af_update_unlocked_pokemon and af_update_berries_banked() with the appropriate parms for the battle spend (noted in the comments of the function). Changing either of those should also force a new pull of the af_get_battle_hub_counts() if it isn't already done by a ui reload issued on those actions (battle and pokemon unlock). also, after the battle complets, af_update_unlocked_pokemon is called to unlock one more.

**BaerenLock:** this shows reward_time, can set it via settings, this should be calling the functions from postgres to get and set these values. Same with the reward expiry, should be calling a postgres function to get and set this. 
Get and set of pin/parent email/ other settings stored in the db, should be get and update functions called in postgres.
Also has the ability to issue a daily reset which means when the baerenLock screen shows, it should call the same reset function in postgres to ensure a reset isn't needed before it pulls the banked_mins or reward_expiry to display in the ui. 

##Basic concept:## the android UI is stupid and just calls get and update methods from postgres to display data or update the data based on user actions in the app. If it does anything other than that, it is listed here by app:

---

## Implementation status (repo)

- **SQL:** See [`sql/DEPLOY_ORDER.md`](sql/DEPLOY_ORDER.md). New RPCs are **full copies** in [`sql/deploy_af_update_config_rpcs.sql`](sql/deploy_af_update_config_rpcs.sql) (alongside legacy names). `af_daily_reset` chains `af_update_*` only after an actual reset (`IF FOUND`). When refactor is done, drop legacy functions per [`sql/refactor_drop_legacy_config_rpcs_AFTER_MIGRATION.sql`](sql/refactor_drop_legacy_config_rpcs_AFTER_MIGRATION.sql).
- **Android:** [`SupabaseInterface`](app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt); [`DbProfileSessionLoader`](app/src/main/java/com/talq2me/baerened/DbProfileSessionLoader.kt) for fetch+apply after `af_daily_reset`. [`TaskCompletion`](app/src/main/java/com/talq2me/baerened/TaskCompletion.kt) wraps write RPC chains. No cloud on/off toggle; if Supabase is not configured, RPC paths fail fast.
- **Trainer Map UI (DB-first):** [`TrainingMapActivity`](app/src/main/java/com/talq2me/baerened/TrainingMapActivity.kt) (from Battle Hub) uses [`TrainerMapTaskMerge.prepareFromDbStrict`](app/src/main/java/com/talq2me/baerened/TrainerMapTaskMerge.kt). [`LayoutV2`](app/src/main/java/com/talq2me/baerened/LayoutV2.kt) + [`MainActivity`](app/src/main/java/com/talq2me/baerened/MainActivity.kt) for main screen.
- **BaerenLock:** [`DbUserDataRefresh`](../BaerenLock/BaerenLock/app/src/main/java/com/talq2me/baerenlock/DbUserDataRefresh.kt) — called directly from [`LauncherActivity`](../BaerenLock/BaerenLock/app/src/main/java/com/talq2me/baerenlock/LauncherActivity.kt) (onResume + refresh) and [`MainActivity.onResume`](../BaerenLock/BaerenLock/app/src/main/java/com/talq2me/baerenlock/MainActivity.kt). Network I/O lives in [`SupabaseInterface`](../BaerenLock/BaerenLock/app/src/main/java/com/talq2me/baerenlock/SupabaseInterface.kt) (Supabase client; not removable without replacing all RPCs).