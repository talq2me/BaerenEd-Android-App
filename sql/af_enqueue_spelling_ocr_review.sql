-- Call site: web/spell.html after the last spelling drawing is stored.
-- Fires once per profile, language, and day after that many OCR images are stored.
-- Vault secret spelling_ocr_webhook_bearer is the crsr_ token only (no "Bearer " prefix).

CREATE OR REPLACE FUNCTION af_enqueue_spelling_ocr_review(
  p_profile text,
  p_language text,
  p_date date,
  p_expected_count int
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  v_profile text := upper(trim(p_profile));
  v_language text := lower(trim(p_language));
  v_prefix text;
  v_day text;
  v_count int;
  v_token text;
  v_body text;
  v_status int;
  v_claimed int;
BEGIN
  IF v_profile IS NULL OR v_profile = '' OR v_profile NOT IN ('AM', 'BM', 'TE') THEN
    RAISE EXCEPTION 'Invalid profile: %', p_profile;
  END IF;
  IF v_language NOT IN ('eng', 'fr') THEN
    RAISE EXCEPTION 'Invalid language: %', p_language;
  END IF;
  IF p_date IS NULL OR COALESCE(p_expected_count, 0) < 1 THEN
    RETURN;
  END IF;

  v_prefix := CASE v_language WHEN 'eng' THEN 'EngSpellingOCR' ELSE 'FrSpellingOCR' END;
  v_day := to_char(p_date, 'YYYY-MM-DD');

  SELECT count(*) INTO v_count
  FROM image_uploads
  WHERE profile = v_profile
    AND task LIKE v_prefix || '-' || v_day || '-%';

  IF v_count < p_expected_count THEN
    RETURN;
  END IF;

  INSERT INTO spelling_dictation_reviews (profile, review_date, language, status, webhook_sent)
  VALUES (v_profile, p_date, v_language, 'incomplete', false)
  ON CONFLICT (profile, review_date, language) DO NOTHING;

  UPDATE spelling_dictation_reviews
  SET webhook_sent = true
  WHERE profile = v_profile
    AND review_date = p_date
    AND language = v_language
    AND webhook_sent = false
    AND status IS DISTINCT FROM 'complete';

  GET DIAGNOSTICS v_claimed = ROW_COUNT;
  IF v_claimed = 0 THEN
    RETURN;
  END IF;

  SELECT decrypted_secret INTO v_token
  FROM vault.decrypted_secrets
  WHERE name = 'spelling_ocr_webhook_bearer'
  LIMIT 1;

  IF v_token IS NULL OR btrim(v_token) = '' THEN
    UPDATE spelling_dictation_reviews
    SET webhook_sent = false
    WHERE profile = v_profile
      AND review_date = p_date
      AND language = v_language
      AND status IS DISTINCT FROM 'complete';
    RAISE WARNING 'spelling_ocr_webhook_bearer not configured in Supabase Vault';
    RETURN;
  END IF;

  v_body := jsonb_build_object(
    'profile', v_profile,
    'language', v_language,
    'date', v_day
  )::text;

  BEGIN
    SELECT r.status INTO v_status
    FROM extensions.http((
      'POST',
      'https://api2.cursor.sh/automations/webhook/2cb85974-7eea-5dd6-99ad-8d521fa2e7f7',
      ARRAY[
        extensions.http_header('Authorization', 'Bearer ' || btrim(v_token)),
        extensions.http_header('Content-Type', 'application/json')
      ]::extensions.http_header[],
      'application/json',
      v_body
    )::extensions.http_request) r;
  EXCEPTION WHEN OTHERS THEN
    UPDATE spelling_dictation_reviews
    SET webhook_sent = false
    WHERE profile = v_profile
      AND review_date = p_date
      AND language = v_language
      AND status IS DISTINCT FROM 'complete';
    RAISE WARNING 'spelling OCR webhook request failed: %', SQLERRM;
    RETURN;
  END;

  IF v_status IS NULL OR v_status < 200 OR v_status >= 300 THEN
    UPDATE spelling_dictation_reviews
    SET webhook_sent = false
    WHERE profile = v_profile
      AND review_date = p_date
      AND language = v_language
      AND status IS DISTINCT FROM 'complete';
    RAISE WARNING 'spelling OCR webhook returned status %', COALESCE(v_status, -1);
  END IF;
END;
$$;

GRANT EXECUTE ON FUNCTION af_enqueue_spelling_ocr_review(text, text, date, int) TO anon, authenticated, service_role;
