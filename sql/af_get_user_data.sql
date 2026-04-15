-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  downloadUserData ("af_get_user_data").
--   app/src/main/java/com/talq2me/baerened/UserDataRepository.kt  -  fetchUserData.

-- BaerenEd: af_get_user_data
-- (from af_data_access_rpcs.sql)

-- BaerenEd: RPC-only data access helpers (replace REST GET/PATCH on user_data, settings, devices, image_uploads).
-- Deploy after user_data / settings / devices / image_uploads tables exist.
-- Every function here is used by BaerenEd ([SupabaseInterface] and related) and/or BaerenLock (CloudSyncManager, DailyResetAndSyncManager).

-- -----------------------------------------------------------------------------
-- user_data reads
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION af_get_user_data(p_profile text)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT to_jsonb(ud.*)
  FROM user_data ud
  WHERE ud.profile = p_profile
  LIMIT 1;
$$;
GRANT EXECUTE ON FUNCTION af_get_user_data(text) TO anon, authenticated, service_role;
