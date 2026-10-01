-- Call site: web/battle.js when the battle sequence ends.
-- Records today's Toronto date and the practice-task count already finished,
-- so the hub power bar starts again at zero.

CREATE OR REPLACE FUNCTION af_web_finish_battle(p_profile text, p_practice_done int)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_profile text := upper(trim(p_profile));
BEGIN
  IF v_profile IS NULL OR v_profile = '' OR v_profile NOT IN ('AM', 'BM', 'TE') THEN
    RAISE EXCEPTION 'Invalid profile: %', p_profile;
  END IF;

  UPDATE user_data
  SET
    web_battle_day = (NOW() AT TIME ZONE 'America/Toronto')::date,
    web_battle_practice_baseline = GREATEST(COALESCE(p_practice_done, 0), 0),
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = v_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_web_finish_battle(text, int) TO anon, authenticated, service_role;
