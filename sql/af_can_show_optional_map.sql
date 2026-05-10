-- Deploy to Supabase from this repo only; the Android app invokes PostgREST RPCs on Supabase.
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfCanShowOptionalMap.
--   app/src/main/java/com/talq2me/baerened/MainActivity.kt  -  optional training map gate.
--
-- BaerenEd: gate for showing the Extra Practice (optional) map. Returns true iff every visible required task
-- (per af_get_tasks_required, excluding checklist rows) is currently 'complete' / 'done'. With no visible required
-- tasks, returns false (no implicit unlock).

DROP FUNCTION IF EXISTS af_can_show_optional_map(text);

CREATE OR REPLACE FUNCTION af_can_show_optional_map(p_profile text)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  WITH rows AS (
    SELECT lower(t.completion_status) AS s
    FROM af_get_tasks_required(p_profile) AS t
    WHERE NOT t.is_checklist
  )
  SELECT
    EXISTS (SELECT 1 FROM rows)
    AND NOT EXISTS (
      SELECT 1 FROM rows
      WHERE s NOT IN ('complete', 'done')
    );
$$;

GRANT EXECUTE ON FUNCTION af_can_show_optional_map(text) TO anon, authenticated, service_role;
