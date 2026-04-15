-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAddRewardTime; MainActivity.kt (grant minutes); BattleHubActivity.kt.

-- Parent path: add minutes to banked_mins or extend active reward session.

CREATE OR REPLACE FUNCTION af_reward_time_add(p_profile TEXT, p_minutes INTEGER)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_minutes IS NULL OR p_minutes <= 0 THEN
        RETURN;
    END IF;

    UPDATE user_data
    SET reward_time_expiry = CASE
            WHEN reward_time_expiry IS NOT NULL AND reward_time_expiry > (NOW() AT TIME ZONE 'America/Toronto')
                THEN reward_time_expiry + (p_minutes * INTERVAL '1 minute')
            ELSE reward_time_expiry
        END,
        banked_mins = CASE
            WHEN reward_time_expiry IS NULL OR reward_time_expiry <= (NOW() AT TIME ZONE 'America/Toronto')
                THEN COALESCE(banked_mins, 0) + p_minutes
            ELSE banked_mins
        END,
        last_updated = NOW() AT TIME ZONE 'America/Toronto'
    WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_add(TEXT, INTEGER) TO anon, authenticated, service_role;
