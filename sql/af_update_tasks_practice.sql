-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAfUpdatePracticeTask; DailyProgressManager (SingleItemUpdate.PracticeTask).

-- BaerenEd: Update a single practice task by title. Only non-null parameters are applied.
-- When p_times_completed increases and p_stars is provided, adds (delta * p_stars) to berries_earned and (delta * stars_to_minutes) to banked_mins.
-- After updating, if all practice (optional) tasks are complete, resets all practice tasks to incomplete (times_completed 0, etc.)
-- so the app can show them as doable again without any app-side state. Requires http extension for config fetch.
-- Call: POST /rest/v1/rpc/af_update_tasks_practice with body e.g.
--   {"p_profile": "TE", "p_task_title": "Time Telling", "p_times_completed": 2, "p_stars": 3, "p_correct": 10, "p_incorrect": 0, "p_questions_answered": 10}

CREATE OR REPLACE FUNCTION af_update_tasks_practice(
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
SET search_path = public, extensions
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
  updated_practice jsonb;
  config_json jsonb;
  http_status int;
  github_url text;
  optional_titles text[];
  all_complete boolean;
  reset_practice jsonb;
  tit text;
BEGIN
  SELECT COALESCE(practice_tasks, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;
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

  updated_practice := jsonb_set(cur, ARRAY[p_task_title], new_task, true);

  IF p_stars IS NOT NULL AND p_stars > 0 AND new_tc > old_tc THEN
    delta := new_tc - old_tc;
    add_berries := delta * p_stars;
    add_mins := delta * af_get_stars_to_minutes(p_stars);
  END IF;

  -- If all visible practice (optional) tasks are complete, reset all to incomplete so app shows them doable again
  github_url := 'https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/' || p_profile || '_config.json';
  SELECT r.status, r.content::jsonb INTO http_status, config_json FROM http_get(github_url) r LIMIT 1;
  IF http_status = 200 AND config_json IS NOT NULL THEN
    SELECT array_agg(t->>'title')
      INTO optional_titles
      FROM jsonb_array_elements(config_json->'sections') AS sec,
           jsonb_array_elements(COALESCE(sec->'tasks', '[]'::jsonb)) AS t
      WHERE sec->>'id' = 'optional'
        AND t->>'title' IS NOT NULL AND t->>'title' != '';
    IF optional_titles IS NOT NULL AND array_length(optional_titles, 1) > 0 THEN
      all_complete := true;
      FOREACH tit IN ARRAY optional_titles
      LOOP
        IF COALESCE((updated_practice->tit->>'times_completed')::int, 0) <= 0 THEN
          all_complete := false;
          EXIT;
        END IF;
      END LOOP;
      IF all_complete THEN
        SELECT jsonb_object_agg(
          t->>'title',
          jsonb_build_object(
            'times_completed', 0,
            'correct', 0,
            'incorrect', 0,
            'questions_answered', 0,
            'showdays', t->'showdays',
            'hidedays', t->'hidedays',
            'displayDays', t->'displayDays',
            'disable', t->'disable'
          )
        )
        INTO reset_practice
        FROM jsonb_array_elements(config_json->'sections') AS sec,
             jsonb_array_elements(COALESCE(sec->'tasks', '[]'::jsonb)) AS t
        WHERE sec->>'id' = 'optional' AND t->>'title' IS NOT NULL AND t->>'title' != '';
        IF reset_practice IS NOT NULL THEN
          updated_practice := reset_practice;
        END IF;
      END IF;
    END IF;
  END IF;

  UPDATE user_data
  SET
    practice_tasks = updated_practice,
    berries_earned = COALESCE(berries_earned, 0) + add_berries,
    banked_mins = COALESCE(banked_mins, 0) + add_mins,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_practice(text, text, int, int, int, int, int) TO anon, authenticated, service_role;
