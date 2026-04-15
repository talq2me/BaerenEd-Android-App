-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpdateRequiredTasksFromConfig.
--   app/src/main/java/com/talq2me/baerened/DbProfileSessionLoader.kt  -  chained after profile load / config refresh.

CREATE OR REPLACE FUNCTION af_update_tasks_from_config_required(p_profile text, p_config_json jsonb DEFAULT NULL)
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
  merged_checklist jsonb;
  v_today_short text := lower(to_char((NOW() AT TIME ZONE 'America/Toronto'), 'Dy'));
  v_today_date date := (NOW() AT TIME ZONE 'America/Toronto')::date;
  v_required_possible_stars int := 0;
  v_checklist_possible_stars int := 0;
  v_possible_stars int := 0;
BEGIN
  IF p_config_json IS NOT NULL AND p_config_json != 'null'::jsonb THEN
    config_json := p_config_json;
  ELSE
    SELECT r.status, r.content::jsonb INTO http_status, config_json
    FROM http_get(github_url) r
    LIMIT 1;
    IF http_status != 200 OR config_json IS NULL THEN
      RAISE WARNING 'af_update_tasks_from_config_required: failed to fetch config for % (status %, content null)', p_profile, COALESCE(http_status, -1);
      RETURN;
    END IF;
  END IF;

  SELECT COALESCE(required_tasks, '{}'::jsonb) INTO existing_required
  FROM user_data
  WHERE profile = p_profile;

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
      WHERE sec->>'id' = 'required'
    ),
    '{}'::jsonb
  ) INTO merged_required;

  PERFORM af_update_tasks_from_config_checklist_items(p_profile, config_json);

  SELECT COALESCE(checklist_items, '{}'::jsonb) INTO merged_checklist
  FROM user_data
  WHERE profile = p_profile;

  SELECT COALESCE(SUM(COALESCE((e.value->>'stars')::int, 0)), 0)
  INTO v_required_possible_stars
  FROM jsonb_each(COALESCE(merged_required, '{}'::jsonb)) AS e(key, value)
  WHERE
    NOT (
      NULLIF(TRIM(COALESCE(e.value->>'disable', '')), '') IS NOT NULL
      AND to_date(e.value->>'disable', 'Mon DD, YYYY') IS NOT NULL
      AND v_today_date < to_date(e.value->>'disable', 'Mon DD, YYYY')
    )
    AND NOT EXISTS (
      SELECT 1
      FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'hidedays', ''), ' ', '')), ',')) AS d(day_token)
      WHERE d.day_token = v_today_short
    )
    AND (
      NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NULL
      OR EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = v_today_short
      )
    )
    AND (
      NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NOT NULL
      OR NULLIF(TRIM(COALESCE(e.value->>'showdays', '')), '') IS NULL
      OR EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'showdays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = v_today_short
      )
    );

  SELECT COALESCE(SUM(COALESCE((e.value->>'stars')::int, 0)), 0)
  INTO v_checklist_possible_stars
  FROM jsonb_each(COALESCE(merged_checklist, '{}'::jsonb)) AS e(key, value)
  WHERE
    NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NULL
    OR EXISTS (
      SELECT 1
      FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
      WHERE d.day_token = v_today_short
    );

  v_possible_stars := v_required_possible_stars + v_checklist_possible_stars;

  UPDATE user_data
  SET
    required_tasks = merged_required,
    possible_stars = v_possible_stars,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_from_config_required(text, jsonb) TO anon, authenticated, service_role;
