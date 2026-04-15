-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt - upsertUserDataColumns -> RPC af_upsert_user_data_columns.
--   SupabaseInterface.invokeAddRewardTime - fallback only (when af_reward_time_add RPC fails).

-- BaerenEd: af_upsert_user_data_columns

-- Removed af_upload_user_data (full-row upload); use af_upsert_user_data_columns and task RPCs.
DROP FUNCTION IF EXISTS af_upload_user_data(jsonb);

CREATE OR REPLACE FUNCTION af_upsert_user_data_columns(p_profile text, p_columns jsonb)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  UPDATE user_data SET
    last_reset = CASE WHEN p_columns ? 'last_reset' AND NULLIF(trim(p_columns->>'last_reset'), '') IS NOT NULL
      THEN (p_columns->>'last_reset')::timestamp(3) ELSE last_reset END,
    last_updated = CASE WHEN p_columns ? 'last_updated' AND NULLIF(trim(p_columns->>'last_updated'), '') IS NOT NULL
      THEN (p_columns->>'last_updated')::timestamp(3) ELSE last_updated END,
    required_tasks = CASE WHEN p_columns ? 'required_tasks' THEN (p_columns->'required_tasks')::jsonb ELSE required_tasks END,
    practice_tasks = CASE WHEN p_columns ? 'practice_tasks' THEN (p_columns->'practice_tasks')::jsonb ELSE practice_tasks END,
    bonus_tasks = CASE WHEN p_columns ? 'bonus_tasks' THEN (p_columns->'bonus_tasks')::jsonb ELSE bonus_tasks END,
    checklist_items = CASE WHEN p_columns ? 'checklist_items' THEN (p_columns->'checklist_items')::jsonb ELSE checklist_items END,
    possible_stars = CASE WHEN p_columns ? 'possible_stars' THEN (p_columns->>'possible_stars')::int ELSE possible_stars END,
    banked_mins = CASE WHEN p_columns ? 'banked_mins' THEN (p_columns->>'banked_mins')::int ELSE banked_mins END,
    berries_earned = CASE WHEN p_columns ? 'berries_earned' THEN (p_columns->>'berries_earned')::int ELSE berries_earned END,
    coins_earned = CASE WHEN p_columns ? 'coins_earned' THEN (p_columns->>'coins_earned')::int ELSE coins_earned END,
    kid_bank_balance = CASE WHEN p_columns ? 'kid_bank_balance' AND jsonb_typeof(p_columns->'kid_bank_balance') <> 'null'
      THEN (p_columns->>'kid_bank_balance')::numeric ELSE kid_bank_balance END,
    last_coins_payout_at = CASE WHEN p_columns ? 'last_coins_payout_at' AND NULLIF(trim(p_columns->>'last_coins_payout_at'), '') IS NOT NULL
      THEN (p_columns->>'last_coins_payout_at')::timestamp(3)
      WHEN p_columns ? 'last_coins_payout_at' AND NULLIF(trim(p_columns->>'last_coins_payout_at'), '') IS NULL THEN NULL
      ELSE last_coins_payout_at END,
    chores = CASE WHEN p_columns ? 'chores' THEN (p_columns->'chores')::jsonb ELSE chores END,
    pokemon_unlocked = CASE WHEN p_columns ? 'pokemon_unlocked' THEN (p_columns->>'pokemon_unlocked')::int ELSE pokemon_unlocked END,
    game_indices = CASE WHEN p_columns ? 'game_indices' THEN (p_columns->'game_indices')::jsonb ELSE game_indices END,
    reward_time_expiry = CASE WHEN p_columns ? 'reward_time_expiry' AND NULLIF(trim(p_columns->>'reward_time_expiry'), '') IS NOT NULL
      THEN (p_columns->>'reward_time_expiry')::timestamp(3)
      WHEN p_columns ? 'reward_time_expiry' AND NULLIF(trim(p_columns->>'reward_time_expiry'), '') IS NULL THEN NULL
      ELSE reward_time_expiry END,
    reward_apps = CASE WHEN p_columns ? 'reward_apps' THEN p_columns->>'reward_apps' ELSE reward_apps END,
    blacklisted_apps = CASE WHEN p_columns ? 'blacklisted_apps' THEN p_columns->>'blacklisted_apps' ELSE blacklisted_apps END,
    white_listed_apps = CASE WHEN p_columns ? 'white_listed_apps' THEN p_columns->>'white_listed_apps' ELSE white_listed_apps END
  WHERE profile = p_profile;
END;
$$;
GRANT EXECUTE ON FUNCTION af_upsert_user_data_columns(text, jsonb) TO anon, authenticated, service_role;
