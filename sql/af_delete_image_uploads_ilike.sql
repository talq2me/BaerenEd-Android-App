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
