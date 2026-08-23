-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetPhotoChoreTasksRows.
--   app/src/main/java/com/talq2me/baerened/TrainerMapTaskMerge.kt  -  prepareFromDbStrict.

-- BaerenEd: Today's visible photo-chore rows from user_data.photo_chores (trainer map).
-- Same day filters as af_get_tasks_required (disable / hidedays / displayDays / showdays).
-- POST /rest/v1/rpc/af_get_tasks_photo_chores {"p_profile":"AM"}

CREATE OR REPLACE FUNCTION af_get_tasks_photo_chores(p_profile text)
RETURNS TABLE (
  task_name text,
  chore_id text,
  description text,
  reward_cash numeric,
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
  easy boolean,
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
      COALESCE(ud.photo_chores, '{}'::jsonb) AS v_chores,
      lower(to_char((now() AT TIME ZONE 'America/Toronto'), 'Dy')) AS v_today_short,
      (now() AT TIME ZONE 'America/Toronto')::date AS v_today_date
    FROM (SELECT 1) AS _one
    LEFT JOIN user_data ud ON ud.profile = p_profile
  ),
  visible AS (
    SELECT
      COALESCE(NULLIF(TRIM(e.value->>'title'), ''), e.key::text) AS task_name,
      e.key::text AS chore_id,
      (e.value->>'description')::text AS description,
      COALESCE((e.value->>'rewardCash')::numeric, 0) AS reward_cash,
      COALESCE(e.value->>'status', 'incomplete')::text AS completion_status,
      COALESCE(NULLIF(TRIM(e.value->>'launch'), ''), 'chorePhoto') AS launch,
      NULLIF(TRIM(e.value->>'url'), '') AS url,
      CASE
        WHEN jsonb_typeof(e.value->'webGame') = 'boolean' THEN (e.value->>'webGame')::boolean
        WHEN lower(COALESCE(e.value->>'webGame', '')) IN ('true', '1') THEN true
        ELSE false
      END AS web_game
    FROM params p
    CROSS JOIN jsonb_each(p.v_chores) AS e(key, value)
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
  SELECT
    v.task_name,
    v.chore_id,
    v.description,
    v.reward_cash,
    v.completion_status,
    0 AS berry_value,
    0 AS mins_value,
    v.launch,
    v.url,
    v.web_game,
    false AS chrome_page,
    NULL::text AS video_sequence,
    NULL::text AS playlist_id,
    NULL::int AS total_questions,
    NULL::text AS reward_id,
    false AS easy,
    NULL::text AS easydays,
    NULL::text AS harddays,
    NULL::text AS extremedays,
    false AS block_outlines,
    false AS is_checklist
  FROM visible v
  ORDER BY v.task_name;
$$;

GRANT EXECUTE ON FUNCTION af_get_tasks_photo_chores(text) TO anon, authenticated, service_role;
