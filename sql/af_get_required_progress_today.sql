-- Deploy to Supabase from this repo only; the Android app invokes PostgREST RPCs on Supabase.
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetRequiredProgressToday.
--   app/src/main/java/com/talq2me/baerened/BattleHubActivity.kt  -  Earn Extra Berries / Daily Spin gate; battle-end snapshot.
--
-- BaerenEd: combined "today's required progress" needed by Battle Hub.
--   all_done       : true iff every visible-today required task AND every visible-today checklist item with stars>0 is complete/done.
--                    Special case (matches legacy DailyProgressManager): if nothing is visible today, returns true only when both
--                    required_tasks and checklist_items are literally empty in user_data (kid has nothing to do at all).
--   earned_berries : sum of berry_value for the visible rows that are currently complete/done. Used to snapshot/compare across battles.

DROP FUNCTION IF EXISTS af_get_required_progress_today(text);

CREATE OR REPLACE FUNCTION af_get_required_progress_today(p_profile text)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  WITH
    raw AS (
      SELECT
        COALESCE(required_tasks, '{}'::jsonb)   AS rt,
        COALESCE(checklist_items, '{}'::jsonb)  AS ci
      FROM user_data
      WHERE profile = p_profile
    ),
    -- af_get_tasks_required already applies today's day-rules (showdays/hidedays/displayDays/disable).
    -- We additionally drop checklist rows with stars<=0 to match the legacy "stars > 0" filter.
    rows AS (
      SELECT
        lower(t.completion_status) AS s,
        COALESCE(t.berry_value, 0) AS b
      FROM af_get_tasks_required(p_profile) AS t
      WHERE NOT t.is_checklist OR COALESCE(t.berry_value, 0) > 0
    )
  SELECT jsonb_build_object(
    'all_done',
    CASE
      WHEN EXISTS (SELECT 1 FROM rows) THEN
        NOT EXISTS (SELECT 1 FROM rows WHERE s NOT IN ('complete', 'done'))
      ELSE
        COALESCE((SELECT (rt = '{}'::jsonb AND ci = '{}'::jsonb) FROM raw), false)
    END,
    'earned_berries',
    COALESCE((SELECT SUM(b) FROM rows WHERE s IN ('complete', 'done')), 0)::int
  );
$$;

GRANT EXECUTE ON FUNCTION af_get_required_progress_today(text) TO anon, authenticated, service_role;
