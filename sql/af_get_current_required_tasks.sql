-- BaerenEd: Today's visible required tasks + checklist items from user_data.
-- One row per task (required + visible checklist), sorted by name.
--
-- Columns (real table columns for PostgREST / SELECT * — not one json blob):
--   task_name          — key in required_tasks or checklist_items JSON
--   completion_status  — 'complete' | 'incomplete'
--   berry_value        — stars for that task
--   mins_value         — af_stars_to_minutes(stars)
--
-- Call:
--   POST /rest/v1/rpc/af_get_current_required_tasks {"p_profile":"AM"}
--   Or: SELECT * FROM af_get_current_required_tasks('AM');

DROP FUNCTION IF EXISTS af_get_current_required_tasks(text);

CREATE OR REPLACE FUNCTION af_get_current_required_tasks(p_profile text)
RETURNS TABLE (
  task_name text,
  completion_status text,
  berry_value int,
  mins_value int
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
      e.key AS item_name,
      coalesce(e.value->>'status', 'incomplete') AS status_text,
      coalesce((e.value->>'stars')::int, 0) AS stars
    FROM params p
    CROSS JOIN jsonb_each(p.v_required) AS e(key, value)
    WHERE
      NOT (
        nullif(trim(coalesce(e.value->>'disable', '')), '') IS NOT NULL
        AND to_date(e.value->>'disable', 'Mon DD, YYYY') IS NOT NULL
        AND p.v_today_date < to_date(e.value->>'disable', 'Mon DD, YYYY')
      )
      AND NOT EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(coalesce(e.value->>'hidedays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = p.v_today_short
      )
      AND (
        nullif(trim(coalesce(e.value->>'displayDays', '')), '') IS NULL
        OR EXISTS (
          SELECT 1
          FROM unnest(string_to_array(lower(replace(coalesce(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
          WHERE d.day_token = p.v_today_short
        )
      )
      AND (
        nullif(trim(coalesce(e.value->>'displayDays', '')), '') IS NOT NULL
        OR nullif(trim(coalesce(e.value->>'showdays', '')), '') IS NULL
        OR EXISTS (
          SELECT 1
          FROM unnest(string_to_array(lower(replace(coalesce(e.value->>'showdays', ''), ' ', '')), ',')) AS d(day_token)
          WHERE d.day_token = p.v_today_short
        )
      )
  ),
  visible_checklist AS (
    SELECT
      e.key AS item_name,
      CASE WHEN coalesce((e.value->>'done')::boolean, false) THEN 'complete' ELSE 'incomplete' END AS status_text,
      coalesce((e.value->>'stars')::int, 0) AS stars
    FROM params p
    CROSS JOIN jsonb_each(p.v_checklist) AS e(key, value)
    WHERE
      nullif(trim(coalesce(e.value->>'displayDays', '')), '') IS NULL
      OR EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(coalesce(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = p.v_today_short
      )
  ),
  combined AS (
    SELECT item_name, status_text, stars FROM visible_required
    UNION ALL
    SELECT item_name, status_text, stars FROM visible_checklist
  )
  SELECT
    c.item_name::text AS task_name,
    c.status_text::text AS completion_status,
    c.stars::int AS berry_value,
    af_stars_to_minutes(c.stars)::int AS mins_value
  FROM combined c
  ORDER BY c.item_name;
$$;

GRANT EXECUTE ON FUNCTION af_get_current_required_tasks(text) TO anon, authenticated, service_role;
