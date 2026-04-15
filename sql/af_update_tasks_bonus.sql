-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAfUpdateBonusTask; DailyProgressManager (SingleItemUpdate.BonusTask).

-- BaerenEd: Update a single bonus task by title. Same semantics as af_update_tasks_practice but for bonus_tasks column.
-- Call: POST /rest/v1/rpc/af_update_tasks_bonus with body e.g.
--   {"p_profile": "TE", "p_task_title": "Bonus Game", "p_times_completed": 1, "p_stars": 2, "p_correct": 5, "p_incorrect": 0, "p_questions_answered": 5}

CREATE OR REPLACE FUNCTION af_update_tasks_bonus(
  p_profile text,
  p_task_title text,
  p_times_completed int DEFAULT NULL,
  p_stars int DEFAULT NULL,
  p_correct int DEFAULT NULL,
  p_incorrect int DEFAULT NULL,
  p_questions_answered int DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  cur jsonb;
  existing jsonb;
  new_task jsonb;
  old_tc int;
  new_tc int;
  delta int;
  add_berries int := 0;
  add_mins int := 0;
BEGIN
  SELECT COALESCE(bonus_tasks, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;
  existing := cur->p_task_title;
  old_tc := COALESCE((existing->>'times_completed')::int, 0);
  -- DB-owned increment: when app omits p_times_completed, treat completion RPC as +1.
  new_tc := CASE WHEN p_times_completed IS NOT NULL THEN p_times_completed ELSE old_tc + 1 END;

  new_task := COALESCE(existing, '{}'::jsonb)
    || jsonb_build_object(
      'times_completed', to_jsonb(new_tc),
      -- DB-owned accumulation for completion event metrics.
      'correct', CASE WHEN p_correct IS NOT NULL THEN to_jsonb(COALESCE((existing->>'correct')::int, 0) + p_correct) ELSE existing->'correct' END,
      'incorrect', CASE WHEN p_incorrect IS NOT NULL THEN to_jsonb(COALESCE((existing->>'incorrect')::int, 0) + p_incorrect) ELSE existing->'incorrect' END,
      'questions_answered', CASE WHEN p_questions_answered IS NOT NULL THEN to_jsonb(COALESCE((existing->>'questions_answered')::int, 0) + p_questions_answered) ELSE existing->'questions_answered' END
    );
  new_task := new_task || (COALESCE(existing, '{}'::jsonb) - 'times_completed' - 'correct' - 'incorrect' - 'questions_answered');

  IF p_stars IS NOT NULL AND p_stars > 0 AND new_tc > old_tc THEN
    delta := new_tc - old_tc;
    add_berries := delta * p_stars;
    add_mins := delta * af_get_stars_to_minutes(p_stars);
  END IF;

  UPDATE user_data
  SET
    bonus_tasks = jsonb_set(cur, ARRAY[p_task_title], new_task, true),
    berries_earned = COALESCE(berries_earned, 0) + add_berries,
    banked_mins = COALESCE(banked_mins, 0) + add_mins,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_bonus(text, text, int, int, int, int, int) TO anon, authenticated, service_role;
