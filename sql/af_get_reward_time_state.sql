-- Call sites (BaerenLock):
--   SupabaseInterface.fetchRewardTimeState — periodic UI poll + reward state refresh (no daily reset).

-- Minimal read for dumb-UI reward display: avoids shipping the full user_data row on a timer.

CREATE OR REPLACE FUNCTION af_get_reward_time_state(p_profile text)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT jsonb_build_object(
    'banked_mins', COALESCE(ud.banked_mins, 0),
    'reward_time_expiry', to_jsonb(ud.reward_time_expiry)
  )
  FROM user_data ud
  WHERE ud.profile = p_profile
  LIMIT 1;
$$;

GRANT EXECUTE ON FUNCTION af_get_reward_time_state(text) TO anon, authenticated, service_role;
