-- BaerenEd: Update user_data.chores only from GitHub chores.json.
-- Fetch from GitHub when p_chores_json omitted; merge by chore_id (preserve done).
-- Requires http extension: CREATE EXTENSION IF NOT EXISTS http WITH SCHEMA extensions;
-- Call: {"p_profile": "TE"} or {"p_profile": "TE", "p_chores_json": [...]}

CREATE OR REPLACE FUNCTION af_chores_from_github(p_profile text, p_chores_json jsonb DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  chores_url text := 'https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/chores.json';
  chores_json jsonb;
  http_status int;
  existing_chores jsonb;
  merged_chores jsonb;
BEGIN
  IF p_chores_json IS NOT NULL AND p_chores_json != 'null'::jsonb AND jsonb_typeof(p_chores_json) = 'array' THEN
    chores_json := p_chores_json;
  ELSE
    SELECT r.status, r.content::jsonb INTO http_status, chores_json FROM http_get(chores_url) r LIMIT 1;
    IF http_status != 200 OR chores_json IS NULL OR jsonb_typeof(chores_json) != 'array' THEN
      RAISE WARNING 'af_chores_from_github: failed to fetch chores (status %)', COALESCE(http_status, -1);
      RETURN;
    END IF;
  END IF;

  SELECT COALESCE(chores, '[]'::jsonb) INTO existing_chores FROM user_data WHERE profile = p_profile;

  SELECT jsonb_agg(
    jsonb_build_object(
      'chore_id', c->'id',
      'description', c->'description',
      'coins_reward', c->'coins',
      'done', COALESCE(
        (SELECT (e->>'done')::boolean FROM jsonb_array_elements(existing_chores) e WHERE (e->>'chore_id')::int = (c->>'id')::int LIMIT 1),
        false
      )
    )
    ORDER BY (c->>'id')::int
  ) INTO merged_chores
  FROM jsonb_array_elements(chores_json) c;

  UPDATE user_data SET chores = COALESCE(merged_chores, '[]'::jsonb), last_updated = (NOW() AT TIME ZONE 'America/New_York') WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_chores_from_github(text, jsonb) TO anon, authenticated, service_role;
