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
