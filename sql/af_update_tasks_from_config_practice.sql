-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpdatePracticeTasksFromConfig.
--   app/src/main/java/com/talq2me/baerened/DbProfileSessionLoader.kt  -  chained after profile load / config refresh.
--
-- After merging GitHub optional tasks into practice_tasks: if every task **visible today** is already
-- complete (same visibility + completion rules as af_get_tasks_practice), set completed=false on all
-- merged keys so the round can repeat. Matches the post-completion reset in af_update_tasks_practice and
-- fixes stuck maps when that RPC never ran (e.g. old DB function) — training map load always runs this merge.

CREATE OR REPLACE FUNCTION af_update_tasks_from_config_practice(p_profile text, p_config_json jsonb DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  github_url text := 'https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/config/' || p_profile || '_config.json';
  config_json jsonb;
  http_status int;
  existing_practice jsonb;
  merged_practice jsonb;
BEGIN
  IF p_config_json IS NOT NULL AND p_config_json != 'null'::jsonb THEN
    config_json := p_config_json;
  ELSE
    SELECT r.status, r.content::jsonb INTO http_status, config_json FROM http_get(github_url) r LIMIT 1;
    IF http_status != 200 OR config_json IS NULL THEN
      RAISE WARNING 'af_update_tasks_from_config_practice: failed to fetch config for %', p_profile;
      RETURN;
    END IF;
  END IF;

  SELECT COALESCE(practice_tasks, '{}'::jsonb) INTO existing_practice FROM user_data WHERE profile = p_profile;

  SELECT COALESCE(
    (
      SELECT jsonb_object_agg(
        t->>'title',
        jsonb_build_object(
          'times_completed', COALESCE((existing_practice->(t->>'title'))->>'times_completed', '0')::int,
          'completed', COALESCE(existing_practice->(t->>'title')->'completed', to_jsonb(false)),
          'correct', existing_practice->(t->>'title')->'correct',
          'incorrect', existing_practice->(t->>'title')->'incorrect',
          'questions_answered', existing_practice->(t->>'title')->'questions_answered',
          'stars', t->'stars',
          'launch', t->'launch',
          'url', t->'url',
          'webGame', t->'webGame',
          'chromePage', t->'chromePage',
          'videoSequence', t->'videoSequence',
          'video', t->'video',
          'playlistId', t->'playlistId',
          'blockOutlines', t->'blockOutlines',
          'rewardId', t->'rewardId',
          'totalQuestions', t->'totalQuestions',
          'easy', t->'easy',
          'easydays', t->'easydays',
          'harddays', t->'harddays',
          'extremedays', t->'extremedays',
          'showdays', t->'showdays',
          'hidedays', t->'hidedays',
          'displayDays', t->'displayDays',
          'disable', t->'disable'
        )
      )
      FROM jsonb_array_elements(config_json->'sections') AS sec,
           jsonb_array_elements(COALESCE(sec->'tasks', '[]'::jsonb)) AS t
      WHERE sec->>'id' = 'optional'
    ),
    '{}'::jsonb
  ) INTO merged_practice;

  -- Visible-today round reset (aligned with af_get_tasks_practice + af_update_tasks_practice).
  IF EXISTS (SELECT 1 FROM jsonb_each(merged_practice))
     AND (
       WITH p AS (
         SELECT
           merged_practice AS v_tasks,
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
    INTO merged_practice
    FROM jsonb_each(merged_practice) AS e;
  END IF;

  UPDATE user_data SET
    practice_tasks = merged_practice,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_from_config_practice(text, jsonb) TO anon, authenticated, service_role;
