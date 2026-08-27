-- Parent schedule editor: push profile config JSON (and optional schedule master) to GitHub (Contents API).
-- Call site: reports/schedule_editor.html (after user_data PATCH).
--
-- Setup (Supabase Dashboard → Project Settings → Vault):
--   1. schedule_editor_write_key  — shared secret; enter same value in reports config.
--   2. github_config_pat          — fine-grained PAT with Contents: Read and write on BaerenEd-Android-App.
--
-- GitHub branch: V3 (same branch GitHub Pages serves for config).

DROP FUNCTION IF EXISTS af_push_profile_config_to_github(text, jsonb, text);
DROP FUNCTION IF EXISTS af_push_profile_config_to_github(text, jsonb, text, jsonb);

CREATE OR REPLACE FUNCTION af_push_profile_config_to_github(
  p_profile text,
  p_config_json jsonb,
  p_write_key text,
  p_schedule_master_json jsonb DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  v_profile text := upper(trim(p_profile));
  v_expected_key text;
  v_pat text;
  v_files jsonb;
  v_file jsonb;
  v_idx int;
  v_file_count int;
  v_path text;
  v_api_url text;
  v_get_status int;
  v_get_body jsonb;
  v_sha text;
  v_content text;
  v_content_b64 text;
  v_put_request text;
  v_put_status int;
  v_put_response jsonb;
  v_paths jsonb := '[]'::jsonb;
  v_branch text := 'V3';
BEGIN
  IF v_profile NOT IN ('AM', 'BM', 'TE') THEN
    RAISE EXCEPTION 'Invalid profile: %', p_profile;
  END IF;

  IF p_config_json IS NULL OR jsonb_typeof(p_config_json) != 'object' THEN
    RAISE EXCEPTION 'p_config_json must be a JSON object';
  END IF;

  SELECT decrypted_secret INTO v_expected_key
  FROM vault.decrypted_secrets
  WHERE name = 'schedule_editor_write_key'
  LIMIT 1;

  IF v_expected_key IS NULL OR v_expected_key = '' THEN
    RAISE EXCEPTION 'schedule_editor_write_key not configured in Supabase Vault';
  END IF;

  IF p_write_key IS NULL OR p_write_key = '' OR p_write_key IS DISTINCT FROM v_expected_key THEN
    RAISE EXCEPTION 'Invalid schedule editor write key';
  END IF;

  SELECT decrypted_secret INTO v_pat
  FROM vault.decrypted_secrets
  WHERE name = 'github_config_pat'
  LIMIT 1;

  IF v_pat IS NULL OR v_pat = '' THEN
    RAISE EXCEPTION 'github_config_pat not configured in Supabase Vault';
  END IF;

  v_files := jsonb_build_array(
    jsonb_build_object(
      'path', 'app/src/main/assets/config/' || v_profile || '_config.json',
      'payload', p_config_json,
      'message', 'Schedule editor: update ' || v_profile || '_config.json'
    )
  );

  IF p_schedule_master_json IS NOT NULL AND jsonb_typeof(p_schedule_master_json) = 'object' THEN
    v_files := v_files || jsonb_build_array(
      jsonb_build_object(
        'path', 'app/src/main/assets/config/schedule_master_' || v_profile || '.json',
        'payload', p_schedule_master_json,
        'message', 'Schedule editor: update schedule_master_' || v_profile || '.json'
      )
    );
  END IF;

  v_file_count := jsonb_array_length(v_files);
  FOR v_idx IN 0 .. v_file_count - 1 LOOP
    v_file := v_files -> v_idx;
    v_path := v_file->>'path';
    v_api_url := 'https://api.github.com/repos/talq2me/BaerenEd-Android-App/contents/' || v_path || '?ref=' || v_branch;

    SELECT r.status, r.content::jsonb
    INTO v_get_status, v_get_body
    FROM extensions.http((
      'GET',
      v_api_url,
      ARRAY[
        extensions.http_header('Authorization', 'Bearer ' || v_pat),
        extensions.http_header('Accept', 'application/vnd.github+json'),
        extensions.http_header('User-Agent', 'BaerenEd-Schedule-Editor')
      ]::extensions.http_header[],
      NULL,
      NULL
    )::extensions.http_request) r;

    IF v_get_status != 200 OR v_get_body IS NULL THEN
      RAISE EXCEPTION 'GitHub GET failed for % (status %): %', v_path, COALESCE(v_get_status, -1), COALESCE(v_get_body::text, 'null');
    END IF;

    v_sha := v_get_body->>'sha';
    IF v_sha IS NULL OR v_sha = '' THEN
      RAISE EXCEPTION 'GitHub GET did not return file sha for %', v_path;
    END IF;

    v_content := jsonb_pretty(v_file->'payload');
    v_content_b64 := encode(convert_to(v_content, 'UTF8'), 'base64');

    v_put_request := jsonb_build_object(
      'message', v_file->>'message',
      'content', v_content_b64,
      'branch', v_branch,
      'sha', v_sha
    )::text;

    SELECT r.status, r.content::jsonb
    INTO v_put_status, v_put_response
    FROM extensions.http((
      'PUT',
      'https://api.github.com/repos/talq2me/BaerenEd-Android-App/contents/' || v_path,
      ARRAY[
        extensions.http_header('Authorization', 'Bearer ' || v_pat),
        extensions.http_header('Accept', 'application/vnd.github+json'),
        extensions.http_header('User-Agent', 'BaerenEd-Schedule-Editor')
      ]::extensions.http_header[],
      'application/json',
      v_put_request
    )::extensions.http_request) r;

    IF v_put_status NOT IN (200, 201) THEN
      RAISE EXCEPTION 'GitHub PUT failed for % (status %): %', v_path, COALESCE(v_put_status, -1), COALESCE(v_put_response::text, 'null');
    END IF;

    v_paths := v_paths || jsonb_build_array(v_path);
  END LOOP;

  RETURN jsonb_build_object(
    'ok', true,
    'profile', v_profile,
    'path', v_paths->>0,
    'paths', v_paths,
    'master_path', CASE WHEN jsonb_array_length(v_paths) > 1 THEN v_paths->>1 ELSE NULL END,
    'branch', v_branch,
    'commit_sha', v_put_response->'commit'->>'sha'
  );
END;
$$;

GRANT EXECUTE ON FUNCTION af_push_profile_config_to_github(text, jsonb, text, jsonb) TO anon, authenticated, service_role;
