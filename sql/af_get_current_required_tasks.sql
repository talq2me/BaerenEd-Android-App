-- BaerenEd: Today's visible required tasks + checklist items from user_data.
-- One row per task (required + visible checklist), sorted by name.
--
-- Columns:
--   task_name          — key in required_tasks or checklist_items JSON
--   completion_status  — 'complete' | 'incomplete'
--   berry_value        — stars for that task (berries if completed toward goal)
--   mins_value         — screen/reward minutes for that task (via af_stars_to_minutes)
--
-- Call:
-- POST /rest/v1/rpc/af_get_current_required_tasks {"p_profile":"AM"}

DROP FUNCTION IF EXISTS af_get_current_required_tasks(text);

CREATE OR REPLACE FUNCTION af_get_current_required_tasks(p_profile text)
RETURNS TABLE (
  task_name text,
  completion_status text,
  berry_value int,
  mins_value int
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_required jsonb := '{}'::jsonb;
  v_checklist jsonb := '{}'::jsonb;
  v_today_short text := lower(to_char((NOW() AT TIME ZONE 'America/Toronto'), 'Dy'));
  v_today_date date := (NOW() AT TIME ZONE 'America/Toronto')::date;
BEGIN
  SELECT COALESCE(required_tasks, '{}'::jsonb), COALESCE(checklist_items, '{}'::jsonb)
  INTO v_required, v_checklist
  FROM user_data
  WHERE profile = p_profile;

  RETURN QUERY
  WITH visible_required AS (
    SELECT
      e.key AS item_name,
      COALESCE(e.value->>'status', 'incomplete') AS completion_status,
      COALESCE((e.value->>'stars')::int, 0) AS stars
    FROM jsonb_each(v_required) AS e(key, value)
    WHERE
      NOT (
        NULLIF(TRIM(COALESCE(e.value->>'disable', '')), '') IS NOT NULL
        AND to_date(e.value->>'disable', 'Mon DD, YYYY') IS NOT NULL
        AND v_today_date < to_date(e.value->>'disable', 'Mon DD, YYYY')
      )
      AND NOT EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'hidedays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = v_today_short
      )
      AND (
        NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NULL
        OR EXISTS (
          SELECT 1
          FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
          WHERE d.day_token = v_today_short
        )
      )
      AND (
        NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NOT NULL
        OR NULLIF(TRIM(COALESCE(e.value->>'showdays', '')), '') IS NULL
        OR EXISTS (
          SELECT 1
          FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'showdays', ''), ' ', '')), ',')) AS d(day_token)
          WHERE d.day_token = v_today_short
        )
      )
  ),
  visible_checklist AS (
    SELECT
      e.key AS item_name,
      CASE WHEN COALESCE((e.value->>'done')::boolean, false) THEN 'complete' ELSE 'incomplete' END AS completion_status,
      COALESCE((e.value->>'stars')::int, 0) AS stars
    FROM jsonb_each(v_checklist) AS e(key, value)
    WHERE
      NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NULL
      OR EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = v_today_short
      )
  ),
  combined AS (
    SELECT item_name, completion_status, stars FROM visible_required
    UNION ALL
    SELECT item_name, completion_status, stars FROM visible_checklist
  )
  SELECT
    c.item_name::text,
    c.completion_status::text,
    c.stars::int AS berry_value,
    af_stars_to_minutes(c.stars)::int AS mins_value
  FROM combined c
  ORDER BY c.item_name;
END;
$$;

GRANT EXECUTE ON FUNCTION af_get_current_required_tasks(text) TO anon, authenticated, service_role;
