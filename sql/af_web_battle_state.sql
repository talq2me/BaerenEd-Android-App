-- Call site: web/index.html loadHub.
-- Whether this profile already finished a battle today, and how many practice tasks
-- were done at that moment. The web hub uses the difference to refill the power bar.

CREATE OR REPLACE FUNCTION af_web_battle_state(p_profile text)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_profile text := upper(trim(p_profile));
  v_day date;
  v_baseline int;
BEGIN
  IF v_profile IS NULL OR v_profile = '' OR v_profile NOT IN ('AM', 'BM', 'TE') THEN
    RAISE EXCEPTION 'Invalid profile: %', p_profile;
  END IF;

  SELECT web_battle_day, COALESCE(web_battle_practice_baseline, 0)
    INTO v_day, v_baseline
  FROM user_data
  WHERE profile = v_profile;

  RETURN jsonb_build_object(
    'battleToday', v_day IS NOT NULL AND v_day = (NOW() AT TIME ZONE 'America/Toronto')::date,
    'practiceBaseline', COALESCE(v_baseline, 0)
  );
END;
$$;

GRANT EXECUTE ON FUNCTION af_web_battle_state(text) TO anon, authenticated, service_role;
