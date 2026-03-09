-- BaerenEd: Populate user_data.required_tasks from the config JSON for a given profile.
-- Option A: DB fetches config from GitHub (requires http extension).
-- Option B: Client passes config (e.g. after fetching from GitHub).
--
-- Merge behavior:
-- - If current required_tasks is empty: set to full task list from config (all status=incomplete).
-- - If not empty: use config as base (task list + stars/showdays/etc.); for each task name that
--   exists in both, keep existing status, correct, incorrect, questions; take the rest from config.
--
-- Enable the http extension once in Supabase (Database → Extensions → enable "http", or run):
--   CREATE EXTENSION IF NOT EXISTS http WITH SCHEMA extensions;
-- Then this function can fetch config from GitHub when p_config_json is omitted.

-- Call with profile only (DB fetches from GitHub): POST /rest/v1/rpc/af_required_tasks_from_config {"p_profile": "TE"}
-- Call with profile + config (client provides JSON):  {"p_profile": "TE", "p_config_json": { ... }}
CREATE OR REPLACE FUNCTION af_required_tasks_from_config(p_profile text, p_config_json jsonb DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  github_url text := 'https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/' || p_profile || '_config.json';
  config_json jsonb;
  http_status int;
  existing_required jsonb;
  merged_required jsonb;
BEGIN
  -- Resolve config: use p_config_json if provided, else fetch from GitHub
  IF p_config_json IS NOT NULL AND p_config_json != 'null'::jsonb THEN
    config_json := p_config_json;
  ELSE
    SELECT r.status, r.content::jsonb INTO http_status, config_json
    FROM http_get(github_url) r
    LIMIT 1;
    IF http_status != 200 OR config_json IS NULL THEN
      RAISE WARNING 'af_required_tasks_from_config: failed to fetch config for % (status %, content null)', p_profile, COALESCE(http_status, -1);
      RETURN;
    END IF;
  END IF;

  -- Current required_tasks for this profile (empty object if no row or column empty)
  SELECT COALESCE(required_tasks, '{}'::jsonb) INTO existing_required
  FROM user_data
  WHERE profile = p_profile;

  -- Build merged required_tasks: config as base; for each task, preserve existing status/correct/incorrect/questions when present
  SELECT COALESCE(
    (
      SELECT jsonb_object_agg(
        t->>'title',
        jsonb_build_object(
          'status', COALESCE((existing_required->(t->>'title'))->>'status', 'incomplete'),
          'correct', existing_required->(t->>'title')->'correct',
          'incorrect', existing_required->(t->>'title')->'incorrect',
          'questions', existing_required->(t->>'title')->'questions',
          'stars', t->'stars',
          'showdays', t->'showdays',
          'hidedays', t->'hidedays',
          'displayDays', t->'displayDays',
          'disable', t->'disable'
        )
      )
      FROM jsonb_array_elements(config_json->'sections') AS sec,
           jsonb_array_elements(COALESCE(sec->'tasks', '[]'::jsonb)) AS t
      WHERE sec->>'id' = 'required'
    ),
    '{}'::jsonb
  ) INTO merged_required;

  UPDATE user_data
  SET
    required_tasks = merged_required,
    last_updated = (NOW() AT TIME ZONE 'America/New_York')
  WHERE profile = p_profile;
END;
$$;

-- Grant execute so Supabase API can call it
GRANT EXECUTE ON FUNCTION af_required_tasks_from_config(text, jsonb) TO anon;
GRANT EXECUTE ON FUNCTION af_required_tasks_from_config(text, jsonb) TO authenticated;
GRANT EXECUTE ON FUNCTION af_required_tasks_from_config(text, jsonb) TO service_role;
