-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetBattleHubCounts.
--   app/src/main/java/com/talq2me/baerened/BattleHubActivity.kt  -  hub counts.
--   SupabaseInterface.invokeAddRewardTime  -  fallback path reads hub after failed af_reward_time_add.

-- BaerenEd: Single JSON payload for Battle Hub (stars, berries, banked reward time, coins, pokemon, active reward expiry).
-- POST /rest/v1/rpc/af_get_battle_hub_counts {"p_profile":"AM"}

CREATE OR REPLACE FUNCTION af_get_battle_hub_counts(p_profile text)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT COALESCE(
    (
      SELECT jsonb_build_object(
        'possible_stars', COALESCE(ud.possible_stars, 0),
        'berries_earned', COALESCE(ud.berries_earned, 0),
        'banked_mins', COALESCE(ud.banked_mins, 0),
        'coins_earned', COALESCE(ud.coins_earned, 0),
        'pokemon_unlocked', COALESCE(ud.pokemon_unlocked, 0),
        'kid_bank_balance', COALESCE(ud.kid_bank_balance, 0),
        'reward_time_expiry', ud.reward_time_expiry
      )
      FROM user_data ud
      WHERE ud.profile = p_profile
    ),
    '{}'::jsonb
  );
$$;

GRANT EXECUTE ON FUNCTION af_get_battle_hub_counts(text) TO anon, authenticated, service_role;
