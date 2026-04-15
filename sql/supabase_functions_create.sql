
-- =============================================================================

-- BaerenEd: Deploy all RPC/functions (drop + create)

-- Run after schema tables exist (see sql/supabase_schema_create.sql).

-- Regenerate from sql/regen_deploy_all_functions.ps1

-- =============================================================================


-- Drop existing function signatures first
DROP FUNCTION IF EXISTS af_daily_reset(text);
DROP FUNCTION IF EXISTS af_delete_image_upload_by_id(bigint);
DROP FUNCTION IF EXISTS af_delete_image_uploads_ilike(text, text);
DROP FUNCTION IF EXISTS af_get_battle_hub_counts(text);
DROP FUNCTION IF EXISTS af_get_current_required_tasks(text);
DROP FUNCTION IF EXISTS af_get_device_row(text);
DROP FUNCTION IF EXISTS af_get_image_upload_id(text, text);
DROP FUNCTION IF EXISTS af_get_settings_last_updated();
DROP FUNCTION IF EXISTS af_get_settings_row();
DROP FUNCTION IF EXISTS af_get_stars_to_minutes(int);
DROP FUNCTION IF EXISTS af_get_tasks_bonus(text);
DROP FUNCTION IF EXISTS af_get_tasks_practice(text);
DROP FUNCTION IF EXISTS af_get_tasks_required(text);
DROP FUNCTION IF EXISTS af_get_user_data(text);
DROP FUNCTION IF EXISTS af_get_user_last_reset(text);
DROP FUNCTION IF EXISTS af_get_user_last_updated(text);
DROP FUNCTION IF EXISTS af_insert_user_data_profile(text);
DROP FUNCTION IF EXISTS af_reward_time_add(TEXT, INTEGER);
DROP FUNCTION IF EXISTS af_reward_time_expire(TEXT);
DROP FUNCTION IF EXISTS af_reward_time_pause(TEXT);
DROP FUNCTION IF EXISTS af_reward_time_use(TEXT);
DROP FUNCTION IF EXISTS af_update_berries_banked(text, int, int);
DROP FUNCTION IF EXISTS af_update_game_index(text, text, int);
DROP FUNCTION IF EXISTS af_update_pokemon_unlocked(text, int);
DROP FUNCTION IF EXISTS af_update_task_completion(text, text, text, int, int, int, int);
DROP FUNCTION IF EXISTS af_update_tasks_bonus(text, text, int, int, int, int, int);
DROP FUNCTION IF EXISTS af_update_tasks_checklist_items(text, text, boolean);
DROP FUNCTION IF EXISTS af_update_tasks_chores(text, int, boolean);
DROP FUNCTION IF EXISTS af_update_tasks_from_config_bonus(text, jsonb);
DROP FUNCTION IF EXISTS af_update_tasks_from_config_checklist_items(text, jsonb);
DROP FUNCTION IF EXISTS af_update_tasks_from_config_chores(text, jsonb);
DROP FUNCTION IF EXISTS af_update_tasks_from_config_practice(text, jsonb);
DROP FUNCTION IF EXISTS af_update_tasks_from_config_required(text, jsonb);
DROP FUNCTION IF EXISTS af_update_tasks_practice(text, text, int, int, int, int, int);
DROP FUNCTION IF EXISTS af_update_tasks_required(text, text, text, int, int, int);
DROP FUNCTION IF EXISTS af_upsert_device(text, text, text, text, text, text, text, boolean);
DROP FUNCTION IF EXISTS af_upsert_image_upload(text, text, text);
DROP FUNCTION IF EXISTS af_upsert_settings_row(text, text, boolean);
DROP FUNCTION IF EXISTS af_upsert_user_data_columns(text, jsonb);

-- -----------------------------------------------------------------------------
-- FILE: af_get_stars_to_minutes.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo): not used (Kotlin never calls this RPC).
-- Invoked from (PostgreSQL, this repo sql/):
--   af_get_current_required_tasks.sql; af_get_tasks_required.sql; af_get_tasks_practice.sql;
--   af_get_tasks_bonus.sql; af_update_tasks_required.sql; af_update_tasks_practice.sql;
--   af_update_tasks_bonus.sql; af_update_tasks_checklist_items.sql.

-- BaerenEd: Shared helper for reward minutes. Same rules as DailyProgressManager.convertStarsToMinutes:
-- 1 star = 1 min, 2 stars = 3 min, 3 stars = 5 min; 4+ uses (stars/3)*5 + remainder (1->1, 2->3).
-- Deploy this once; used by af_update_tasks_required, af_update_tasks_practice, af_update_tasks_checklist_items.

CREATE OR REPLACE FUNCTION af_get_stars_to_minutes(p_stars int)
RETURNS int
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT CASE
    WHEN p_stars IS NULL OR p_stars <= 0 THEN 0
    WHEN p_stars = 1 THEN 1
    WHEN p_stars = 2 THEN 3
    WHEN p_stars = 3 THEN 5
    ELSE (p_stars/3)*5 + CASE p_stars % 3 WHEN 1 THEN 1 WHEN 2 THEN 3 ELSE 0 END
  END
$$;

GRANT EXECUTE ON FUNCTION af_get_stars_to_minutes(int) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_update_tasks_from_config_checklist_items.sql
-- -----------------------------------------------------------------------------
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


-- -----------------------------------------------------------------------------
-- FILE: af_update_tasks_from_config_chores.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpdateChoresFromGitHub.
--   app/src/main/java/com/talq2me/baerened/DbProfileSessionLoader.kt  -  chained after profile load / config refresh.

CREATE OR REPLACE FUNCTION af_update_tasks_from_config_chores(p_profile text, p_chores_json jsonb DEFAULT NULL)
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
      RAISE WARNING 'af_update_tasks_from_config_chores: failed to fetch chores (status %)', COALESCE(http_status, -1);
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

  UPDATE user_data SET chores = COALESCE(merged_chores, '[]'::jsonb), last_updated = (NOW() AT TIME ZONE 'America/Toronto') WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_from_config_chores(text, jsonb) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_update_tasks_from_config_practice.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpdatePracticeTasksFromConfig.
--   app/src/main/java/com/talq2me/baerened/DbProfileSessionLoader.kt  -  chained after profile load / config refresh.

