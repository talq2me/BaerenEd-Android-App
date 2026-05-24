-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeUseRewardTime ("af_reward_time_use"); RewardSelectionActivity.

-- Activates banked reward time: moves banked_mins into reward_time_expiry (Toronto wall clock).

DROP FUNCTION IF EXISTS af_reward_time_use(TEXT);

CREATE OR REPLACE FUNCTION af_reward_time_use(p_profile TEXT)
RETURNS jsonb
LANGUAGE plpgsql
AS $$
DECLARE
    v_banked INTEGER;
    v_expiry TIMESTAMP(3);
    v_now_ts TIMESTAMP(3);
BEGIN
    SELECT COALESCE(banked_mins, 0) INTO v_banked
    FROM user_data
    WHERE profile = p_profile
    FOR UPDATE;

    IF v_banked <= 0 THEN
        RETURN jsonb_build_object('banked_used', 0, 'reward_time_expiry', NULL, 'toronto_now', NULL);
    END IF;

    v_now_ts := (CURRENT_TIMESTAMP AT TIME ZONE 'America/Toronto')::timestamp(3);
    -- Instant + banked minutes, then render as America/Toronto wall clock (matches column contract).
    v_expiry := (
        (CURRENT_TIMESTAMP + (v_banked * INTERVAL '1 minute'))
        AT TIME ZONE 'America/Toronto'
    )::timestamp(3);

    INSERT INTO reward_time_log (profile, event, reward_mins_remaining, logged_at)
    VALUES (
        p_profile,
        'Start Reward Time Session',
        v_banked,
        v_now_ts
    );

    UPDATE user_data
    SET reward_time_expiry = v_expiry,
        banked_mins = 0,
        last_updated = v_now_ts
    WHERE profile = p_profile;

    RETURN jsonb_build_object(
        'banked_used', v_banked,
        'reward_time_expiry', to_char(v_expiry, 'YYYY-MM-DD HH24:MI:SS.MS'),
        'toronto_now', to_char(v_now_ts, 'YYYY-MM-DD HH24:MI:SS.MS')
    );
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_use(TEXT) TO anon, authenticated, service_role;
