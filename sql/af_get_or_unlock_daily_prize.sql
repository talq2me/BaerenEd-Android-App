-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetOrUnlockDailyPrize.
--   app/src/main/java/com/talq2me/baerened/RewardSpinnerActivity.kt  -  resolve/open daily prize.

-- If prize_unlocked already exists for the profile, return it.
-- Otherwise, if required/checklist work is complete for the day (berries_earned >= possible_stars),
-- pick a weighted random reward from reward_spinner, persist user_data.prize_unlocked, and return it.
CREATE OR REPLACE FUNCTION af_get_or_unlock_daily_prize(p_profile text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_possible_stars int := 0;
  v_berries_earned int := 0;
  v_prize_unlocked text := NULL;
  v_reward_name text := NULL;
BEGIN
  SELECT
    COALESCE(ud.possible_stars, 0),
    COALESCE(ud.berries_earned, 0),
    NULLIF(trim(ud.prize_unlocked), '')
  INTO v_possible_stars, v_berries_earned, v_prize_unlocked
  FROM user_data ud
  WHERE ud.profile = p_profile
  FOR UPDATE;

  IF NOT FOUND THEN
    RETURN jsonb_build_object(
      'prize_unlocked', NULL,
      'newly_unlocked', false,
      'eligible', false
    );
  END IF;

  IF v_prize_unlocked IS NOT NULL THEN
    RETURN jsonb_build_object(
      'prize_unlocked', v_prize_unlocked,
      'newly_unlocked', false,
      'eligible', true
    );
  END IF;

  IF v_possible_stars <= 0 OR v_berries_earned < v_possible_stars THEN
    RETURN jsonb_build_object(
      'prize_unlocked', NULL,
      'newly_unlocked', false,
      'eligible', false
    );
  END IF;

  WITH weighted AS (
    SELECT
      rs.id,
      rs.name,
      rs.percent,
      SUM(rs.percent) OVER (ORDER BY rs.id) AS cumulative_weight
    FROM reward_spinner rs
    WHERE rs.percent > 0
  ),
  total AS (
    SELECT MAX(cumulative_weight) AS total_weight
    FROM weighted
  ),
  roll AS (
    SELECT (FLOOR(random() * total_weight) + 1)::int AS ticket
    FROM total
  )
  SELECT w.name
  INTO v_reward_name
  FROM weighted w, roll r
  WHERE w.cumulative_weight >= r.ticket
  ORDER BY w.cumulative_weight
  LIMIT 1;

  IF v_reward_name IS NULL THEN
    RETURN jsonb_build_object(
      'prize_unlocked', NULL,
      'newly_unlocked', false,
      'eligible', false,
      'error', 'No reward spinner rows with positive percent.'
    );
  END IF;

  UPDATE user_data
  SET
    prize_unlocked = v_reward_name,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;

  RETURN jsonb_build_object(
    'prize_unlocked', v_reward_name,
    'newly_unlocked', true,
    'eligible', true
  );
END;
$$;

GRANT EXECUTE ON FUNCTION af_get_or_unlock_daily_prize(text) TO anon, authenticated, service_role;
