-- BaerenEd: Update user_data.practice_tasks (Extra Practice Map, section "optional" only).
-- Fetch from GitHub when p_config_json omitted; merge with existing (preserve times_completed, correct, etc.).
-- Requires http extension: CREATE EXTENSION IF NOT EXISTS http WITH SCHEMA extensions;
-- Call: {"p_profile": "TE"} or {"p_profile": "TE", "p_config_json": {...}}
-- For bonus tasks use af_bonus_tasks_from_config.

CREATE OR REPLACE FUNCTION af_practice_tasks_from_config(p_profile text, p_config_json jsonb DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  github_url text := 'https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/' || p_profile || '_config.json';
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
      RAISE WARNING 'af_practice_tasks_from_config: failed to fetch config for %', p_profile;
      RETURN;
    END IF;
  END IF;

  SELECT COALESCE(practice_tasks, '{}'::jsonb) INTO existing_practice FROM user_data WHERE profile = p_profile;

  -- practice_tasks: only section id = 'optional'
  SELECT COALESCE(
    (
      SELECT jsonb_object_agg(
        t->>'title',
        jsonb_build_object(
          'times_completed', COALESCE((existing_practice->(t->>'title'))->>'times_completed', '0')::int,
          'correct', existing_practice->(t->>'title')->'correct',
          'incorrect', existing_practice->(t->>'title')->'incorrect',
          'questions_answered', existing_practice->(t->>'title')->'questions_answered',
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

  UPDATE user_data SET
    practice_tasks = merged_practice,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_practice_tasks_from_config(text, jsonb) TO anon, authenticated, service_role;
