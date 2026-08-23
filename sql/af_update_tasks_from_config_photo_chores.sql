-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpdatePhotoChoresFromConfig.
--   app/src/main/java/com/talq2me/baerened/DbProfileSessionLoader.kt  -  chained after profile load / config refresh.
-- Invoked from (PostgreSQL, this repo sql/):
--   af_daily_reset.sql

-- BaerenEd: Merge GitHub Pages profile config section id "chores" into user_data.photo_chores.
-- Keyed by chore id. Preserves today's status on merge. Does not grant cash, berries, or minutes.
-- POST /rest/v1/rpc/af_update_tasks_from_config_photo_chores {"p_profile":"AM","p_config_json":{...}}

CREATE OR REPLACE FUNCTION af_update_tasks_from_config_photo_chores(p_profile text, p_config_json jsonb DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  github_url text := 'https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/config/' || p_profile || '_config.json';
  config_json jsonb;
  http_status int;
  existing_chores jsonb;
  merged_chores jsonb;
BEGIN
  IF p_config_json IS NOT NULL AND p_config_json != 'null'::jsonb THEN
    config_json := p_config_json;
  ELSE
    SELECT r.status, r.content::jsonb INTO http_status, config_json FROM http_get(github_url) r LIMIT 1;
    IF http_status != 200 OR config_json IS NULL THEN
      RAISE WARNING 'af_update_tasks_from_config_photo_chores: failed to fetch config for %', p_profile;
      RETURN;
    END IF;
  END IF;

  SELECT COALESCE(photo_chores, '{}'::jsonb) INTO existing_chores FROM user_data WHERE profile = p_profile;

  SELECT COALESCE(
    (
      SELECT jsonb_object_agg(
        t->>'id',
        jsonb_build_object(
          'status', COALESCE(existing_chores->(t->>'id')->>'status', 'incomplete'),
          'title', t->>'title',
          'description', t->>'description',
          'rewardCash', t->'rewardCash',
          'launch', COALESCE(NULLIF(TRIM(t->>'launch'), ''), 'chorePhoto'),
          'url', t->>'url',
          'webGame', t->'webGame',
          'showdays', t->>'showdays',
          'hidedays', t->>'hidedays',
          'displayDays', t->>'displayDays',
          'disable', t->>'disable'
        )
      )
      FROM jsonb_array_elements(config_json->'sections') AS sec,
           jsonb_array_elements(COALESCE(sec->'tasks', '[]'::jsonb)) AS t
      WHERE sec->>'id' = 'chores'
        AND NULLIF(TRIM(COALESCE(t->>'id', '')), '') IS NOT NULL
    ),
    '{}'::jsonb
  ) INTO merged_chores;

  UPDATE user_data SET
    photo_chores = merged_chores,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_from_config_photo_chores(text, jsonb) TO anon, authenticated, service_role;
