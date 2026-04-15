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
