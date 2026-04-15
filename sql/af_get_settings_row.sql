-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetSettingsRow.
--   app/src/main/java/com/talq2me/baerened/SettingsManager.kt  -  loadSettingsFromCloud / related.

-- BaerenEd: af_get_settings_row
-- (from af_data_access_rpcs.sql)

-- -----------------------------------------------------------------------------
-- settings (id = 1)
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION af_get_settings_row()
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT to_jsonb(s.*)
  FROM settings s
  WHERE s.id = 1
  LIMIT 1;
$$;
GRANT EXECUTE ON FUNCTION af_get_settings_row() TO anon, authenticated, service_role;
