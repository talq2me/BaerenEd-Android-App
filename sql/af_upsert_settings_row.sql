-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt - invokeAfUpsertSettingsRow.
--   app/src/main/java/com/talq2me/baerened/SettingsManager.kt - saveSettingsToCloud / related.

-- BaerenEd: af_upsert_settings_row

CREATE OR REPLACE FUNCTION af_upsert_settings_row(p_parent_email text, p_pin text, p_aggressive_cleanup boolean DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  UPDATE settings SET
    parent_email = COALESCE(p_parent_email, parent_email),
    pin = COALESCE(p_pin, pin),
    aggressive_cleanup = CASE WHEN p_aggressive_cleanup IS NULL THEN aggressive_cleanup ELSE p_aggressive_cleanup END,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE id = 1;

  IF NOT FOUND THEN
    INSERT INTO settings (id, parent_email, pin, aggressive_cleanup, last_updated)
    VALUES (1, COALESCE(p_parent_email, ''), COALESCE(p_pin, ''), COALESCE(p_aggressive_cleanup, true), (NOW() AT TIME ZONE 'America/Toronto'))
    ON CONFLICT (id) DO UPDATE SET
      parent_email = EXCLUDED.parent_email,
      pin = EXCLUDED.pin,
      aggressive_cleanup = EXCLUDED.aggressive_cleanup,
      last_updated = EXCLUDED.last_updated;
  END IF;
END;
$$;
GRANT EXECUTE ON FUNCTION af_upsert_settings_row(text, text, boolean) TO anon, authenticated, service_role;
