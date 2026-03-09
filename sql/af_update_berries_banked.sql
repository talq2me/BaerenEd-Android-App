-- BaerenEd: Update berries_earned and banked_mins for a profile (scalar params only).
-- Call when berries/banked change without a task completion (e.g. battle spend, reset).
-- Call: POST /rest/v1/rpc/af_update_berries_banked with body {"p_profile": "TE", "p_berries_earned": 10, "p_banked_mins": 5}

CREATE OR REPLACE FUNCTION af_update_berries_banked(
  p_profile text,
  p_berries_earned int,
  p_banked_mins int
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  UPDATE user_data
  SET
    berries_earned = p_berries_earned,
    banked_mins = p_banked_mins,
    last_updated = (NOW() AT TIME ZONE 'America/New_York')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_berries_banked(text, int, int) TO anon, authenticated, service_role;
