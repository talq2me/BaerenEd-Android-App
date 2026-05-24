-- Call sites: BaerenLock SupabaseInterface.pauseRewardTime -> af_reward_time_pause.
-- Other: 000Requirements.md (reward pause behaviour).

-- Pauses active reward time: remaining minutes go to banked_mins; clears reward_time_expiry.

CREATE OR REPLACE FUNCTION af_reward_time_pause(p_profile TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_expiry TIMESTAMP(3);
    v_remaining INTEGER;
    v_session_start INTEGER;
    v_now_ts TIMESTAMP(3);
BEGIN
    SELECT reward_time_expiry INTO v_expiry
    FROM user_data
    WHERE profile = p_profile
    FOR UPDATE;

    IF v_expiry IS NULL THEN
        RETURN;
    END IF;

    v_now_ts := (CURRENT_TIMESTAMP AT TIME ZONE 'America/Toronto')::timestamp(3);

    -- True remaining minutes: interpret stored expiry as Toronto wall clock vs current instant.
    v_remaining := GREATEST(0, CEIL(
        EXTRACT(EPOCH FROM (
            (v_expiry AT TIME ZONE 'America/Toronto') - CURRENT_TIMESTAMP
        )) / 60.0
    ));

    -- Never bank more than this session was started with (guards corrupt / mis-parsed expiry values).
    SELECT reward_mins_remaining INTO v_session_start
    FROM reward_time_log
    WHERE profile = p_profile
      AND event = 'Start Reward Time Session'
    ORDER BY logged_at DESC
    LIMIT 1;

    IF v_session_start IS NOT NULL THEN
        v_remaining := LEAST(v_remaining, v_session_start);
    END IF;

    INSERT INTO reward_time_log (profile, event, reward_mins_remaining, logged_at)
    VALUES (
        p_profile,
        'Pause Reward Session',
        v_remaining,
        v_now_ts
    );

    UPDATE user_data
    SET banked_mins = v_remaining,
        reward_time_expiry = NULL,
        last_updated = v_now_ts
    WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_pause(TEXT) TO anon, authenticated, service_role;
