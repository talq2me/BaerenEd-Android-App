-- Call sites (BaerenEd Android, BaerenLock):
--   SupabaseInterface.kt - saveSettingsToCloud / af_upsert_settings_row RPC
--   reports/banked_time.html - parent audio monitor settings

-- BaerenEd: af_upsert_settings_row

CREATE OR REPLACE FUNCTION af_upsert_settings_row(
    p_parent_email text,
    p_pin text,
    p_aggressive_cleanup boolean DEFAULT NULL,
    p_reward_audio_monitor_enabled boolean DEFAULT NULL,
    p_reward_audio_loudness_threshold integer DEFAULT NULL
)
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
    reward_audio_monitor_enabled = CASE
        WHEN p_reward_audio_monitor_enabled IS NULL THEN reward_audio_monitor_enabled
        ELSE p_reward_audio_monitor_enabled
    END,
    reward_audio_loudness_threshold = CASE
        WHEN p_reward_audio_loudness_threshold IS NULL THEN reward_audio_loudness_threshold
        ELSE p_reward_audio_loudness_threshold
    END,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE id = 1;

  IF NOT FOUND THEN
    INSERT INTO settings (
        id,
        parent_email,
        pin,
        aggressive_cleanup,
        reward_audio_monitor_enabled,
        reward_audio_loudness_threshold,
        last_updated
    )
    VALUES (
        1,
        COALESCE(p_parent_email, ''),
        COALESCE(p_pin, ''),
        COALESCE(p_aggressive_cleanup, true),
        COALESCE(p_reward_audio_monitor_enabled, true),
        COALESCE(p_reward_audio_loudness_threshold, 75),
        (NOW() AT TIME ZONE 'America/Toronto')
    )
    ON CONFLICT (id) DO UPDATE SET
      parent_email = EXCLUDED.parent_email,
      pin = EXCLUDED.pin,
      aggressive_cleanup = EXCLUDED.aggressive_cleanup,
      reward_audio_monitor_enabled = EXCLUDED.reward_audio_monitor_enabled,
      reward_audio_loudness_threshold = EXCLUDED.reward_audio_loudness_threshold,
      last_updated = EXCLUDED.last_updated;
  END IF;
END;
$$;

DROP FUNCTION IF EXISTS af_upsert_settings_row(text, text, boolean);

GRANT EXECUTE ON FUNCTION af_upsert_settings_row(text, text, boolean, boolean, integer) TO anon, authenticated, service_role;
