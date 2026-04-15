-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpdateTaskCompletion.
--   app/src/main/java/com/talq2me/baerened/DailyProgressManager.kt  -  markTaskCompletedWithName.
--
-- BaerenEd: Unified completion RPC for dumb UI.
-- Routes to required/practice/bonus update RPCs and returns earned stars from DB rules.
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

    BEGIN
      PERFORM af_update_required_task(
        p_profile,
        p_task_title,
        'complete',
        p_correct,
        p_incorrect,
        p_questions_answered
      );
    EXCEPTION WHEN undefined_function THEN
      PERFORM af_update_tasks_required(
        p_profile,
        p_task_title,
        'complete',
        p_correct,
        p_incorrect,
        p_questions_answered
      );
    END;

    IF COALESCE(required_old_status, 'incomplete') <> 'complete' THEN
      earned_stars := COALESCE(p_stars, required_db_stars, 0);
    END IF;
  ELSIF normalized_section = 'bonus' THEN
    BEGIN
      PERFORM af_update_bonus_task(
        p_profile,
        p_task_title,
        NULL,
        p_stars,
        p_correct,
        p_incorrect,
        p_questions_answered
      );
    EXCEPTION WHEN undefined_function THEN
      PERFORM af_update_tasks_bonus(
        p_profile,
        p_task_title,
        NULL,
        p_stars,
        p_correct,
        p_incorrect,
        p_questions_answered
      );
    END;
    earned_stars := GREATEST(COALESCE(p_stars, 0), 0);
  ELSE
    BEGIN
      PERFORM af_update_practice_task(
        p_profile,
        p_task_title,
        NULL,
        p_stars,
        p_correct,
        p_incorrect,
        p_questions_answered
      );
    EXCEPTION WHEN undefined_function THEN
      PERFORM af_update_tasks_practice(
        p_profile,
        p_task_title,
        NULL,
        p_stars,
        p_correct,
        p_incorrect,
        p_questions_answered
      );
    END;
    earned_stars := GREATEST(COALESCE(p_stars, 0), 0);
  END IF;

  RETURN earned_stars;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_task_completion(text, text, text, int, int, int, int) TO anon, authenticated, service_role;
