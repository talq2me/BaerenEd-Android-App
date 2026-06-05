-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpdateTaskCompletion.
--   app/src/main/java/com/talq2me/baerened/DailyProgressManager.kt  -  markTaskCompletedWithName.
--
-- Deploy: DROP legacy `af_update_task_completion(text,text,int,int,int,int)` below if it still exists (PostgREST
-- mismatch / unknown-arg errors). Clients should send `p_section_id` as a JSON **string** (e.g. "optional"), never
-- JSON null, so Postgres binds it as text.
--
-- BaerenEd: Unified completion RPC for dumb UI (invoke only on Supabase; SQL is maintained in this repo).
-- Routes to required / practice / bonus updaters and returns earned stars from DB rules. NO FALLBACKS:
-- this RPC calls the canonical functions only; if one is missing the call fails so the bug is visible.
-- Practice (optional): calls af_update_tasks_practice (increments counters, toggles "completed" when the full set is done; see that function).
--
-- PostgREST: if more than ONE overload exists, resolution often fails → 404 or "could not choose best candidate".
-- CREATE OR REPLACE only replaces ONE signature at a time, so orphans must be dropped explicitly.
DO $$
DECLARE
  r RECORD;
BEGIN
  FOR r IN (
    SELECT p.oid
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.proname = 'af_update_task_completion'
  ) LOOP
    EXECUTE format('DROP FUNCTION IF EXISTS %s CASCADE', r.oid::regprocedure);
  END LOOP;
END $$;

CREATE OR REPLACE FUNCTION af_update_task_completion(
  p_profile text,
  p_task_title text,
  p_section_id text DEFAULT NULL,
  p_stars int DEFAULT NULL,
  p_correct int DEFAULT NULL,
  p_incorrect int DEFAULT NULL,
  p_questions_answered int DEFAULT NULL
)
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  normalized_section text;
  required_old_status text;
  required_db_stars int;
  earned_stars int := 0;
BEGIN
  normalized_section := COALESCE(NULLIF(trim(lower(p_section_id)), ''), 'optional');

  IF normalized_section = 'required' THEN
    SELECT (required_tasks->p_task_title->>'status'),
           COALESCE((required_tasks->p_task_title->>'stars')::int, 0)
      INTO required_old_status, required_db_stars
      FROM user_data
      WHERE profile = p_profile;

    PERFORM af_update_tasks_required(
      p_profile,
      p_task_title,
      'complete',
      p_correct,
      p_incorrect,
      p_questions_answered
    );

    IF COALESCE(required_old_status, 'incomplete') <> 'complete' THEN
      earned_stars := COALESCE(p_stars, required_db_stars, 0);
    END IF;
  ELSIF normalized_section = 'bonus' THEN
    PERFORM af_update_tasks_bonus(
      p_profile,
      p_task_title,
      NULL,
      p_stars,
      p_correct,
      p_incorrect,
      p_questions_answered
    );
    earned_stars := GREATEST(COALESCE(p_stars, 0), 0);
  ELSE
    PERFORM af_update_tasks_practice(
      p_profile,
      p_task_title,
      NULL,
      p_stars,
      p_correct,
      p_incorrect,
      p_questions_answered
    );
    earned_stars := GREATEST(COALESCE(p_stars, 0), 0);
  END IF;

  IF normalized_section = 'required' THEN
    PERFORM af_maybe_advance_spelling_pools(p_profile);
  END IF;

  RETURN earned_stars;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_task_completion(text, text, text, int, int, int, int) TO anon, authenticated, service_role;
