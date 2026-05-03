-- Call sites: BaerenLock SupabaseInterface.expireRewards -> af_reward_time_expire.
-- Other: 000Requirements.md (BaerenLock on expiry); reports/banked_time.html.

-- Clears reward_time_expiry when session has expired (Toronto now).

CREATE OR REPLACE FUNCTION af_reward_time_expire(p_profile TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_updated INTEGER := 0;
BEGIN
    UPDATE user_data
    SET reward_time_expiry = NULL,
        last_updated = NOW() AT TIME ZONE 'America/Toronto'
    WHERE profile = p_profile
      AND reward_time_expiry IS NOT NULL
      AND reward_time_expiry <= (NOW() AT TIME ZONE 'America/Toronto');

    GET DIAGNOSTICS v_updated = ROW_COUNT;

    IF v_updated > 0 THEN
        INSERT INTO reward_time_log (profile, event, reward_mins_remaining, logged_at)
        VALUES (
            p_profile,
            'Reward Session Expiry',
            0,
            NOW() AT TIME ZONE 'America/Toronto'
        );
    END IF;
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_expire(TEXT) TO anon, authenticated, service_role;
