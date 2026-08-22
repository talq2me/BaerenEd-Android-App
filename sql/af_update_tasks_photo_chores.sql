-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfUpdatePhotoChore.
--   app/src/main/java/com/talq2me/baerened/ChorePhotoActivity.kt  -  after image upload.

-- BaerenEd: Mark a photo chore complete by chore id. Does not grant berries, minutes, coins, or cash.
-- POST /rest/v1/rpc/af_update_tasks_photo_chores {"p_profile":"AM","p_chore_id":"unload_dishwasher"}

CREATE OR REPLACE FUNCTION af_update_tasks_photo_chores(
  p_profile text,
  p_chore_id text
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  cur jsonb;
  existing jsonb;
BEGIN
  IF NULLIF(TRIM(COALESCE(p_chore_id, '')), '') IS NULL THEN
    RAISE EXCEPTION 'af_update_tasks_photo_chores: p_chore_id is required';
  END IF;

  SELECT COALESCE(photo_chores, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;
  existing := cur->p_chore_id;
  IF existing IS NULL OR existing = 'null'::jsonb THEN
    RAISE EXCEPTION 'af_update_tasks_photo_chores: unknown chore_id % for profile %', p_chore_id, p_profile;
  END IF;

  UPDATE user_data
  SET
    photo_chores = jsonb_set(cur, ARRAY[p_chore_id], existing || jsonb_build_object('status', 'complete'), true),
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_photo_chores(text, text) TO anon, authenticated, service_role;
