-- Call sites (BaerenEd Android, this repo):
--   (no references under app/ in this repo.)
-- Other: 000Requirements.md (BaerenLock on expiry); reports/banked_time.html  -  fetch RPC af_reward_time_expire.

-- Clears reward_time_expiry when session has expired (Toronto now).

CREATE OR REPLACE FUNCTION af_reward_time_expire(p_profile TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE user_data
    SET reward_time_expiry = NULL,
        last_updated = NOW() AT TIME ZONE 'America/Toronto'
    WHERE profile = p_profile
      AND reward_time_expiry IS NOT NULL
      AND reward_time_expiry <= (NOW() AT TIME ZONE 'America/Toronto');
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_expire(TEXT) TO anon, authenticated, service_role;
