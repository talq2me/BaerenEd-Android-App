-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpdateChecklistItemsFromConfig (RPC af_update_tasks_from_config_checklist_items).
--   app/src/main/java/com/talq2me/baerened/DbProfileSessionLoader.kt  -  chained after profile load / config refresh.

CREATE OR REPLACE FUNCTION af_update_tasks_from_config_checklist_items(p_profile text, p_config_json jsonb DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  github_url text := 'https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/' || p_profile || '_config.json';
  config_json jsonb;
  http_status int;
  existing_checklist jsonb;
  merged_checklist jsonb;
BEGIN
  IF p_config_json IS NOT NULL AND p_config_json != 'null'::jsonb THEN
    config_json := p_config_json;
  ELSE
    SELECT r.status, r.content::jsonb INTO http_status, config_json FROM http_get(github_url) r LIMIT 1;
    IF http_status != 200 OR config_json IS NULL THEN
      RAISE WARNING 'af_update_tasks_from_config_checklist_items: failed to fetch config for %', p_profile;
      RETURN;
    END IF;
  END IF;

  SELECT COALESCE(checklist_items, '{}'::jsonb) INTO existing_checklist FROM user_data WHERE profile = p_profile;

  SELECT COALESCE(
    (
      SELECT jsonb_object_agg(
        it->>'label',
        jsonb_build_object(
          'done', COALESCE((existing_checklist->(it->>'label'))->>'done', 'false')::boolean,
          'stars', COALESCE((it->>'stars')::int, 0),
          'id', it->'id',
          'showdays', it->'showdays',
          'hidedays', it->'hidedays',
          'displayDays', it->'displayDays',
          'launch', to_jsonb('checklist_' || COALESCE(it->>'id', it->>'label'))
        )
      )
      FROM jsonb_array_elements(config_json->'sections') AS sec
      CROSS JOIN LATERAL jsonb_array_elements(COALESCE(sec->'items', '[]'::jsonb)) AS it
      WHERE sec->'items' IS NOT NULL AND jsonb_array_length(COALESCE(sec->'items', '[]'::jsonb)) > 0
    ),
    '{}'::jsonb
  ) INTO merged_checklist;

  UPDATE user_data SET checklist_items = merged_checklist, last_updated = (NOW() AT TIME ZONE 'America/Toronto') WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_from_config_checklist_items(text, jsonb) TO anon, authenticated, service_role;
