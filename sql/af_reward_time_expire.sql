-- Call sites: BaerenLock SupabaseInterface.expireRewards -> af_reward_time_expire (p_force default false).
-- reports/banked_time.html parent "Expire Reward Time" -> af_reward_time_expire with p_force true.

-- Natural expiry (BaerenLock timer): clears reward_time_expiry only when expiry <= now (Toronto).
-- Parent force expiry (p_force true): ends any active session immediately (expiry set to now, then cleared).

CREATE OR REPLACE FUNCTION af_reward_time_expire(p_profile TEXT, p_force BOOLEAN DEFAULT FALSE)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_expiry TIMESTAMP(3);
    v_remaining INTEGER := 0;
    v_updated INTEGER := 0;
    v_now TIMESTAMP(3) := (CURRENT_TIMESTAMP AT TIME ZONE 'America/Toronto')::timestamp(3);
BEGIN
    SELECT reward_time_expiry INTO v_expiry
    FROM user_data
    WHERE profile = p_profile
    FOR UPDATE;

    IF v_expiry IS NULL THEN
        RETURN;
    END IF;

    IF p_force THEN
        IF (v_expiry AT TIME ZONE 'America/Toronto') > CURRENT_TIMESTAMP THEN
            v_remaining := GREATEST(0, CEIL(
                EXTRACT(EPOCH FROM (
                    (v_expiry AT TIME ZONE 'America/Toronto') - CURRENT_TIMESTAMP
                )) / 60.0
            ));
        END IF;

        UPDATE user_data
        SET reward_time_expiry = NULL,
            last_updated = v_now
        WHERE profile = p_profile
          AND reward_time_expiry IS NOT NULL;

        GET DIAGNOSTICS v_updated = ROW_COUNT;

        IF v_updated > 0 THEN
            INSERT INTO reward_time_log (profile, event, reward_mins_remaining, logged_at)
            VALUES (
                p_profile,
                'Parent Ended Reward Session',
                v_remaining,
                v_now
            );
        END IF;
        RETURN;
    END IF;

    -- Natural expiry: session ended when stored Toronto wall-clock expiry is at or before now.
    UPDATE user_data
    SET reward_time_expiry = NULL,
        last_updated = v_now
    WHERE profile = p_profile
      AND reward_time_expiry IS NOT NULL
      AND (reward_time_expiry AT TIME ZONE 'America/Toronto') <= CURRENT_TIMESTAMP;

    GET DIAGNOSTICS v_updated = ROW_COUNT;

    IF v_updated > 0 THEN
        INSERT INTO reward_time_log (profile, event, reward_mins_remaining, logged_at)
        VALUES (
            p_profile,
            'Reward Session Expiry',
            0,
            v_now
        );
    END IF;
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_expire(TEXT, BOOLEAN) TO anon, authenticated, service_role;
