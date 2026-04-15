-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  ensureUserDataProfileExists (private) before reward time / add time / berries RPCs.

-- BaerenEd: af_insert_user_data_profile
-- (from af_data_access_rpcs.sql)

-- -----------------------------------------------------------------------------
-- user_data ensure / partial patch (no timestamp conflict logic)
-- -----------------------------------------------------------------------------

DROP FUNCTION IF EXISTS af_ensure_user_data_profile(text);

CREATE OR REPLACE FUNCTION af_insert_user_data_profile(p_profile text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  INSERT INTO user_data (profile, banked_mins, last_updated)
  VALUES (p_profile, 0, (NOW() AT TIME ZONE 'America/Toronto'))
  ON CONFLICT (profile) DO NOTHING;
END;
$$;
GRANT EXECUTE ON FUNCTION af_insert_user_data_profile(text) TO anon, authenticated, service_role;
