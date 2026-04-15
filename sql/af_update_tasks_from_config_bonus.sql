-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpdateBonusTasksFromConfig.
--   app/src/main/java/com/talq2me/baerened/DbProfileSessionLoader.kt  -  chained after profile load / config refresh.

CREATE OR REPLACE FUNCTION af_update_tasks_from_config_bonus(p_profile text, p_config_json jsonb DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  github_url text := 'https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/' || p_profile || '_config.json';
  config_json jsonb;
  http_status int;
  existing_bonus jsonb;
  merged_bonus jsonb;
BEGIN
  IF p_config_json IS NOT NULL AND p_config_json != 'null'::jsonb THEN
    config_json := p_config_json;
  ELSE
    SELECT r.status, r.content::jsonb INTO http_status, config_json FROM http_get(github_url) r LIMIT 1;
    IF http_status != 200 OR config_json IS NULL THEN
      RAISE WARNING 'af_update_tasks_from_config_bonus: failed to fetch config for %', p_profile;
      RETURN;
    END IF;
  END IF;

  SELECT COALESCE(bonus_tasks, '{}'::jsonb) INTO existing_bonus FROM user_data WHERE profile = p_profile;

  SELECT COALESCE(
    (
      SELECT jsonb_object_agg(
        t->>'title',
        jsonb_build_object(
          'times_completed', COALESCE((existing_bonus->(t->>'title'))->>'times_completed', '0')::int,
          'correct', existing_bonus->(t->>'title')->'correct',
          'incorrect', existing_bonus->(t->>'title')->'incorrect',
          'questions_answered', existing_bonus->(t->>'title')->'questions_answered',
          'stars', t->'stars',
          'launch', t->'launch',
          'url', t->'url',
          'webGame', t->'webGame',
          'chromePage', t->'chromePage',
          'videoSequence', t->'videoSequence',
          'video', t->'video',
          'playlistId', t->'playlistId',
          'blockOutlines', t->'blockOutlines',
          'rewardId', t->'rewardId',
          'totalQuestions', t->'totalQuestions',
          'easydays', t->'easydays',
          'harddays', t->'harddays',
          'extremedays', t->'extremedays',
          'showdays', t->'showdays',
          'hidedays', t->'hidedays',
          'displayDays', t->'displayDays',
          'disable', t->'disable'
        )
      )
      FROM jsonb_array_elements(config_json->'sections') AS sec,
           jsonb_array_elements(COALESCE(sec->'tasks', '[]'::jsonb)) AS t
      WHERE sec->>'id' = 'bonus'
    ),
    '{}'::jsonb
  ) INTO merged_bonus;

  UPDATE user_data SET
    bonus_tasks = merged_bonus,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_from_config_bonus(text, jsonb) TO anon, authenticated, service_role;
