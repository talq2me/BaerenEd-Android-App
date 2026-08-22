-- Call sites (BaerenEd reports, this repo):
--   reports/daily_progress_report.html  -  parent Yes / edit amount then Yes.

-- BaerenEd: Credit kid_bank_balance for one chore photo (image_uploads.task key).
-- Idempotent: a second grant for the same profile+task_key does not add cash again.
-- POST /rest/v1/rpc/af_grant_chore_reward
--   {"p_profile":"AM","p_task_key":"chore_unload_dishwasher_2026-08-19","p_amount":2.00}

CREATE OR REPLACE FUNCTION af_grant_chore_reward(
  p_profile text,
  p_task_key text,
  p_amount numeric
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  img image_uploads%ROWTYPE;
  add_amount numeric;
  new_balance numeric;
BEGIN
  IF NULLIF(TRIM(COALESCE(p_profile, '')), '') IS NULL THEN
    RAISE EXCEPTION 'af_grant_chore_reward: p_profile is required';
  END IF;
  IF NULLIF(TRIM(COALESCE(p_task_key, '')), '') IS NULL THEN
    RAISE EXCEPTION 'af_grant_chore_reward: p_task_key is required';
  END IF;
  IF p_amount IS NULL OR p_amount < 0 THEN
    RAISE EXCEPTION 'af_grant_chore_reward: p_amount must be >= 0';
  END IF;

  add_amount := ROUND(p_amount, 2);

  SELECT * INTO img
  FROM image_uploads
  WHERE profile = p_profile AND task = p_task_key
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'af_grant_chore_reward: no image for profile % task %', p_profile, p_task_key;
  END IF;

  IF COALESCE(img.reward_granted, false) THEN
    SELECT COALESCE(kid_bank_balance, 0) INTO new_balance FROM user_data WHERE profile = p_profile;
    RETURN jsonb_build_object(
      'granted', false,
      'already_granted', true,
      'granted_amount', img.granted_amount,
      'kid_bank_balance', COALESCE(new_balance, 0)
    );
  END IF;

  UPDATE user_data
  SET
    kid_bank_balance = ROUND(COALESCE(kid_bank_balance, 0) + add_amount, 2),
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile
  RETURNING COALESCE(kid_bank_balance, 0) INTO new_balance;

  IF new_balance IS NULL THEN
    RAISE EXCEPTION 'af_grant_chore_reward: unknown profile %', p_profile;
  END IF;

  UPDATE image_uploads
  SET
    reward_granted = true,
    granted_amount = add_amount
  WHERE profile = p_profile AND task = p_task_key;

  RETURN jsonb_build_object(
    'granted', true,
    'already_granted', false,
    'granted_amount', add_amount,
    'kid_bank_balance', new_balance
  );
END;
$$;

GRANT EXECUTE ON FUNCTION af_grant_chore_reward(text, text, numeric) TO anon, authenticated, service_role;
