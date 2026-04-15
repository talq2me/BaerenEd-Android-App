-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfDailyReset.
--   app/src/main/java/com/talq2me/baerened/UserDataRepository.kt  -  fetchUserData before download.

-- BaerenEd: Daily reset applied at read time (AF = "at fetch").
-- Call this before reading user_data so the row for the given profile with last_reset date not equal to today (Toronto time)
-- gets reset: blank required_tasks, checklist_items, practice_tasks, berries_earned, banked_mins, chores;
-- set last_reset and last_updated to now() in America/Toronto. Does not change coins_earned, pokemon_unlocked, game_indices.
-- When a reset row was updated (FOUND), repopulates task/chore columns from GitHub via af_update_*
-- (full implementations are in the per-function af_update_* files in sql/).
-- Run in Supabase SQL Editor once to create the function; then call via PostgREST: POST /rest/v1/rpc/af_daily_reset with body {"p_profile": "AM"}

CREATE OR REPLACE FUNCTION af_daily_reset(p_profile text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  today_est date;
BEGIN
  today_est := (NOW() AT TIME ZONE 'America/Toronto')::date;

  UPDATE user_data
  SET
    last_reset = (NOW() AT TIME ZONE 'America/Toronto'),
    last_updated = (NOW() AT TIME ZONE 'America/Toronto'),
    required_tasks = '{}'::jsonb,
    checklist_items = '{}'::jsonb,
    practice_tasks = '{}'::jsonb,
    bonus_tasks = '{}'::jsonb,
    berries_earned = 0,
    banked_mins = 0,
    reward_time_expiry = NULL,
    chores = '[]'::jsonb
  WHERE profile = p_profile
    AND (last_reset IS NULL OR last_reset::date IS DISTINCT FROM today_est);

  IF FOUND THEN
    PERFORM af_update_tasks_from_config_required(p_profile);
    PERFORM af_update_tasks_from_config_practice(p_profile);
    PERFORM af_update_tasks_from_config_bonus(p_profile);
    PERFORM af_update_tasks_from_config_chores(p_profile);
  END IF;
END;
$$;

-- Grant execute to anon and authenticated so Supabase API can call it
GRANT EXECUTE ON FUNCTION af_daily_reset(text) TO anon;
GRANT EXECUTE ON FUNCTION af_daily_reset(text) TO authenticated;
GRANT EXECUTE ON FUNCTION af_daily_reset(text) TO service_role;
