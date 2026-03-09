-- BaerenEd: Update a single chore by chore_id. Sets done for that chore in the chores JSONB array.
-- When p_done is true and chore was not done, adds chore's coins_reward (from DB) to coins_earned.
-- Call: POST /rest/v1/rpc/af_update_chore with body e.g.
--   {"p_profile": "TE", "p_chore_id": 1, "p_done": true}

CREATE OR REPLACE FUNCTION af_update_chore(
  p_profile text,
  p_chore_id int,
  p_done boolean
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  cur jsonb;
  new_chores jsonb;
  old_done boolean;
  chore_coins int;
  add_coins int := 0;
BEGIN
  SELECT COALESCE(chores, '[]'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;

  SELECT (e->>'done')::boolean, COALESCE((e->>'coins_reward')::int, 0)
    INTO old_done, chore_coins
  FROM jsonb_array_elements(cur) e
  WHERE (e->>'chore_id')::int = p_chore_id
  LIMIT 1;
  old_done := COALESCE(old_done, false);

  IF p_done AND NOT old_done AND chore_coins > 0 THEN
    add_coins := chore_coins;
  END IF;

  new_chores := (
    SELECT jsonb_agg(
      CASE WHEN (elem->>'chore_id')::int = p_chore_id THEN
        elem || jsonb_build_object('done', p_done)
      ELSE
        elem
      END
      ORDER BY ord
    )
    FROM jsonb_array_elements(cur) WITH ORDINALITY AS t(elem, ord)
  );

  UPDATE user_data
  SET
    chores = COALESCE(new_chores, '[]'::jsonb),
    coins_earned = COALESCE(coins_earned, 0) + add_coins,
    last_updated = (NOW() AT TIME ZONE 'America/New_York')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_chore(text, int, boolean) TO anon, authenticated, service_role;
