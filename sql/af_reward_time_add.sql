-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAddRewardTime; MainActivity.kt (grant minutes); BattleHubActivity.kt.
-- reports/banked_time.html — positive minutes add, negative minutes remove.

-- Parent path: add/remove minutes on banked_mins (no active session) or extend/shrink active reward_time_expiry.

CREATE OR REPLACE FUNCTION af_reward_time_add(p_profile TEXT, p_minutes INTEGER)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_now TIMESTAMP(3) := (NOW() AT TIME ZONE 'America/Toronto');
    v_sub INTEGER;
BEGIN
    IF p_minutes IS NULL OR p_minutes = 0 THEN
        RETURN;
    END IF;

    IF p_minutes > 0 THEN
        UPDATE user_data
        SET reward_time_expiry = CASE
                WHEN reward_time_expiry IS NOT NULL AND reward_time_expiry > v_now
                    THEN reward_time_expiry + (p_minutes * INTERVAL '1 minute')
                ELSE reward_time_expiry
            END,
            banked_mins = CASE
                WHEN reward_time_expiry IS NULL OR reward_time_expiry <= v_now
                    THEN COALESCE(banked_mins, 0) + p_minutes
                ELSE banked_mins
            END,
            last_updated = v_now
        WHERE profile = p_profile;
        RETURN;
    END IF;

    v_sub := -p_minutes;

    UPDATE user_data
    SET reward_time_expiry = CASE
            WHEN reward_time_expiry IS NOT NULL AND reward_time_expiry > v_now THEN
                CASE
                    WHEN reward_time_expiry - (v_sub * INTERVAL '1 minute') <= v_now THEN NULL
                    ELSE reward_time_expiry - (v_sub * INTERVAL '1 minute')
                END
            ELSE reward_time_expiry
        END,
        banked_mins = CASE
            WHEN reward_time_expiry IS NULL OR reward_time_expiry <= v_now
                THEN GREATEST(0, COALESCE(banked_mins, 0) - v_sub)
            ELSE banked_mins
        END,
        last_updated = v_now
    WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_add(TEXT, INTEGER) TO anon, authenticated, service_role;
