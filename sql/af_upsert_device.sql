-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpsertDevice.
--   app/src/main/java/com/talq2me/baerened/SettingsManager.kt  -  sync device / active profile.

-- BaerenEd: af_upsert_device
-- (from af_data_access_rpcs.sql)

-- Profile sync: pass p_apply_baerenlock_health = false (default). Updates device_name, active_profile, last_updated;
-- preserves BaerenLock health columns on conflict.
-- BaerenLock health sync: pass p_apply_baerenlock_health = true with health fields; on conflict updates device_name and
-- health columns only (preserves last_updated and active_profile). Use explicit null for p_baerenlock_health_issues to clear it.
DROP FUNCTION IF EXISTS af_upsert_device(text, text, text, text);

CREATE OR REPLACE FUNCTION af_upsert_device(
  p_device_id text,
  p_device_name text,
  p_active_profile text,
  p_last_updated text DEFAULT NULL,
  p_baerenlock_health_status text DEFAULT NULL,
  p_baerenlock_health_issues text DEFAULT NULL,
  p_baerenlock_last_health_check text DEFAULT NULL,
  p_apply_baerenlock_health boolean DEFAULT false
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_ts timestamp(3);
  v_hc_ts timestamp(3);
BEGIN
  BEGIN
    v_ts := NULLIF(trim(COALESCE(p_last_updated, '')), '')::timestamp(3);
  EXCEPTION WHEN OTHERS THEN
    v_ts := (NOW() AT TIME ZONE 'America/Toronto');
  END;
  IF v_ts IS NULL THEN
    v_ts := (NOW() AT TIME ZONE 'America/Toronto');
  END IF;

  v_hc_ts := NULL;
  IF p_apply_baerenlock_health THEN
    BEGIN
      v_hc_ts := NULLIF(trim(COALESCE(p_baerenlock_last_health_check, '')), '')::timestamp(3);
    EXCEPTION WHEN OTHERS THEN
      v_hc_ts := (NOW() AT TIME ZONE 'America/Toronto');
    END;
    IF v_hc_ts IS NULL THEN
      v_hc_ts := (NOW() AT TIME ZONE 'America/Toronto');
    END IF;
  END IF;

  INSERT INTO devices (
    device_id,
    device_name,
    active_profile,
    last_updated,
    baerenlock_health_status,
    baerenlock_health_issues,
    baerenlock_last_health_check
  )
  VALUES (
    p_device_id,
    p_device_name,
    p_active_profile,
    v_ts,
    CASE WHEN p_apply_baerenlock_health THEN p_baerenlock_health_status ELSE NULL END,
    CASE WHEN p_apply_baerenlock_health THEN p_baerenlock_health_issues ELSE NULL END,
    CASE WHEN p_apply_baerenlock_health THEN v_hc_ts ELSE NULL END
  )
  ON CONFLICT (device_id) DO UPDATE SET
    device_name = EXCLUDED.device_name,
    active_profile = CASE
      WHEN p_apply_baerenlock_health THEN devices.active_profile
      ELSE EXCLUDED.active_profile
    END,
    last_updated = CASE
      WHEN p_apply_baerenlock_health THEN devices.last_updated
      ELSE EXCLUDED.last_updated
    END,
    baerenlock_health_status = CASE
      WHEN p_apply_baerenlock_health THEN EXCLUDED.baerenlock_health_status
      ELSE devices.baerenlock_health_status
    END,
    baerenlock_health_issues = CASE
      WHEN p_apply_baerenlock_health THEN EXCLUDED.baerenlock_health_issues
      ELSE devices.baerenlock_health_issues
    END,
    baerenlock_last_health_check = CASE
      WHEN p_apply_baerenlock_health THEN EXCLUDED.baerenlock_last_health_check
      ELSE devices.baerenlock_last_health_check
    END;
END;
$$;
GRANT EXECUTE ON FUNCTION af_upsert_device(text, text, text, text, text, text, text, boolean) TO anon, authenticated, service_role;
