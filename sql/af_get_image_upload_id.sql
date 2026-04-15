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
