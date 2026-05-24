-- Call sites (BaerenLock):
--   SupabaseInterface.fetchRewardTimeState — periodic UI poll + reward state refresh (no daily reset).

-- Minimal read for dumb-UI reward display: avoids shipping the full user_data row on a timer.
-- reward_mins_remaining is computed on the server (America/Toronto vs reward_time_expiry) so clients
-- do not depend on device clock or local timestamp parsing.

CREATE OR REPLACE FUNCTION af_get_reward_time_state(p_profile text)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT jsonb_build_object(
    'banked_mins', COALESCE(ud.banked_mins, 0),
    'reward_time_expiry', to_jsonb(to_char(ud.reward_time_expiry, 'YYYY-MM-DD HH24:MI:SS.MS')),
    'reward_mins_remaining',
      CASE
        WHEN ud.reward_time_expiry IS NOT NULL
          AND (ud.reward_time_expiry AT TIME ZONE 'America/Toronto') > CURRENT_TIMESTAMP
        THEN GREATEST(0, CEIL(
          EXTRACT(EPOCH FROM (
            (ud.reward_time_expiry AT TIME ZONE 'America/Toronto') - CURRENT_TIMESTAMP
          )) / 60.0
        ))::integer
        ELSE 0
      END,
    'reward_session_active',
      (ud.reward_time_expiry IS NOT NULL
        AND (ud.reward_time_expiry AT TIME ZONE 'America/Toronto') > CURRENT_TIMESTAMP)
  )
  FROM user_data ud
  WHERE ud.profile = p_profile
  LIMIT 1;
$$;

GRANT EXECUTE ON FUNCTION af_get_reward_time_state(text) TO anon, authenticated, service_role;
