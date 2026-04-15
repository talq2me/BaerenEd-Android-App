-- Call sites (BaerenEd Android, this repo):
--   (no references under app/ in this repo.)
-- Other: 000Requirements.md (BaerenLock / reward pause behaviour); wire in lock app when applicable.

-- Pauses active reward time: remaining minutes go to banked_mins; clears reward_time_expiry.

CREATE OR REPLACE FUNCTION af_reward_time_pause(p_profile TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_expiry TIMESTAMP(3);
    v_remaining INTEGER;
BEGIN
    SELECT reward_time_expiry INTO v_expiry
    FROM user_data
    WHERE profile = p_profile
    FOR UPDATE;

    IF v_expiry IS NULL THEN
        RETURN;
    END IF;

    v_remaining := GREATEST(0, CEIL(EXTRACT(EPOCH FROM (v_expiry - (NOW() AT TIME ZONE 'America/Toronto'))) / 60.0));

    UPDATE user_data
    SET banked_mins = v_remaining,
        reward_time_expiry = NULL,
        last_updated = NOW() AT TIME ZONE 'America/Toronto'
    WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_pause(TEXT) TO anon, authenticated, service_role;
