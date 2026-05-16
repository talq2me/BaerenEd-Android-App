-- Deploy to Supabase from this repo only; the Android app invokes PostgREST RPCs on Supabase.
-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAfUpdatePracticeTask; DailyProgressManager (SingleItemUpdate.PracticeTask).
--   af_update_task_completion (optional section) delegates here.
--
-- On each practice task completion: increment times_completed, aggregate correct/incorrect/questions_answered,
-- set this task's "completed" to true, award berries/minutes when applicable.
--
-- Lifetime counters (times_completed, correct, incorrect, questions_answered) are never cleared here;
-- only daily reset clears user_data as a whole.
--
-- Repeatable Extra Practice map: when every task **visible today** (same filters as af_get_tasks_practice)
-- is complete (completed flag, else legacy times_completed > 0), set "completed" to false on all map
-- entries so the set can be run again. Hidden / wrong-day rows must not block reset. Counters unchanged.
--
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
  updated_practice jsonb;
  reset_practice jsonb;
BEGIN
  SELECT COALESCE(practice_tasks, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;
  existing := cur->p_task_title;
  old_tc := COALESCE((existing->>'times_completed')::int, 0);
  -- DB-owned increment: when app omits p_times_completed, treat completion RPC as +1.
  new_tc := CASE WHEN p_times_completed IS NOT NULL THEN p_times_completed ELSE old_tc + 1 END;

  new_task := COALESCE(existing, '{}'::jsonb)
    || jsonb_build_object(
      'times_completed', to_jsonb(new_tc),
      'completed', to_jsonb(true),
      -- DB-owned accumulation for completion event metrics.
      'correct', CASE WHEN p_correct IS NOT NULL THEN to_jsonb(COALESCE((existing->>'correct')::int, 0) + p_correct) ELSE existing->'correct' END,
      'incorrect', CASE WHEN p_incorrect IS NOT NULL THEN to_jsonb(COALESCE((existing->>'incorrect')::int, 0) + p_incorrect) ELSE existing->'incorrect' END,
      'questions_answered', CASE WHEN p_questions_answered IS NOT NULL THEN to_jsonb(COALESCE((existing->>'questions_answered')::int, 0) + p_questions_answered) ELSE existing->'questions_answered' END
    );
  new_task := new_task
    || (COALESCE(existing, '{}'::jsonb) - 'times_completed' - 'correct' - 'incorrect' - 'questions_answered' - 'completed');

  updated_practice := jsonb_set(cur, ARRAY[p_task_title], new_task, true);

  IF p_stars IS NOT NULL AND p_stars > 0 AND new_tc > old_tc THEN
    delta := new_tc - old_tc;
    add_berries := delta * p_stars;
    add_mins := delta * af_get_stars_to_minutes(p_stars);
  END IF;

  -- All *visible today* tasks complete (matches af_get_tasks_practice) → clear completed only (next pass).
  IF EXISTS (SELECT 1 FROM jsonb_each(updated_practice))
     AND (
       WITH p AS (
         SELECT
           updated_practice AS v_tasks,
           (now() AT TIME ZONE 'America/Toronto')::date AS v_today_date,
           lower(to_char((now() AT TIME ZONE 'America/Toronto'), 'Dy')) AS v_today_short
       ),
       vis AS (
         SELECT
           e.key,
           e.value,
           CASE
             WHEN (e.value ? 'completed') THEN COALESCE((e.value->>'completed')::boolean, false)
             ELSE COALESCE((e.value->>'times_completed')::int, 0) > 0
           END AS is_done
         FROM p
         CROSS JOIN jsonb_each(p.v_tasks) AS e(key, value)
         WHERE
           NOT (
             NULLIF(TRIM(COALESCE(e.value->>'disable', '')), '') IS NOT NULL
             AND to_date(e.value->>'disable', 'Mon DD, YYYY') IS NOT NULL
             AND p.v_today_date < to_date(e.value->>'disable', 'Mon DD, YYYY')
           )
           AND NOT EXISTS (
             SELECT 1
             FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'hidedays', ''), ' ', '')), ',')) AS d(day_token)
             WHERE d.day_token = p.v_today_short
           )
           AND (
             NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NULL
             OR EXISTS (
               SELECT 1
               FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
               WHERE d.day_token = p.v_today_short
             )
           )
           AND (
             NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NOT NULL
             OR NULLIF(TRIM(COALESCE(e.value->>'showdays', '')), '') IS NULL
             OR EXISTS (
               SELECT 1
               FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'showdays', ''), ' ', '')), ',')) AS d(day_token)
               WHERE d.day_token = p.v_today_short
             )
           )
       )
       SELECT (SELECT COUNT(*)::int FROM vis) > 0
              AND NOT EXISTS (SELECT 1 FROM vis WHERE NOT vis.is_done)
     )
  THEN
    SELECT jsonb_object_agg(
      e.key,
      e.value || jsonb_build_object('completed', false)
    )
    INTO reset_practice
    FROM jsonb_each(updated_practice) AS e;
    IF reset_practice IS NOT NULL THEN
      updated_practice := reset_practice;
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
