# Online-first: DB + GitHub only (no cache, no local progress)

## Goal
- **DB and GitHub are the only source of truth.** No in-memory cache, no SharedPreferences for progress.
- **Read**: Fetch from DB when we need data. The result is used only for the current flow (e.g. this screen); not stored for later.
- **Write**: Build payload from the data we have this flow, upload to DB. No local copy kept.
- **Reset**: Build payload from GitHub, PATCH DB. No prefs.

## What we do
- **UserDataRepository**: No cache. fetchUserData() returns the result; caller uses it. uploadUserData() writes to DB; nothing stored.
- **DailyProgressManager**: Holds "progress data for this flow only" — the result of the fetch we just did. Set by entry point after fetch; cleared when leaving screen. getRequiredTasks / getCompletedTasksMap etc. read from that. saveRequiredTasks / savePracticeTasks update that in-memory data only; caller syncs to DB.
- **ProgressDataCollector**: collectLocalData(profile) builds upload payload from DailyProgressManager.getProgressDataForUpload(profile) (the data set this flow). No prefs.
- **Entry point (e.g. BattleHub)**: On load: fetch from DB → progressManager.setProgressDataForCurrentFlow(data). On leave: progressManager.clearProgressDataForCurrentFlow() so we never read stale next time.

## What stays
- **SettingsManager**: Active profile, PIN/email (device identity) — can remain in prefs.
- **GitHub**: Source of truth for config. Reset payload is built from GitHub content.

## Flow summary
1. **Screen load**: Fetch from DB → setProgressDataForCurrentFlow(data). UI reads from that for this flow only.
2. **Task complete**: Update progress data for this flow → caller uploads to DB (collectLocalData uses that data). No cache.
3. **First launch of day**: Fetch shows last_reset yesterday → build reset payload from GitHub → PATCH DB → setProgressDataForCurrentFlow(resetPayload).
4. **Leave screen**: clearProgressDataForCurrentFlow() so next time we fetch again.
