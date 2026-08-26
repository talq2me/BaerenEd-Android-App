-- Call sites (BaerenEd Android, this repo):
--   app/src/main/java/com/talq2me/baerened/SupabaseInterface.kt  -  invokeAfCleanupOldChoreVideos
--   Called before each chore video Storage upload, and before each image_uploads upsert
--   (chore photos, chore audio, spelling, handwriting).

-- BaerenEd: Delete all image_uploads rows older than p_days (default 7).
-- Returns chore-videos object names older than p_days so the app can delete
-- them via the Storage API (direct DELETE FROM storage.objects is blocked).
-- Does not touch behavior_log or other data tables.
-- POST /rest/v1/rpc/af_cleanup_old_chore_videos
--   {"p_days": 7}

CREATE OR REPLACE FUNCTION af_cleanup_old_chore_videos(p_days integer DEFAULT 7)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, storage
AS $$
DECLARE
  days int := GREATEST(1, COALESCE(p_days, 7));
  deleted_rows int := 0;
  storage_paths text[] := ARRAY[]::text[];
BEGIN
  SELECT coalesce(array_agg(name), ARRAY[]::text[])
  INTO storage_paths
  FROM storage.objects
  WHERE bucket_id = 'chore-videos'
    AND created_at < (NOW() - make_interval(days => days));

  DELETE FROM image_uploads
  WHERE capture_date_time < ((NOW() AT TIME ZONE 'America/Toronto') - make_interval(days => days));
  GET DIAGNOSTICS deleted_rows = ROW_COUNT;

  RETURN jsonb_build_object(
    'days', days,
    'storage_paths', to_jsonb(storage_paths),
    'deleted_image_uploads', deleted_rows
  );
END;
$$;

GRANT EXECUTE ON FUNCTION af_cleanup_old_chore_videos(integer) TO anon, authenticated, service_role;