CREATE OR REPLACE FUNCTION af_update_tasks_from_config_practice(p_profile text, p_config_json jsonb DEFAULT NULL)
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
      RAISE WARNING 'af_update_tasks_from_config_practice: failed to fetch config for %', p_profile;
      RETURN;
    END IF;
  END IF;

  SELECT COALESCE(practice_tasks, '{}'::jsonb) INTO existing_practice FROM user_data WHERE profile = p_profile;

  SELECT COALESCE(
    (
      SELECT jsonb_object_agg(
        t->>'title',
        jsonb_build_object(
          'times_completed', COALESCE((existing_practice->(t->>'title'))->>'times_completed', '0')::int,
          'correct', existing_practice->(t->>'title')->'correct',
          'incorrect', existing_practice->(t->>'title')->'incorrect',
          'questions_answered', existing_practice->(t->>'title')->'questions_answered',
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

GRANT EXECUTE ON FUNCTION af_update_tasks_from_config_practice(text, jsonb) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_update_tasks_from_config_bonus.sql
-- -----------------------------------------------------------------------------
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


-- -----------------------------------------------------------------------------
-- FILE: af_update_tasks_from_config_required.sql
-- -----------------------------------------------------------------------------
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


-- -----------------------------------------------------------------------------
-- FILE: af_daily_reset.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfDailyReset.
--   app/src/main/java/com/talq2me/baerened/UserDataRepository.kt  -  fetchUserData before download.

-- BaerenEd: Daily reset applied at read time (AF = "at fetch").
-- Call this before reading user_data so the row for the given profile with last_reset date not equal to today (Toronto time)
-- gets reset: blank required_tasks, checklist_items, practice_tasks, berries_earned, banked_mins, chores;
-- set last_reset and last_updated to now() in America/Toronto. Does not change coins_earned, pokemon_unlocked, game_indices.
-- When a reset row was updated (FOUND), repopulates task/chore columns from GitHub via af_update_*
-- (full implementations are in the per-function af_update_* files in sql/).
-- Run in Supabase SQL Editor once to create the function; then call via PostgREST: POST /rest/v1/rpc/af_daily_reset with body {"p_profile": "AM"}

CREATE OR REPLACE FUNCTION af_daily_reset(p_profile text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  today_est date;
BEGIN
  today_est := (NOW() AT TIME ZONE 'America/Toronto')::date;

  UPDATE user_data
  SET
    last_reset = (NOW() AT TIME ZONE 'America/Toronto'),
    last_updated = (NOW() AT TIME ZONE 'America/Toronto'),
    required_tasks = '{}'::jsonb,
    checklist_items = '{}'::jsonb,
    practice_tasks = '{}'::jsonb,
    bonus_tasks = '{}'::jsonb,
    berries_earned = 0,
    banked_mins = 0,
    reward_time_expiry = NULL,
    chores = '[]'::jsonb
  WHERE profile = p_profile
    AND (last_reset IS NULL OR last_reset::date IS DISTINCT FROM today_est);

  IF FOUND THEN
    PERFORM af_update_tasks_from_config_required(p_profile);
    PERFORM af_update_tasks_from_config_practice(p_profile);
    PERFORM af_update_tasks_from_config_bonus(p_profile);
    PERFORM af_update_tasks_from_config_chores(p_profile);
  END IF;
END;
$$;

-- Grant execute to anon and authenticated so Supabase API can call it
GRANT EXECUTE ON FUNCTION af_daily_reset(text) TO anon;
GRANT EXECUTE ON FUNCTION af_daily_reset(text) TO authenticated;
GRANT EXECUTE ON FUNCTION af_daily_reset(text) TO service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_get_current_required_tasks.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   (no Kotlin string match for this RPC name in this repo  -  optional / legacy / manual PostgREST.)
-- Invoked from (PostgreSQL, this repo sql/):
--   Uses af_get_stars_to_minutes; may be referenced by older DB setups or tooling.

-- BaerenEd: Today's visible required tasks + checklist items from user_data.
-- One row per task (required + visible checklist), sorted by name.
--
-- Columns (real table columns for PostgREST / SELECT * — not one json blob):
--   task_name          — key in required_tasks or checklist_items JSON
--   completion_status  — 'complete' | 'incomplete'
--   berry_value        — stars for that task
--   mins_value         — af_get_stars_to_minutes(stars)
--
-- Call:
--   POST /rest/v1/rpc/af_get_current_required_tasks {"p_profile":"AM"}
--   Or: SELECT * FROM af_get_current_required_tasks('AM');

DROP FUNCTION IF EXISTS af_get_current_required_tasks(text);

CREATE OR REPLACE FUNCTION af_get_current_required_tasks(p_profile text)
RETURNS TABLE (
  task_name text,
  completion_status text,
  berry_value int,
  mins_value int
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  WITH params AS (
    SELECT
      COALESCE(ud.required_tasks, '{}'::jsonb) AS v_required,
      COALESCE(ud.checklist_items, '{}'::jsonb) AS v_checklist,
      lower(to_char((now() AT TIME ZONE 'America/Toronto'), 'Dy')) AS v_today_short,
      (now() AT TIME ZONE 'America/Toronto')::date AS v_today_date
    FROM (SELECT 1) AS _one
    LEFT JOIN user_data ud ON ud.profile = p_profile
  ),
  visible_required AS (
    SELECT
      e.key AS item_name,
      coalesce(e.value->>'status', 'incomplete') AS status_text,
      coalesce((e.value->>'stars')::int, 0) AS stars
    FROM params p
    CROSS JOIN jsonb_each(p.v_required) AS e(key, value)
    WHERE
      NOT (
        nullif(trim(coalesce(e.value->>'disable', '')), '') IS NOT NULL
        AND to_date(e.value->>'disable', 'Mon DD, YYYY') IS NOT NULL
        AND p.v_today_date < to_date(e.value->>'disable', 'Mon DD, YYYY')
      )
      AND NOT EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(coalesce(e.value->>'hidedays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = p.v_today_short
      )
      AND (
        nullif(trim(coalesce(e.value->>'displayDays', '')), '') IS NULL
        OR EXISTS (
          SELECT 1
          FROM unnest(string_to_array(lower(replace(coalesce(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
          WHERE d.day_token = p.v_today_short
        )
      )
      AND (
        nullif(trim(coalesce(e.value->>'displayDays', '')), '') IS NOT NULL
        OR nullif(trim(coalesce(e.value->>'showdays', '')), '') IS NULL
        OR EXISTS (
          SELECT 1
          FROM unnest(string_to_array(lower(replace(coalesce(e.value->>'showdays', ''), ' ', '')), ',')) AS d(day_token)
          WHERE d.day_token = p.v_today_short
        )
      )
  ),
  visible_checklist AS (
    SELECT
      e.key AS item_name,
      CASE WHEN coalesce((e.value->>'done')::boolean, false) THEN 'complete' ELSE 'incomplete' END AS status_text,
      coalesce((e.value->>'stars')::int, 0) AS stars
    FROM params p
    CROSS JOIN jsonb_each(p.v_checklist) AS e(key, value)
    WHERE
      nullif(trim(coalesce(e.value->>'displayDays', '')), '') IS NULL
      OR EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(coalesce(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = p.v_today_short
      )
  ),
  combined AS (
    SELECT item_name, status_text, stars FROM visible_required
    UNION ALL
    SELECT item_name, status_text, stars FROM visible_checklist
  )
  SELECT
    c.item_name::text AS task_name,
    c.status_text::text AS completion_status,
    c.stars::int AS berry_value,
    af_get_stars_to_minutes(c.stars)::int AS mins_value
  FROM combined c
  ORDER BY c.item_name;
$$;

GRANT EXECUTE ON FUNCTION af_get_current_required_tasks(text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_get_tasks_required.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetRequiredTasksRows (invokeRpcProfileReturningJsonArray "af_get_tasks_required").
--   app/src/main/java/com/talq2me/baerened/TrainerMapTaskMerge.kt  -  prepareFromDbStrict.

-- BaerenEd: Required + checklist task rows from user_data JSON (trainer map).
-- POST /rest/v1/rpc/af_get_tasks_required {"p_profile":"AM"}

DROP FUNCTION IF EXISTS af_get_tasks_required_v2(text);
DROP FUNCTION IF EXISTS af_get_tasks_required(text);

CREATE OR REPLACE FUNCTION af_get_tasks_required(p_profile text)
RETURNS TABLE (
  task_name text,
  completion_status text,
  berry_value int,
  mins_value int,
  launch text,
  url text,
  web_game boolean,
  chrome_page boolean,
  video_sequence text,
  playlist_id text,
  total_questions int,
  reward_id text,
  easydays text,
  harddays text,
  extremedays text,
  block_outlines boolean,
  is_checklist boolean
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  WITH params AS (
    SELECT
      COALESCE(ud.required_tasks, '{}'::jsonb) AS v_required,
      COALESCE(ud.checklist_items, '{}'::jsonb) AS v_checklist,
      lower(to_char((now() AT TIME ZONE 'America/Toronto'), 'Dy')) AS v_today_short,
      (now() AT TIME ZONE 'America/Toronto')::date AS v_today_date
    FROM (SELECT 1) AS _one
    LEFT JOIN user_data ud ON ud.profile = p_profile
  ),
  visible_required AS (
    SELECT
      e.key::text AS task_name,
      COALESCE(e.value->>'status', 'incomplete')::text AS completion_status,
      COALESCE((e.value->>'stars')::int, 0) AS berry_value,
      (e.value->>'launch')::text AS launch,
      (e.value->>'url')::text AS url,
      COALESCE((e.value->>'webGame')::boolean, false) AS web_game,
      COALESCE((e.value->>'chromePage')::boolean, false) AS chrome_page,
      (e.value->>'videoSequence')::text AS video_sequence,
      (e.value->>'playlistId')::text AS playlist_id,
      (e.value->>'totalQuestions')::int AS total_questions,
      (e.value->>'rewardId')::text AS reward_id,
      (e.value->>'easydays')::text AS easydays,
      (e.value->>'harddays')::text AS harddays,
      (e.value->>'extremedays')::text AS extremedays,
      COALESCE((e.value->>'blockOutlines')::boolean, false) AS block_outlines,
      false AS is_checklist
    FROM params p
    CROSS JOIN jsonb_each(p.v_required) AS e(key, value)
    WHERE
      NOT (
        NULLIF(TRIM(COALESCE(e.value->>'disable', '')), '') IS NOT NULL
        AND to_date(e.value->>'disable', 'Mon DD, YYYY') IS NOT NULL
        AND p.v_today_date < to_date(e.value->>'disable', 'Mon DD, YYYY')
      )
      AND NOT EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'hidedays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = p.v_today_short
      )
      AND (
        NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NULL
        OR EXISTS (
          SELECT 1
          FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
          WHERE d.day_token = p.v_today_short
        )
      )
      AND (
        NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NOT NULL
        OR NULLIF(TRIM(COALESCE(e.value->>'showdays', '')), '') IS NULL
        OR EXISTS (
          SELECT 1
          FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'showdays', ''), ' ', '')), ',')) AS d(day_token)
          WHERE d.day_token = p.v_today_short
        )
      )
  ),
  visible_checklist AS (
    SELECT
      e.key::text AS task_name,
      CASE WHEN COALESCE((e.value->>'done')::boolean, false) THEN 'complete' ELSE 'incomplete' END AS completion_status,
      COALESCE((e.value->>'stars')::int, 0) AS berry_value,
      COALESCE((e.value->>'launch')::text, 'checklist_' || COALESCE(NULLIF(e.value->>'id', ''), e.key)) AS launch,
      NULL::text AS url,
      false AS web_game,
      false AS chrome_page,
      NULL::text AS video_sequence,
      NULL::text AS playlist_id,
      NULL::int AS total_questions,
      NULL::text AS reward_id,
      NULL::text AS easydays,
      NULL::text AS harddays,
      NULL::text AS extremedays,
      false AS block_outlines,
      true AS is_checklist
    FROM params p
    CROSS JOIN jsonb_each(p.v_checklist) AS e(key, value)
    WHERE
      NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NULL
      OR EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = p.v_today_short
      )
  )
  SELECT
    v.task_name,
    v.completion_status,
    v.berry_value,
    af_get_stars_to_minutes(v.berry_value)::int AS mins_value,
    v.launch,
    v.url,
    v.web_game,
    v.chrome_page,
    v.video_sequence,
    v.playlist_id,
    v.total_questions,
    v.reward_id,
    v.easydays,
    v.harddays,
    v.extremedays,
    v.block_outlines,
    v.is_checklist
  FROM (
    SELECT * FROM visible_required
    UNION ALL
    SELECT * FROM visible_checklist
  ) v
  ORDER BY v.task_name;
$$;

GRANT EXECUTE ON FUNCTION af_get_tasks_required(text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_get_tasks_practice.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetPracticeTasksRows ("af_get_tasks_practice").
--   app/src/main/java/com/talq2me/baerened/TrainerMapTaskMerge.kt  -  prepareFromDbStrict.

-- BaerenEd: Practice (optional map) task rows from user_data JSON.
-- POST /rest/v1/rpc/af_get_tasks_practice {"p_profile":"AM"}

DROP FUNCTION IF EXISTS af_get_tasks_practice_v2(text);

CREATE OR REPLACE FUNCTION af_get_tasks_practice(p_profile text)
RETURNS TABLE (
  task_name text,
  completion_status text,
  berry_value int,
  mins_value int,
  launch text,
  url text,
  web_game boolean,
  chrome_page boolean,
  video_sequence text,
  playlist_id text,
  total_questions int,
  reward_id text,
  easydays text,
  harddays text,
  extremedays text,
  block_outlines boolean,
  is_checklist boolean
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  WITH params AS (
    SELECT
      COALESCE(ud.practice_tasks, '{}'::jsonb) AS v_tasks,
      lower(to_char((now() AT TIME ZONE 'America/Toronto'), 'Dy')) AS v_today_short,
      (now() AT TIME ZONE 'America/Toronto')::date AS v_today_date
    FROM (SELECT 1) AS _one
    LEFT JOIN user_data ud ON ud.profile = p_profile
  ),
  visible AS (
    SELECT
      e.key::text AS task_name,
      CASE WHEN COALESCE((e.value->>'times_completed')::int, 0) > 0 THEN 'complete' ELSE 'incomplete' END AS completion_status,
      COALESCE((e.value->>'stars')::int, 0) AS berry_value,
      (e.value->>'launch')::text AS launch,
      (e.value->>'url')::text AS url,
      COALESCE((e.value->>'webGame')::boolean, false) AS web_game,
      COALESCE((e.value->>'chromePage')::boolean, false) AS chrome_page,
      (e.value->>'videoSequence')::text AS video_sequence,
      (e.value->>'playlistId')::text AS playlist_id,
      (e.value->>'totalQuestions')::int AS total_questions,
      (e.value->>'rewardId')::text AS reward_id,
      (e.value->>'easydays')::text AS easydays,
      (e.value->>'harddays')::text AS harddays,
      (e.value->>'extremedays')::text AS extremedays,
      COALESCE((e.value->>'blockOutlines')::boolean, false) AS block_outlines,
      false AS is_checklist
    FROM params p
    CROSS JOIN jsonb_each(p.v_tasks) AS e(key, value)
    WHERE
      NOT (
        NULLIF(TRIM(COALESCE(e.value->>'disable', '')), '') IS NOT NULL
        AND to_date(e.value->>'disable', 'Mon DD, YYYY') IS NOT NULL
        AND p.v_today_date < to_date(e.value->>'disable', 'Mon DD, YYYY')
      )
      AND NOT EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'hidedays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = p.v_today_short
      )
      AND (
        NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NULL
        OR EXISTS (
          SELECT 1
          FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
          WHERE d.day_token = p.v_today_short
        )
      )
      AND (
        NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NOT NULL
        OR NULLIF(TRIM(COALESCE(e.value->>'showdays', '')), '') IS NULL
        OR EXISTS (
          SELECT 1
          FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'showdays', ''), ' ', '')), ',')) AS d(day_token)
          WHERE d.day_token = p.v_today_short
        )
      )
  )
  SELECT
    v.task_name,
    v.completion_status,
    v.berry_value,
    af_get_stars_to_minutes(v.berry_value)::int AS mins_value,
    v.launch,
    v.url,
    v.web_game,
    v.chrome_page,
    v.video_sequence,
    v.playlist_id,
    v.total_questions,
    v.reward_id,
    v.easydays,
    v.harddays,
    v.extremedays,
    v.block_outlines,
    v.is_checklist
  FROM visible v
  ORDER BY v.task_name;
$$;

GRANT EXECUTE ON FUNCTION af_get_tasks_practice(text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_get_tasks_bonus.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetBonusTasksRows ("af_get_tasks_bonus").
--   app/src/main/java/com/talq2me/baerened/TrainerMapTaskMerge.kt  -  prepareFromDbStrict.

-- BaerenEd: Bonus map task rows from user_data JSON.
-- POST /rest/v1/rpc/af_get_tasks_bonus {"p_profile":"AM"}

DROP FUNCTION IF EXISTS af_get_tasks_bonus_v2(text);

CREATE OR REPLACE FUNCTION af_get_tasks_bonus(p_profile text)
RETURNS TABLE (
  task_name text,
  completion_status text,
  berry_value int,
  mins_value int,
  launch text,
  url text,
  web_game boolean,
  chrome_page boolean,
  video_sequence text,
  playlist_id text,
  total_questions int,
  reward_id text,
  easydays text,
  harddays text,
  extremedays text,
  block_outlines boolean,
  is_checklist boolean
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  WITH params AS (
    SELECT
      COALESCE(ud.bonus_tasks, '{}'::jsonb) AS v_tasks,
      lower(to_char((now() AT TIME ZONE 'America/Toronto'), 'Dy')) AS v_today_short,
      (now() AT TIME ZONE 'America/Toronto')::date AS v_today_date
    FROM (SELECT 1) AS _one
    LEFT JOIN user_data ud ON ud.profile = p_profile
  ),
  visible AS (
    SELECT
      e.key::text AS task_name,
      CASE WHEN COALESCE((e.value->>'times_completed')::int, 0) > 0 THEN 'complete' ELSE 'incomplete' END AS completion_status,
      COALESCE((e.value->>'stars')::int, 0) AS berry_value,
      (e.value->>'launch')::text AS launch,
      (e.value->>'url')::text AS url,
      COALESCE((e.value->>'webGame')::boolean, false) AS web_game,
      COALESCE((e.value->>'chromePage')::boolean, false) AS chrome_page,
      (e.value->>'videoSequence')::text AS video_sequence,
      (e.value->>'playlistId')::text AS playlist_id,
      (e.value->>'totalQuestions')::int AS total_questions,
      (e.value->>'rewardId')::text AS reward_id,
      (e.value->>'easydays')::text AS easydays,
      (e.value->>'harddays')::text AS harddays,
      (e.value->>'extremedays')::text AS extremedays,
      COALESCE((e.value->>'blockOutlines')::boolean, false) AS block_outlines,
      false AS is_checklist
    FROM params p
    CROSS JOIN jsonb_each(p.v_tasks) AS e(key, value)
    WHERE
      NOT (
        NULLIF(TRIM(COALESCE(e.value->>'disable', '')), '') IS NOT NULL
        AND to_date(e.value->>'disable', 'Mon DD, YYYY') IS NOT NULL
        AND p.v_today_date < to_date(e.value->>'disable', 'Mon DD, YYYY')
      )
      AND NOT EXISTS (
        SELECT 1
        FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'hidedays', ''), ' ', '')), ',')) AS d(day_token)
        WHERE d.day_token = p.v_today_short
      )
      AND (
        NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NULL
        OR EXISTS (
          SELECT 1
          FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'displayDays', ''), ' ', '')), ',')) AS d(day_token)
          WHERE d.day_token = p.v_today_short
        )
      )
      AND (
        NULLIF(TRIM(COALESCE(e.value->>'displayDays', '')), '') IS NOT NULL
        OR NULLIF(TRIM(COALESCE(e.value->>'showdays', '')), '') IS NULL
        OR EXISTS (
          SELECT 1
          FROM unnest(string_to_array(lower(replace(COALESCE(e.value->>'showdays', ''), ' ', '')), ',')) AS d(day_token)
          WHERE d.day_token = p.v_today_short
        )
      )
  )
  SELECT
    v.task_name,
    v.completion_status,
    v.berry_value,
    af_get_stars_to_minutes(v.berry_value)::int AS mins_value,
    v.launch,
    v.url,
    v.web_game,
    v.chrome_page,
    v.video_sequence,
    v.playlist_id,
    v.total_questions,
    v.reward_id,
    v.easydays,
    v.harddays,
    v.extremedays,
    v.block_outlines,
    v.is_checklist
  FROM visible v
  ORDER BY v.task_name;
