-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeUseRewardTime ("af_reward_time_use"); RewardSelectionActivity.

-- Activates banked reward time: moves banked_mins into reward_time_expiry (Toronto).

CREATE OR REPLACE FUNCTION af_reward_time_use(p_profile TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_banked INTEGER;
BEGIN
    SELECT COALESCE(banked_mins, 0) INTO v_banked
    FROM user_data
    WHERE profile = p_profile
    FOR UPDATE;

    IF v_banked <= 0 THEN
        RETURN;
    END IF;

    UPDATE user_data
    SET reward_time_expiry = (NOW() AT TIME ZONE 'America/Toronto') + (v_banked * INTERVAL '1 minute'),
        banked_mins = 0,
        last_updated = NOW() AT TIME ZONE 'America/Toronto'
    WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_use(TEXT) TO anon, authenticated, service_role;
