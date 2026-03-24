-- BaerenEd: Update berries_earned and optionally banked_mins for a profile (scalar params only).
-- Call when berries/banked change without a task completion (e.g. battle spend, reset).
--
-- p_banked_mins: if NULL, banked_mins column is NOT updated (only berries_earned + last_updated).
-- If provided, sets banked_mins to that value (same as before).
--
-- Examples:
--   Battle spend (clear berries only): {"p_profile": "AM", "p_berries_earned": 0, "p_banked_mins": null}
--   Set both: {"p_profile": "TE", "p_berries_earned": 10, "p_banked_mins": 5}

CREATE OR REPLACE FUNCTION af_update_berries_banked(
  p_profile text,
  p_berries_earned int,
  p_banked_mins int DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF p_banked_mins IS NULL THEN
    UPDATE user_data
    SET
      berries_earned = p_berries_earned,
      last_updated = (NOW() AT TIME ZONE 'America/Toronto')
    WHERE profile = p_profile;
  ELSE
    UPDATE user_data
    SET
      berries_earned = p_berries_earned,
      banked_mins = p_banked_mins,
      last_updated = (NOW() AT TIME ZONE 'America/Toronto')
    WHERE profile = p_profile;
  END IF;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_berries_banked(text, int, int) TO anon, authenticated, service_role;
