-- Timezone patch migration: EST / America/New_York -> America/Toronto
-- Safe for existing databases with data (no table drops).
--
-- What this does:
-- 1) Updates timestamp DEFAULT expressions on existing columns to use America/Toronto.
-- 2) Recreates existing target functions in-place by taking their current definitions
--    and replacing timezone literals:
--      - AT TIME ZONE 'EST'         -> AT TIME ZONE 'America/Toronto'
--      - America/New_York           -> America/Toronto
--
-- Notes:
-- - CREATE OR REPLACE FUNCTION preserves function OIDs/privileges.
-- - Existing stored timestamp values are not rewritten; this patch updates behavior going forward.

BEGIN;

-- ---------------------------------------------------------------------------
-- 1) Patch table column defaults (only if table+column exist)
-- ---------------------------------------------------------------------------
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'user_data' AND column_name = 'last_updated'
  ) THEN
    EXECUTE 'ALTER TABLE public.user_data ALTER COLUMN last_updated SET DEFAULT (NOW() AT TIME ZONE ''America/Toronto'')';
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'settings' AND column_name = 'last_updated'
  ) THEN
    EXECUTE 'ALTER TABLE public.settings ALTER COLUMN last_updated SET DEFAULT (NOW() AT TIME ZONE ''America/Toronto'')';
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'devices' AND column_name = 'last_updated'
  ) THEN
    EXECUTE 'ALTER TABLE public.devices ALTER COLUMN last_updated SET DEFAULT (NOW() AT TIME ZONE ''America/Toronto'')';
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'image_uploads' AND column_name = 'capture_date_time'
  ) THEN
    EXECUTE 'ALTER TABLE public.image_uploads ALTER COLUMN capture_date_time SET DEFAULT (NOW() AT TIME ZONE ''America/Toronto'')';
  END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2) Patch existing function definitions in-place
-- ---------------------------------------------------------------------------
DO $$
DECLARE
  r RECORD;
  fn_def TEXT;
BEGIN
  FOR r IN
    SELECT
      p.oid,
      n.nspname,
      p.proname,
      pg_get_function_identity_arguments(p.oid) AS identity_args
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.proname = ANY (ARRAY[
        -- Reward-time RPCs
        'use_reward_time',
        'pause_reward_time',
        'expire_rewards',
        'add_reward_time',
        -- Daily reset helpers
        'af_daily_reset',
        'reset_daily_progress',
        'reset_profile_progress',
        'reset_all_profiles_progress',
        -- AF update/merge functions
        'af_update_required_task',
        'af_update_practice_task',
        'af_update_bonus_task',
        'af_update_checklist_item',
        'af_update_chore',
        'af_update_berries_banked',
        'af_update_game_index',
        'af_update_pokemon_unlocked',
        'af_required_tasks_from_config',
        'af_practice_tasks_from_config',
        'af_bonus_tasks_from_config',
        'af_checklist_items_from_config',
        'af_chores_from_github'
      ])
  LOOP
    fn_def := pg_get_functiondef(r.oid);

    -- Replace timezone literals in the function definition text
    fn_def := replace(fn_def, 'AT TIME ZONE ''EST''', 'AT TIME ZONE ''America/Toronto''');
    fn_def := replace(fn_def, 'America/New_York', 'America/Toronto');

    -- Recreate function in-place
    EXECUTE fn_def;

    RAISE NOTICE 'Patched function %.%(%)', r.nspname, r.proname, r.identity_args;
  END LOOP;
END $$;

COMMIT;

