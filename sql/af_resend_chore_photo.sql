-- Call sites (BaerenEd reports, this repo):
--   reports/daily_progress_report.html  -  parent "Resend" after reviewing a chore photo.

-- BaerenEd: Parent rejects a chore photo — delete the image and mark the matching
-- required task incomplete (reverse berries/mins if that completion had awarded them).
-- POST /rest/v1/rpc/af_resend_chore_photo
--   {"p_profile":"TE","p_image_task":"chore_make_bed_r1_2026-08-22"}

CREATE OR REPLACE FUNCTION af_resend_chore_photo(
  p_profile text,
  p_image_task text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, storage
AS $$
DECLARE
  chore_id text;
  cur jsonb;
  task_title text;
  existing jsonb;
  old_status text;
  task_stars int;
  remove_berries int := 0;
  remove_mins int := 0;
  deleted_n int := 0;
  img_payload text;
  storage_path text;
BEGIN
  IF NULLIF(TRIM(COALESCE(p_profile, '')), '') IS NULL THEN
    RAISE EXCEPTION 'af_resend_chore_photo: p_profile is required';
  END IF;
  IF NULLIF(TRIM(COALESCE(p_image_task, '')), '') IS NULL THEN
    RAISE EXCEPTION 'af_resend_chore_photo: p_image_task is required';
  END IF;

  -- chore_{id}_r{reward}_{yyyy-MM-dd} or chore_{id}_{yyyy-MM-dd}
  chore_id := substring(p_image_task from '^chore_(.+)_r[0-9]+(?:\.[0-9]+)?_[0-9]{4}-[0-9]{2}-[0-9]{2}$');
  IF chore_id IS NULL THEN
    chore_id := substring(p_image_task from '^chore_(.+)_[0-9]{4}-[0-9]{2}-[0-9]{2}$');
  END IF;
  IF chore_id IS NULL THEN
    RAISE EXCEPTION 'af_resend_chore_photo: unrecognized image task key %', p_image_task;
  END IF;

  SELECT image INTO img_payload
  FROM image_uploads
  WHERE profile = p_profile AND task = p_image_task;

  IF img_payload LIKE 'storage:chore-videos/%' THEN
    storage_path := substring(img_payload from length('storage:chore-videos/') + 1);
    DELETE FROM storage.objects
    WHERE bucket_id = 'chore-videos' AND name = storage_path;
  END IF;

  DELETE FROM image_uploads
  WHERE profile = p_profile AND task = p_image_task;
  GET DIAGNOSTICS deleted_n = ROW_COUNT;

  SELECT COALESCE(required_tasks, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'af_resend_chore_photo: unknown profile %', p_profile;
  END IF;

  SELECT e.key INTO task_title
  FROM jsonb_each(cur) AS e(key, value)
  WHERE (e.value->>'url') ILIKE '%choreId=' || chore_id || '%'
     OR (e.value->>'url') ILIKE '%choreId%3D' || chore_id || '%'
  LIMIT 1;

  IF task_title IS NOT NULL THEN
    existing := cur->task_title;
    old_status := existing->>'status';
    IF lower(COALESCE(old_status, '')) = 'complete' THEN
      task_stars := COALESCE((existing->>'stars')::int, 0);
      IF task_stars > 0 THEN
        remove_berries := task_stars;
        remove_mins := af_get_stars_to_minutes(task_stars);
      END IF;
    END IF;

    UPDATE user_data
    SET
      required_tasks = jsonb_set(
        cur,
        ARRAY[task_title],
        (COALESCE(existing, '{}'::jsonb) || jsonb_build_object(
          'status', 'incomplete',
          'correct', 0,
          'incorrect', 0,
          'questions', 0
        )),
        true
      ),
      berries_earned = GREATEST(0, COALESCE(berries_earned, 0) - remove_berries),
      banked_mins = GREATEST(0, COALESCE(banked_mins, 0) - remove_mins),
      last_updated = (NOW() AT TIME ZONE 'America/Toronto')
    WHERE profile = p_profile;
  END IF;

  RETURN jsonb_build_object(
    'deleted_images', deleted_n,
    'chore_id', chore_id,
    'required_task_title', task_title,
    'berries_removed', remove_berries,
    'mins_removed', remove_mins
  );
END;
$$;

GRANT EXECUTE ON FUNCTION af_resend_chore_photo(text, text) TO anon, authenticated, service_role;