$$;

GRANT EXECUTE ON FUNCTION af_get_tasks_bonus(text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_get_battle_hub_counts.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetBattleHubCounts.
--   app/src/main/java/com/talq2me/baerened/BattleHubActivity.kt  -  hub counts.
--   SupabaseInterface.invokeAddRewardTime  -  fallback path reads hub after failed af_reward_time_add.

-- BaerenEd: Single JSON payload for Battle Hub (stars, berries, banked reward time, coins, pokemon, active reward expiry).
-- POST /rest/v1/rpc/af_get_battle_hub_counts {"p_profile":"AM"}

CREATE OR REPLACE FUNCTION af_get_battle_hub_counts(p_profile text)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT COALESCE(
    (
      SELECT jsonb_build_object(
        'possible_stars', COALESCE(ud.possible_stars, 0),
        'berries_earned', COALESCE(ud.berries_earned, 0),
        'banked_mins', COALESCE(ud.banked_mins, 0),
        'coins_earned', COALESCE(ud.coins_earned, 0),
        'pokemon_unlocked', COALESCE(ud.pokemon_unlocked, 0),
        'kid_bank_balance', COALESCE(ud.kid_bank_balance, 0),
        'reward_time_expiry', ud.reward_time_expiry
      )
      FROM user_data ud
      WHERE ud.profile = p_profile
    ),
    '{}'::jsonb
  );
$$;

GRANT EXECUTE ON FUNCTION af_get_battle_hub_counts(text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_get_user_data.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  downloadUserData ("af_get_user_data").
--   app/src/main/java/com/talq2me/baerened/UserDataRepository.kt  -  fetchUserData.

-- BaerenEd: af_get_user_data
-- (from af_data_access_rpcs.sql)

-- BaerenEd: RPC-only data access helpers (replace REST GET/PATCH on user_data, settings, devices, image_uploads).
-- Deploy after user_data / settings / devices / image_uploads tables exist.
-- Every function here is used by BaerenEd ([SupabaseInterface] and related) and/or BaerenLock (CloudSyncManager, DailyResetAndSyncManager).

-- -----------------------------------------------------------------------------
-- user_data reads
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION af_get_user_data(p_profile text)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT to_jsonb(ud.*)
  FROM user_data ud
  WHERE ud.profile = p_profile
  LIMIT 1;
$$;
GRANT EXECUTE ON FUNCTION af_get_user_data(text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_get_user_last_reset.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo): none (timestamps come from af_get_user_data / sync flow).
-- BaerenEd: af_get_user_last_reset
-- (from af_data_access_rpcs.sql)

CREATE OR REPLACE FUNCTION af_get_user_last_reset(p_profile text)
RETURNS text
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT to_char(ud.last_reset, 'YYYY-MM-DD HH24:MI:SS.MS')
  FROM user_data ud
  WHERE ud.profile = p_profile
  LIMIT 1;
$$;
GRANT EXECUTE ON FUNCTION af_get_user_last_reset(text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_get_user_last_updated.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo): none (timestamps come from af_get_user_data / sync flow).
-- BaerenEd: af_get_user_last_updated
-- (from af_data_access_rpcs.sql)

CREATE OR REPLACE FUNCTION af_get_user_last_updated(p_profile text)
RETURNS text
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT to_char(ud.last_updated, 'YYYY-MM-DD HH24:MI:SS.MS')
  FROM user_data ud
  WHERE ud.profile = p_profile
  LIMIT 1;
$$;
GRANT EXECUTE ON FUNCTION af_get_user_last_updated(text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_insert_user_data_profile.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  ensureUserDataProfileExists (private) before reward time / add time / berries RPCs.

-- BaerenEd: af_insert_user_data_profile
-- (from af_data_access_rpcs.sql)

-- -----------------------------------------------------------------------------
-- user_data ensure / partial patch (no timestamp conflict logic)
-- -----------------------------------------------------------------------------

DROP FUNCTION IF EXISTS af_ensure_user_data_profile(text);

CREATE OR REPLACE FUNCTION af_insert_user_data_profile(p_profile text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  INSERT INTO user_data (profile, banked_mins, last_updated)
  VALUES (p_profile, 0, (NOW() AT TIME ZONE 'America/Toronto'))
  ON CONFLICT (profile) DO NOTHING;
END;
$$;
GRANT EXECUTE ON FUNCTION af_insert_user_data_profile(text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_upsert_user_data_columns.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt - upsertUserDataColumns -> RPC af_upsert_user_data_columns.
--   SupabaseInterface.invokeAddRewardTime - fallback only (when af_reward_time_add RPC fails).

-- BaerenEd: af_upsert_user_data_columns

-- Removed af_upload_user_data (full-row upload); use af_upsert_user_data_columns and task RPCs.
DROP FUNCTION IF EXISTS af_upload_user_data(jsonb);

CREATE OR REPLACE FUNCTION af_upsert_user_data_columns(p_profile text, p_columns jsonb)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  UPDATE user_data SET
    last_reset = CASE WHEN p_columns ? 'last_reset' AND NULLIF(trim(p_columns->>'last_reset'), '') IS NOT NULL
      THEN (p_columns->>'last_reset')::timestamp(3) ELSE last_reset END,
    last_updated = CASE WHEN p_columns ? 'last_updated' AND NULLIF(trim(p_columns->>'last_updated'), '') IS NOT NULL
      THEN (p_columns->>'last_updated')::timestamp(3) ELSE last_updated END,
    required_tasks = CASE WHEN p_columns ? 'required_tasks' THEN (p_columns->'required_tasks')::jsonb ELSE required_tasks END,
    practice_tasks = CASE WHEN p_columns ? 'practice_tasks' THEN (p_columns->'practice_tasks')::jsonb ELSE practice_tasks END,
    bonus_tasks = CASE WHEN p_columns ? 'bonus_tasks' THEN (p_columns->'bonus_tasks')::jsonb ELSE bonus_tasks END,
    checklist_items = CASE WHEN p_columns ? 'checklist_items' THEN (p_columns->'checklist_items')::jsonb ELSE checklist_items END,
    possible_stars = CASE WHEN p_columns ? 'possible_stars' THEN (p_columns->>'possible_stars')::int ELSE possible_stars END,
    banked_mins = CASE WHEN p_columns ? 'banked_mins' THEN (p_columns->>'banked_mins')::int ELSE banked_mins END,
    berries_earned = CASE WHEN p_columns ? 'berries_earned' THEN (p_columns->>'berries_earned')::int ELSE berries_earned END,
    coins_earned = CASE WHEN p_columns ? 'coins_earned' THEN (p_columns->>'coins_earned')::int ELSE coins_earned END,
    kid_bank_balance = CASE WHEN p_columns ? 'kid_bank_balance' AND jsonb_typeof(p_columns->'kid_bank_balance') <> 'null'
      THEN (p_columns->>'kid_bank_balance')::numeric ELSE kid_bank_balance END,
    last_coins_payout_at = CASE WHEN p_columns ? 'last_coins_payout_at' AND NULLIF(trim(p_columns->>'last_coins_payout_at'), '') IS NOT NULL
      THEN (p_columns->>'last_coins_payout_at')::timestamp(3)
      WHEN p_columns ? 'last_coins_payout_at' AND NULLIF(trim(p_columns->>'last_coins_payout_at'), '') IS NULL THEN NULL
      ELSE last_coins_payout_at END,
    chores = CASE WHEN p_columns ? 'chores' THEN (p_columns->'chores')::jsonb ELSE chores END,
    pokemon_unlocked = CASE WHEN p_columns ? 'pokemon_unlocked' THEN (p_columns->>'pokemon_unlocked')::int ELSE pokemon_unlocked END,
    game_indices = CASE WHEN p_columns ? 'game_indices' THEN (p_columns->'game_indices')::jsonb ELSE game_indices END,
    reward_time_expiry = CASE WHEN p_columns ? 'reward_time_expiry' AND NULLIF(trim(p_columns->>'reward_time_expiry'), '') IS NOT NULL
      THEN (p_columns->>'reward_time_expiry')::timestamp(3)
      WHEN p_columns ? 'reward_time_expiry' AND NULLIF(trim(p_columns->>'reward_time_expiry'), '') IS NULL THEN NULL
      ELSE reward_time_expiry END,
    reward_apps = CASE WHEN p_columns ? 'reward_apps' THEN p_columns->>'reward_apps' ELSE reward_apps END,
    blacklisted_apps = CASE WHEN p_columns ? 'blacklisted_apps' THEN p_columns->>'blacklisted_apps' ELSE blacklisted_apps END,
    white_listed_apps = CASE WHEN p_columns ? 'white_listed_apps' THEN p_columns->>'white_listed_apps' ELSE white_listed_apps END
  WHERE profile = p_profile;
END;
$$;
GRANT EXECUTE ON FUNCTION af_upsert_user_data_columns(text, jsonb) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_get_settings_row.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetSettingsRow.
--   app/src/main/java/com/talq2me/baerened/SettingsManager.kt  -  loadSettingsFromCloud / related.

-- BaerenEd: af_get_settings_row
-- (from af_data_access_rpcs.sql)

-- -----------------------------------------------------------------------------
-- settings (id = 1)
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION af_get_settings_row()
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT to_jsonb(s.*)
  FROM settings s
  WHERE s.id = 1
  LIMIT 1;
$$;
GRANT EXECUTE ON FUNCTION af_get_settings_row() TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_get_settings_last_updated.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo): none (settings row via af_get_settings_row).
-- BaerenEd: af_get_settings_last_updated
-- (from af_data_access_rpcs.sql)

CREATE OR REPLACE FUNCTION af_get_settings_last_updated()
RETURNS text
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT to_char(s.last_updated, 'YYYY-MM-DD HH24:MI:SS.MS')
  FROM settings s
  WHERE s.id = 1
  LIMIT 1;
$$;
GRANT EXECUTE ON FUNCTION af_get_settings_last_updated() TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_upsert_settings_row.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt - invokeAfUpsertSettingsRow.
--   app/src/main/java/com/talq2me/baerened/SettingsManager.kt - saveSettingsToCloud / related.

-- BaerenEd: af_upsert_settings_row

CREATE OR REPLACE FUNCTION af_upsert_settings_row(p_parent_email text, p_pin text, p_aggressive_cleanup boolean DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  UPDATE settings SET
    parent_email = COALESCE(p_parent_email, parent_email),
    pin = COALESCE(p_pin, pin),
    aggressive_cleanup = CASE WHEN p_aggressive_cleanup IS NULL THEN aggressive_cleanup ELSE p_aggressive_cleanup END,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE id = 1;

  IF NOT FOUND THEN
    INSERT INTO settings (id, parent_email, pin, aggressive_cleanup, last_updated)
    VALUES (1, COALESCE(p_parent_email, ''), COALESCE(p_pin, ''), COALESCE(p_aggressive_cleanup, true), (NOW() AT TIME ZONE 'America/Toronto'))
    ON CONFLICT (id) DO UPDATE SET
      parent_email = EXCLUDED.parent_email,
      pin = EXCLUDED.pin,
      aggressive_cleanup = EXCLUDED.aggressive_cleanup,
      last_updated = EXCLUDED.last_updated;
  END IF;
END;
$$;
GRANT EXECUTE ON FUNCTION af_upsert_settings_row(text, text, boolean) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_get_device_row.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfGetDeviceRow.
--   app/src/main/java/com/talq2me/baerened/SettingsManager.kt  -  device row reads.

-- BaerenEd: af_get_device_row
-- (from af_data_access_rpcs.sql)

-- -----------------------------------------------------------------------------
-- devices
-- -----------------------------------------------------------------------------

-- Full devices row as jsonb (includes BaerenLock columns when present on the table:
-- baerenlock_health_status, baerenlock_health_issues, baerenlock_last_health_check).

CREATE OR REPLACE FUNCTION af_get_device_row(p_device_id text)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT to_jsonb(d.*)
  FROM devices d
  WHERE d.device_id = p_device_id
  LIMIT 1;
$$;
GRANT EXECUTE ON FUNCTION af_get_device_row(text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_upsert_device.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpsertDevice.
--   app/src/main/java/com/talq2me/baerened/SettingsManager.kt  -  sync device / active profile.

-- BaerenEd: af_upsert_device
-- (from af_data_access_rpcs.sql)

-- Profile sync: pass p_apply_baerenlock_health = false (default). Updates device_name, active_profile, last_updated;
-- preserves BaerenLock health columns on conflict.
-- BaerenLock health sync: pass p_apply_baerenlock_health = true with health fields; on conflict updates device_name and
-- health columns only (preserves last_updated and active_profile). Use explicit null for p_baerenlock_health_issues to clear it.
DROP FUNCTION IF EXISTS af_upsert_device(text, text, text, text);

CREATE OR REPLACE FUNCTION af_upsert_device(
  p_device_id text,
  p_device_name text,
  p_active_profile text,
  p_last_updated text DEFAULT NULL,
  p_baerenlock_health_status text DEFAULT NULL,
  p_baerenlock_health_issues text DEFAULT NULL,
  p_baerenlock_last_health_check text DEFAULT NULL,
  p_apply_baerenlock_health boolean DEFAULT false
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_ts timestamp(3);
  v_hc_ts timestamp(3);
BEGIN
  BEGIN
    v_ts := NULLIF(trim(COALESCE(p_last_updated, '')), '')::timestamp(3);
  EXCEPTION WHEN OTHERS THEN
    v_ts := (NOW() AT TIME ZONE 'America/Toronto');
  END;
  IF v_ts IS NULL THEN
    v_ts := (NOW() AT TIME ZONE 'America/Toronto');
  END IF;

  v_hc_ts := NULL;
  IF p_apply_baerenlock_health THEN
    BEGIN
      v_hc_ts := NULLIF(trim(COALESCE(p_baerenlock_last_health_check, '')), '')::timestamp(3);
    EXCEPTION WHEN OTHERS THEN
      v_hc_ts := (NOW() AT TIME ZONE 'America/Toronto');
    END;
    IF v_hc_ts IS NULL THEN
      v_hc_ts := (NOW() AT TIME ZONE 'America/Toronto');
    END IF;
  END IF;

  INSERT INTO devices (
    device_id,
    device_name,
    active_profile,
    last_updated,
    baerenlock_health_status,
    baerenlock_health_issues,
    baerenlock_last_health_check
  )
  VALUES (
    p_device_id,
    p_device_name,
    p_active_profile,
    v_ts,
    CASE WHEN p_apply_baerenlock_health THEN p_baerenlock_health_status ELSE NULL END,
    CASE WHEN p_apply_baerenlock_health THEN p_baerenlock_health_issues ELSE NULL END,
    CASE WHEN p_apply_baerenlock_health THEN v_hc_ts ELSE NULL END
  )
  ON CONFLICT (device_id) DO UPDATE SET
    device_name = EXCLUDED.device_name,
    active_profile = CASE
      WHEN p_apply_baerenlock_health THEN devices.active_profile
      ELSE EXCLUDED.active_profile
    END,
    last_updated = CASE
      WHEN p_apply_baerenlock_health THEN devices.last_updated
      ELSE EXCLUDED.last_updated
    END,
    baerenlock_health_status = CASE
      WHEN p_apply_baerenlock_health THEN EXCLUDED.baerenlock_health_status
      ELSE devices.baerenlock_health_status
    END,
    baerenlock_health_issues = CASE
      WHEN p_apply_baerenlock_health THEN EXCLUDED.baerenlock_health_issues
      ELSE devices.baerenlock_health_issues
    END,
    baerenlock_last_health_check = CASE
      WHEN p_apply_baerenlock_health THEN EXCLUDED.baerenlock_last_health_check
      ELSE devices.baerenlock_last_health_check
    END;
END;
$$;
GRANT EXECUTE ON FUNCTION af_upsert_device(text, text, text, text, text, text, text, boolean) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_upsert_image_upload.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpsertImageUpload.
--   app/src/main/java/com/talq2me/baerened/SpellingOCRActivity.kt  -  spelling image upload.
--   app/src/main/java/com/talq2me/baerened/WebGameActivity.kt  -  JS bridge image upload.

-- BaerenEd: af_upsert_image_upload
-- (from af_data_access_rpcs.sql)

-- -----------------------------------------------------------------------------
-- image_uploads
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION af_upsert_image_upload(p_profile text, p_task text, p_image text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  INSERT INTO image_uploads (profile, task, image)
  VALUES (p_profile, p_task, p_image)
  ON CONFLICT (profile, task) DO UPDATE SET
    image = EXCLUDED.image,
    capture_date_time = (NOW() AT TIME ZONE 'America/Toronto');
END;
$$;
GRANT EXECUTE ON FUNCTION af_upsert_image_upload(text, text, text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_delete_image_uploads_ilike.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfDeleteImageUploadsIlike.
--   app/src/main/java/com/talq2me/baerened/SpellingOCRActivity.kt  -  clear old spelling OCR images on launch.

-- BaerenEd: af_delete_image_uploads_ilike

CREATE OR REPLACE FUNCTION af_delete_image_uploads_ilike(p_profile text, p_task_pattern text)
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  n int;
BEGIN
  DELETE FROM image_uploads
  WHERE profile = p_profile AND task ILIKE p_task_pattern;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END;
$$;
GRANT EXECUTE ON FUNCTION af_delete_image_uploads_ilike(text, text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_get_image_upload_id.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt - invokeAfGetImageUploadId.
--   app/src/main/java/com/talq2me/baerened/SpellingOCRActivity.kt - replace prior upload for same task pattern.

-- BaerenEd: af_get_image_upload_id

CREATE OR REPLACE FUNCTION af_get_image_upload_id(p_profile text, p_task_pattern text)
RETURNS bigint
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT i.id
  FROM image_uploads i
  WHERE i.profile = p_profile AND i.task LIKE p_task_pattern
  ORDER BY i.id
  LIMIT 1;
$$;
GRANT EXECUTE ON FUNCTION af_get_image_upload_id(text, text) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_delete_image_upload_by_id.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfDeleteImageUploadById.
--   app/src/main/java/com/talq2me/baerened/SpellingOCRActivity.kt  -  delete existing row before upsert.

-- BaerenEd: af_delete_image_upload_by_id
-- (from af_data_access_rpcs.sql)

CREATE OR REPLACE FUNCTION af_delete_image_upload_by_id(p_id bigint)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  DELETE FROM image_uploads WHERE id = p_id;
$$;
GRANT EXECUTE ON FUNCTION af_delete_image_upload_by_id(bigint) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_update_tasks_required.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpdateRequiredTask.
--   app/src/main/java/com/talq2me/baerened/DailyProgressManager.kt  -  pushSingleItemUpdateToCloud (SingleItemUpdate.RequiredTask).

-- BaerenEd: Update a single required task by title. Only non-null parameters are applied; others preserve existing values.
-- When status is set to 'complete', adds the task's stars (from DB) to berries_earned and stars-to-minutes to banked_mins.
-- Requires af_get_stars_to_minutes (af_get_stars_to_minutes.sql). Identifies task by p_task_title (key in required_tasks JSONB).
-- Call: POST /rest/v1/rpc/af_update_tasks_required with body e.g.
--   {"p_profile": "TE", "p_task_title": "Math", "p_status": "complete", "p_correct": 5, "p_incorrect": 1, "p_questions": 6}

CREATE OR REPLACE FUNCTION af_update_tasks_required(
  p_profile text,
  p_task_title text,
  p_status text DEFAULT NULL,
  p_correct int DEFAULT NULL,
  p_incorrect int DEFAULT NULL,
  p_questions int DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  cur jsonb;
  existing jsonb;
  new_task jsonb;
  old_status text;
  new_status text;
  task_stars int;
  add_berries int := 0;
  add_mins int := 0;
BEGIN
  SELECT COALESCE(required_tasks, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;

  existing := cur->p_task_title;
  old_status := existing->>'status';
  new_status := COALESCE(p_status, old_status, 'incomplete');

  new_task := COALESCE(existing, '{}'::jsonb)
    || jsonb_build_object(
      'status', new_status,
      'correct', CASE WHEN p_correct IS NOT NULL THEN to_jsonb(p_correct) ELSE existing->'correct' END,
      'incorrect', CASE WHEN p_incorrect IS NOT NULL THEN to_jsonb(p_incorrect) ELSE existing->'incorrect' END,
      'questions', CASE WHEN p_questions IS NOT NULL THEN to_jsonb(p_questions) ELSE existing->'questions' END
    );
  new_task := new_task || (COALESCE(existing, '{}'::jsonb) - 'status' - 'correct' - 'incorrect' - 'questions');

  -- When transitioning to complete, add task's stars to berries_earned and to banked_mins (via af_get_stars_to_minutes)
  IF new_status = 'complete' AND (old_status IS NULL OR old_status != 'complete') THEN
    task_stars := COALESCE((new_task->>'stars')::int, 0);
    IF task_stars > 0 THEN
      add_berries := task_stars;
      add_mins := af_get_stars_to_minutes(task_stars);
    END IF;
  END IF;

  UPDATE user_data
  SET
    required_tasks = jsonb_set(cur, ARRAY[p_task_title], new_task, true),
    berries_earned = COALESCE(berries_earned, 0) + add_berries,
    banked_mins = COALESCE(banked_mins, 0) + add_mins,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_required(text, text, text, int, int, int) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_update_tasks_practice.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAfUpdatePracticeTask; DailyProgressManager (SingleItemUpdate.PracticeTask).

-- BaerenEd: Update a single practice task by title. Only non-null parameters are applied.
-- When p_times_completed increases and p_stars is provided, adds (delta * p_stars) to berries_earned and (delta * stars_to_minutes) to banked_mins.
-- After updating, if all practice (optional) tasks are complete, resets all practice tasks to incomplete (times_completed 0, etc.)
-- so the app can show them as doable again without any app-side state. Requires http extension for config fetch.
-- Call: POST /rest/v1/rpc/af_update_tasks_practice with body e.g.
--   {"p_profile": "TE", "p_task_title": "Time Telling", "p_times_completed": 2, "p_stars": 3, "p_correct": 10, "p_incorrect": 0, "p_questions_answered": 10}

CREATE OR REPLACE FUNCTION af_update_tasks_practice(
  p_profile text,
  p_task_title text,
  p_times_completed int DEFAULT NULL,
  p_stars int DEFAULT NULL,
  p_correct int DEFAULT NULL,
  p_incorrect int DEFAULT NULL,
  p_questions_answered int DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  cur jsonb;
  existing jsonb;
  new_task jsonb;
  old_tc int;
  new_tc int;
  delta int;
  add_berries int := 0;
  add_mins int := 0;
  updated_practice jsonb;
  config_json jsonb;
  http_status int;
  github_url text;
  optional_titles text[];
  all_complete boolean;
  reset_practice jsonb;
  tit text;
BEGIN
  SELECT COALESCE(practice_tasks, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;
  existing := cur->p_task_title;
  old_tc := COALESCE((existing->>'times_completed')::int, 0);
  -- DB-owned increment: when app omits p_times_completed, treat completion RPC as +1.
  new_tc := CASE WHEN p_times_completed IS NOT NULL THEN p_times_completed ELSE old_tc + 1 END;

  new_task := COALESCE(existing, '{}'::jsonb)
    || jsonb_build_object(
      'times_completed', to_jsonb(new_tc),
      -- DB-owned accumulation for completion event metrics.
      'correct', CASE WHEN p_correct IS NOT NULL THEN to_jsonb(COALESCE((existing->>'correct')::int, 0) + p_correct) ELSE existing->'correct' END,
      'incorrect', CASE WHEN p_incorrect IS NOT NULL THEN to_jsonb(COALESCE((existing->>'incorrect')::int, 0) + p_incorrect) ELSE existing->'incorrect' END,
      'questions_answered', CASE WHEN p_questions_answered IS NOT NULL THEN to_jsonb(COALESCE((existing->>'questions_answered')::int, 0) + p_questions_answered) ELSE existing->'questions_answered' END
    );
  new_task := new_task || (COALESCE(existing, '{}'::jsonb) - 'times_completed' - 'correct' - 'incorrect' - 'questions_answered');

  updated_practice := jsonb_set(cur, ARRAY[p_task_title], new_task, true);

  IF p_stars IS NOT NULL AND p_stars > 0 AND new_tc > old_tc THEN
    delta := new_tc - old_tc;
    add_berries := delta * p_stars;
    add_mins := delta * af_get_stars_to_minutes(p_stars);
  END IF;

  -- If all visible practice (optional) tasks are complete, reset all to incomplete so app shows them doable again
  github_url := 'https://raw.githubusercontent.com/talq2me/BaerenEd-Android-App/refs/heads/main/app/src/main/assets/config/' || p_profile || '_config.json';
  SELECT r.status, r.content::jsonb INTO http_status, config_json FROM http_get(github_url) r LIMIT 1;
  IF http_status = 200 AND config_json IS NOT NULL THEN
    SELECT array_agg(t->>'title')
      INTO optional_titles
      FROM jsonb_array_elements(config_json->'sections') AS sec,
           jsonb_array_elements(COALESCE(sec->'tasks', '[]'::jsonb)) AS t
      WHERE sec->>'id' = 'optional'
        AND t->>'title' IS NOT NULL AND t->>'title' != '';
    IF optional_titles IS NOT NULL AND array_length(optional_titles, 1) > 0 THEN
      all_complete := true;
      FOREACH tit IN ARRAY optional_titles
      LOOP
        IF COALESCE((updated_practice->tit->>'times_completed')::int, 0) <= 0 THEN
          all_complete := false;
          EXIT;
        END IF;
      END LOOP;
      IF all_complete THEN
        SELECT jsonb_object_agg(
          t->>'title',
          jsonb_build_object(
            'times_completed', 0,
            'correct', 0,
            'incorrect', 0,
            'questions_answered', 0,
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
        INTO reset_practice
        FROM jsonb_array_elements(config_json->'sections') AS sec,
             jsonb_array_elements(COALESCE(sec->'tasks', '[]'::jsonb)) AS t
        WHERE sec->>'id' = 'optional' AND t->>'title' IS NOT NULL AND t->>'title' != '';
        IF reset_practice IS NOT NULL THEN
          updated_practice := reset_practice;
        END IF;
      END IF;
    END IF;
  END IF;

  UPDATE user_data
  SET
    practice_tasks = updated_practice,
    berries_earned = COALESCE(berries_earned, 0) + add_berries,
    banked_mins = COALESCE(banked_mins, 0) + add_mins,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_practice(text, text, int, int, int, int, int) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_update_tasks_bonus.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAfUpdateBonusTask; DailyProgressManager (SingleItemUpdate.BonusTask).

-- BaerenEd: Update a single bonus task by title. Same semantics as af_update_tasks_practice but for bonus_tasks column.
-- Call: POST /rest/v1/rpc/af_update_tasks_bonus with body e.g.
--   {"p_profile": "TE", "p_task_title": "Bonus Game", "p_times_completed": 1, "p_stars": 2, "p_correct": 5, "p_incorrect": 0, "p_questions_answered": 5}

CREATE OR REPLACE FUNCTION af_update_tasks_bonus(
  p_profile text,
  p_task_title text,
  p_times_completed int DEFAULT NULL,
  p_stars int DEFAULT NULL,
  p_correct int DEFAULT NULL,
  p_incorrect int DEFAULT NULL,
  p_questions_answered int DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  cur jsonb;
  existing jsonb;
  new_task jsonb;
  old_tc int;
  new_tc int;
  delta int;
  add_berries int := 0;
  add_mins int := 0;
BEGIN
  SELECT COALESCE(bonus_tasks, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;
  existing := cur->p_task_title;
  old_tc := COALESCE((existing->>'times_completed')::int, 0);
  -- DB-owned increment: when app omits p_times_completed, treat completion RPC as +1.
  new_tc := CASE WHEN p_times_completed IS NOT NULL THEN p_times_completed ELSE old_tc + 1 END;

  new_task := COALESCE(existing, '{}'::jsonb)
    || jsonb_build_object(
      'times_completed', to_jsonb(new_tc),
      -- DB-owned accumulation for completion event metrics.
      'correct', CASE WHEN p_correct IS NOT NULL THEN to_jsonb(COALESCE((existing->>'correct')::int, 0) + p_correct) ELSE existing->'correct' END,
      'incorrect', CASE WHEN p_incorrect IS NOT NULL THEN to_jsonb(COALESCE((existing->>'incorrect')::int, 0) + p_incorrect) ELSE existing->'incorrect' END,
      'questions_answered', CASE WHEN p_questions_answered IS NOT NULL THEN to_jsonb(COALESCE((existing->>'questions_answered')::int, 0) + p_questions_answered) ELSE existing->'questions_answered' END
    );
  new_task := new_task || (COALESCE(existing, '{}'::jsonb) - 'times_completed' - 'correct' - 'incorrect' - 'questions_answered');

  IF p_stars IS NOT NULL AND p_stars > 0 AND new_tc > old_tc THEN
    delta := new_tc - old_tc;
    add_berries := delta * p_stars;
    add_mins := delta * af_get_stars_to_minutes(p_stars);
  END IF;

  UPDATE user_data
  SET
    bonus_tasks = jsonb_set(cur, ARRAY[p_task_title], new_task, true),
    berries_earned = COALESCE(berries_earned, 0) + add_berries,
    banked_mins = COALESCE(banked_mins, 0) + add_mins,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_bonus(text, text, int, int, int, int, int) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_update_task_completion.sql
-- -----------------------------------------------------------------------------
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


-- -----------------------------------------------------------------------------
-- FILE: af_update_tasks_checklist_items.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAfUpdateChecklistItem; DailyProgressManager (SingleItemUpdate.ChecklistItem).

-- BaerenEd: Update a single checklist item by label. Sets done for the item.
-- When p_done is true and item was not done, adds item's stars (from DB) to berries_earned and to banked_mins.
-- Requires af_get_stars_to_minutes (af_get_stars_to_minutes.sql). Identifies item by p_item_label (key in checklist_items JSONB).
-- Call: POST /rest/v1/rpc/af_update_tasks_checklist_items with body e.g.
--   {"p_profile": "TE", "p_item_label": "Laundry", "p_done": true}

CREATE OR REPLACE FUNCTION af_update_tasks_checklist_items(
  p_profile text,
  p_item_label text,
  p_done boolean
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  cur jsonb;
  existing jsonb;
  new_item jsonb;
  old_done boolean;
  item_stars int;
  add_berries int := 0;
  add_mins int := 0;
BEGIN
  SELECT COALESCE(checklist_items, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;
  existing := cur->p_item_label;
  old_done := COALESCE((existing->>'done')::boolean, false);

  new_item := COALESCE(existing, '{}'::jsonb) || jsonb_build_object('done', p_done);

  IF p_done AND NOT old_done THEN
    item_stars := COALESCE((new_item->>'stars')::int, 0);
    IF item_stars > 0 THEN
      add_berries := item_stars;
      add_mins := af_get_stars_to_minutes(item_stars);
    END IF;
  END IF;

  UPDATE user_data
  SET
    checklist_items = jsonb_set(cur, ARRAY[p_item_label], new_item, true),
    berries_earned = COALESCE(berries_earned, 0) + add_berries,
    banked_mins = COALESCE(banked_mins, 0) + add_mins,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_checklist_items(text, text, boolean) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_update_tasks_chores.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAfUpdateChore; DailyProgressManager (SingleItemUpdate.Chore).

-- BaerenEd: Update a single chore by chore_id. Sets done for that chore in the chores JSONB array.
-- When p_done is true and chore was not done, adds chore's coins_reward (from DB) to coins_earned.
-- Call: POST /rest/v1/rpc/af_update_tasks_chores with body e.g.
--   {"p_profile": "TE", "p_chore_id": 1, "p_done": true}

CREATE OR REPLACE FUNCTION af_update_tasks_chores(
  p_profile text,
  p_chore_id int,
  p_done boolean
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  cur jsonb;
  new_chores jsonb;
  old_done boolean;
  chore_coins int;
  add_coins int := 0;
BEGIN
  SELECT COALESCE(chores, '[]'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;

  SELECT (e->>'done')::boolean, COALESCE((e->>'coins_reward')::int, 0)
    INTO old_done, chore_coins
  FROM jsonb_array_elements(cur) e
  WHERE (e->>'chore_id')::int = p_chore_id
  LIMIT 1;
  old_done := COALESCE(old_done, false);

  IF p_done AND NOT old_done AND chore_coins > 0 THEN
    add_coins := chore_coins;
  END IF;

  new_chores := (
    SELECT jsonb_agg(
      CASE WHEN (elem->>'chore_id')::int = p_chore_id THEN
        elem || jsonb_build_object('done', p_done)
      ELSE
        elem
      END
      ORDER BY ord
    )
    FROM jsonb_array_elements(cur) WITH ORDINALITY AS t(elem, ord)
  );

  UPDATE user_data
  SET
    chores = COALESCE(new_chores, '[]'::jsonb),
    coins_earned = COALESCE(coins_earned, 0) + add_coins,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_chores(text, int, boolean) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_update_game_index.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAfUpdateGameIndex; DailyProgressManager (SingleItemUpdate.GameIndex).

-- BaerenEd: Update a single key in game_indices for a profile (scalar params only).
-- If the game key is not yet in the JSON, it is added (new games get an entry on first play).
-- Call: POST /rest/v1/rpc/af_update_game_index with body {"p_profile": "TE", "p_game_key": "spellingRace", "p_index": 2}

CREATE OR REPLACE FUNCTION af_update_game_index(
  p_profile text,
  p_game_key text,
  p_index int
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  cur jsonb;
BEGIN
  SELECT COALESCE(game_indices, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;
  UPDATE user_data
  SET
    game_indices = jsonb_set(COALESCE(cur, '{}'::jsonb), ARRAY[p_game_key], to_jsonb(p_index)::jsonb, true),
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_game_index(text, text, int) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_update_pokemon_unlocked.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAfUpdatePokemonUnlocked; DailyProgressManager (SingleItemUpdate.PokemonUnlocked).

-- BaerenEd: Update pokemon_unlocked for a profile (scalar param only).
-- Call: POST /rest/v1/rpc/af_update_pokemon_unlocked with body {"p_profile": "TE", "p_pokemon_unlocked": 3}

CREATE OR REPLACE FUNCTION af_update_pokemon_unlocked(
  p_profile text,
  p_pokemon_unlocked int
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  UPDATE user_data
  SET
    pokemon_unlocked = p_pokemon_unlocked,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_pokemon_unlocked(text, int) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_update_berries_banked.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAfUpdateBerriesBanked; BattleHubActivity (post-battle berry sync).

-- BaerenEd: Update berries_earned and optionally banked_mins for a profile (scalar params only).
-- Call when berries/banked change without a task completion (e.g. battle spend, reset).
--
-- p_banked_mins: if NULL, banked_mins column is NOT updated (only berries_earned + last_updated).
-- If provided, sets banked_mins to that value (same as before).
--
-- Examples:
--   Battle spend (clear berries only): {"p_profile": "AM", "p_berries_earned": 0, "p_banked_mins": null}
--   Set both: {"p_profile": "TE", "p_berries_earned": 10, "p_banked_mins": 5}

CREATE OR REPLACE FUNCTION af_update_berries_banked(
  p_profile text,
  p_berries_earned int,
  p_banked_mins int DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF p_banked_mins IS NULL THEN
    UPDATE user_data
    SET
      berries_earned = p_berries_earned,
      last_updated = (NOW() AT TIME ZONE 'America/Toronto')
    WHERE profile = p_profile;
  ELSE
    UPDATE user_data
    SET
      berries_earned = p_berries_earned,
      banked_mins = p_banked_mins,
      last_updated = (NOW() AT TIME ZONE 'America/Toronto')
    WHERE profile = p_profile;
  END IF;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_berries_banked(text, int, int) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_reward_time_use.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeUseRewardTime ("af_reward_time_use"); RewardSelectionActivity.

-- Activates banked reward time: moves banked_mins into reward_time_expiry (Toronto).

CREATE OR REPLACE FUNCTION af_reward_time_use(p_profile TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_banked INTEGER;
BEGIN
    SELECT COALESCE(banked_mins, 0) INTO v_banked
    FROM user_data
    WHERE profile = p_profile
    FOR UPDATE;

    IF v_banked <= 0 THEN
        RETURN;
    END IF;

    UPDATE user_data
    SET reward_time_expiry = (NOW() AT TIME ZONE 'America/Toronto') + (v_banked * INTERVAL '1 minute'),
        banked_mins = 0,
        last_updated = NOW() AT TIME ZONE 'America/Toronto'
    WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_use(TEXT) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_reward_time_pause.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   (no references under app/ in this repo.)
-- Other: 000Requirements.md (BaerenLock / reward pause behaviour); wire in lock app when applicable.

-- Pauses active reward time: remaining minutes go to banked_mins; clears reward_time_expiry.

CREATE OR REPLACE FUNCTION af_reward_time_pause(p_profile TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_expiry TIMESTAMP(3);
    v_remaining INTEGER;
BEGIN
    SELECT reward_time_expiry INTO v_expiry
    FROM user_data
    WHERE profile = p_profile
    FOR UPDATE;

    IF v_expiry IS NULL THEN
        RETURN;
    END IF;

    v_remaining := GREATEST(0, CEIL(EXTRACT(EPOCH FROM (v_expiry - (NOW() AT TIME ZONE 'America/Toronto'))) / 60.0));

    UPDATE user_data
    SET banked_mins = v_remaining,
        reward_time_expiry = NULL,
        last_updated = NOW() AT TIME ZONE 'America/Toronto'
    WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_pause(TEXT) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_reward_time_expire.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   (no references under app/ in this repo.)
-- Other: 000Requirements.md (BaerenLock on expiry); reports/banked_time.html  -  fetch RPC af_reward_time_expire.

-- Clears reward_time_expiry when session has expired (Toronto now).

CREATE OR REPLACE FUNCTION af_reward_time_expire(p_profile TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE user_data
    SET reward_time_expiry = NULL,
        last_updated = NOW() AT TIME ZONE 'America/Toronto'
    WHERE profile = p_profile
      AND reward_time_expiry IS NOT NULL
      AND reward_time_expiry <= (NOW() AT TIME ZONE 'America/Toronto');
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_expire(TEXT) TO anon, authenticated, service_role;


-- -----------------------------------------------------------------------------
-- FILE: af_reward_time_add.sql
-- -----------------------------------------------------------------------------
-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAddRewardTime; MainActivity.kt (grant minutes); BattleHubActivity.kt.

-- Parent path: add minutes to banked_mins or extend active reward session.

CREATE OR REPLACE FUNCTION af_reward_time_add(p_profile TEXT, p_minutes INTEGER)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_minutes IS NULL OR p_minutes <= 0 THEN
        RETURN;
    END IF;

    UPDATE user_data
    SET reward_time_expiry = CASE
            WHEN reward_time_expiry IS NOT NULL AND reward_time_expiry > (NOW() AT TIME ZONE 'America/Toronto')
                THEN reward_time_expiry + (p_minutes * INTERVAL '1 minute')
            ELSE reward_time_expiry
        END,
        banked_mins = CASE
            WHEN reward_time_expiry IS NULL OR reward_time_expiry <= (NOW() AT TIME ZONE 'America/Toronto')
                THEN COALESCE(banked_mins, 0) + p_minutes
            ELSE banked_mins
        END,
        last_updated = NOW() AT TIME ZONE 'America/Toronto'
    WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_reward_time_add(TEXT, INTEGER) TO anon, authenticated, service_role;


