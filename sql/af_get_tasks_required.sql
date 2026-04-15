-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetRequiredTasksRows (invokeRpcProfileReturningJsonArray "af_get_tasks_required").
--   app/src/main/java/com/talq2me/baerened/TrainerMapTaskMerge.kt  -  prepareFromDbStrict.

-- BaerenEd: Required + checklist task rows from user_data JSON (trainer map).
-- POST /rest/v1/rpc/af_get_tasks_required {"p_profile":"AM"}

DROP FUNCTION IF EXISTS af_get_tasks_required_v2(text);
DROP FUNCTION IF EXISTS af_get_tasks_required(text);

CREATE OR REPLACE FUNCTION af_get_tasks_required(p_profile text)
RETURNS TABLE (
  task_name text,
  completion_status text,
  berry_value int,
  mins_value int,
  launch text,
  url text,
  web_game boolean,
  chrome_page boolean,
  video_sequence text,
  playlist_id text,
  total_questions int,
  reward_id text,
  easydays text,
  harddays text,
  extremedays text,
  block_outlines boolean,
  is_checklist boolean
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  WITH params AS (
    SELECT
      COALESCE(ud.required_tasks, '{}'::jsonb) AS v_required,
      COALESCE(ud.checklist_items, '{}'::jsonb) AS v_checklist,
      lower(to_char((now() AT TIME ZONE 'America/Toronto'), 'Dy')) AS v_today_short,
      (now() AT TIME ZONE 'America/Toronto')::date AS v_today_date
    FROM (SELECT 1) AS _one
    LEFT JOIN user_data ud ON ud.profile = p_profile
  ),
  visible_required AS (
    SELECT
      e.key::text AS task_name,
      COALESCE(e.value->>'status', 'incomplete')::text AS completion_status,
      COALESCE((e.value->>'stars')::int, 0) AS berry_value,
      (e.value->>'launch')::text AS launch,
      (e.value->>'url')::text AS url,
      COALESCE((e.value->>'webGame')::boolean, false) AS web_game,
      COALESCE((e.value->>'chromePage')::boolean, false) AS chrome_page,
      (e.value->>'videoSequence')::text AS video_sequence,
      (e.value->>'playlistId')::text AS playlist_id,
      (e.value->>'totalQuestions')::int AS total_questions,
      (e.value->>'rewardId')::text AS reward_id,
      (e.value->>'easydays')::text AS easydays,
      (e.value->>'harddays')::text AS harddays,
      (e.value->>'extremedays')::text AS extremedays,
      COALESCE((e.value->>'blockOutlines')::boolean, false) AS block_outlines,
      false AS is_checklist
    FROM params p
    CROSS JOIN jsonb_each(p.v_required) AS e(key, value)
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
  ),
  visible_checklist AS (
    SELECT
      e.key::text AS task_name,
      CASE WHEN COALESCE((e.value->>'done')::boolean, false) THEN 'complete' ELSE 'incomplete' END AS completion_status,
      COALESCE((e.value->>'stars')::int, 0) AS berry_value,
      COALESCE((e.value->>'launch')::text, 'checklist_' || COALESCE(NULLIF(e.value->>'id', ''), e.key)) AS launch,
      NULL::text AS url,
      false AS web_game,
      false AS chrome_page,
      NULL::text AS video_sequence,
      NULL::text AS playlist_id,
      NULL::int AS total_questions,
      NULL::text AS reward_id,
      NULL::text AS easydays,
      NULL::text AS harddays,
      NULL::text AS extremedays,
      false AS block_outlines,
      true AS is_checklist
    FROM params p
    CROSS JOIN jsonb_each(p.v_checklist) AS e(key, value)
    WHERE
      NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NULL
      OR EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = p.v_today_short
      )
  )
  SELECT
    v.task_name,
    v.completion_status,
    v.berry_value,
    af_get_stars_to_minutes(v.berry_value)::int AS mins_value,
    v.launch,
    v.url,
    v.web_game,
    v.chrome_page,
    v.video_sequence,
    v.playlist_id,
    v.total_questions,
    v.reward_id,
    v.easydays,
    v.harddays,
    v.extremedays,
    v.block_outlines,
    v.is_checklist
  FROM (
    SELECT * FROM visible_required
    UNION ALL
    SELECT * FROM visible_checklist
  ) v
  ORDER BY v.task_name;
$$;

GRANT EXECUTE ON FUNCTION af_get_tasks_required(text) TO anon, authenticated, service_role;
