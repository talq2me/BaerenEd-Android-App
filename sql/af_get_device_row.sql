-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetDeviceRow.
--   app/src/main/java/com/talq2me/baerened/SettingsManager.kt  -  device row reads.

-- BaerenEd: af_get_device_row
-- (from af_data_access_rpcs.sql)

-- -----------------------------------------------------------------------------
-- devices
-- -----------------------------------------------------------------------------

-- Full devices row as jsonb (includes BaerenLock columns when present on the table:
-- baerenlock_health_status, baerenlock_health_issues, baerenlock_last_health_check).

CREATE OR REPLACE FUNCTION af_get_device_row(p_device_id text)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT to_jsonb(d.*)
  FROM devices d
  WHERE d.device_id = p_device_id
  LIMIT 1;
$$;
GRANT EXECUTE ON FUNCTION af_get_device_row(text) TO anon, authenticated, service_role;
