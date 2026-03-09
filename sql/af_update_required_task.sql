-- BaerenEd: Update a single required task by title. Only non-null parameters are applied; others preserve existing values.
-- When status is set to 'complete', adds the task's stars (from DB) to berries_earned and stars-to-minutes to banked_mins.
-- Requires af_stars_to_minutes (af_stars_to_minutes.sql). Identifies task by p_task_title (key in required_tasks JSONB).
-- Call: POST /rest/v1/rpc/af_update_required_task with body e.g.
--   {"p_profile": "TE", "p_task_title": "Math", "p_status": "complete", "p_correct": 5, "p_incorrect": 1, "p_questions": 6}

CREATE OR REPLACE FUNCTION af_update_required_task(
  p_profile text,
  p_task_title text,
  p_status text DEFAULT NULL,
  p_correct int DEFAULT NULL,
  p_incorrect int DEFAULT NULL,
  p_questions int DEFAULT NULL
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
  old_status text;
  new_status text;
  task_stars int;
  add_berries int := 0;
  add_mins int := 0;
BEGIN
  SELECT COALESCE(required_tasks, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;

  existing := cur->p_task_title;
  old_status := existing->>'status';
  new_status := COALESCE(p_status, old_status, 'incomplete');

  new_task := COALESCE(existing, '{}'::jsonb)
    || jsonb_build_object(
      'status', new_status,
      'correct', CASE WHEN p_correct IS NOT NULL THEN to_jsonb(p_correct) ELSE existing->'correct' END,
      'incorrect', CASE WHEN p_incorrect IS NOT NULL THEN to_jsonb(p_incorrect) ELSE existing->'incorrect' END,
      'questions', CASE WHEN p_questions IS NOT NULL THEN to_jsonb(p_questions) ELSE existing->'questions' END
    );
  new_task := new_task || (COALESCE(existing, '{}'::jsonb) - 'status' - 'correct' - 'incorrect' - 'questions');

  -- When transitioning to complete, add task's stars to berries_earned and to banked_mins (via af_stars_to_minutes)
  IF new_status = 'complete' AND (old_status IS NULL OR old_status != 'complete') THEN
    task_stars := COALESCE((new_task->>'stars')::int, 0);
    IF task_stars > 0 THEN
      add_berries := task_stars;
      add_mins := af_stars_to_minutes(task_stars);
    END IF;
  END IF;

  UPDATE user_data
  SET
    required_tasks = jsonb_set(cur, ARRAY[p_task_title], new_task, true),
    berries_earned = COALESCE(berries_earned, 0) + add_berries,
    banked_mins = COALESCE(banked_mins, 0) + add_mins,
    last_updated = (NOW() AT TIME ZONE 'America/New_York')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_required_task(text, text, text, int, int, int) TO anon, authenticated, service_role;
