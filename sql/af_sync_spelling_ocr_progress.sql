-- Call sites:
--   Trigger image_uploads_sync_spelling_ocr_progress after a spelling OCR
--   image_uploads.task mark changes (parent report Save, or the review bot).
--
-- The daily progress report reads user_data correct/incorrect. The OCR review
-- page only rewrites the photo task suffix (✓ or X). This copies today's photo
-- totals onto the matching required, practice, or bonus task.
-- It does not change completion, stars, berries, or times_completed.
-- A review of an older day does not replace the current day's score.

CREATE OR REPLACE FUNCTION af_merge_spelling_ocr_counts(
  p_tasks jsonb,
  p_title_re text,
  p_correct int,
  p_incorrect int,
  p_total int,
  p_practice boolean
)
RETURNS jsonb
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
  rec record;
  v_tasks jsonb := p_tasks;
  v_scored boolean;
BEGIN
  IF v_tasks IS NULL OR v_tasks = '{}'::jsonb THEN
    RETURN v_tasks;
  END IF;

  FOR rec IN SELECT key, value FROM jsonb_each(v_tasks)
  LOOP
    v_scored :=
      (rec.value->>'correct') IS NOT NULL
      OR (rec.value->>'questions') IS NOT NULL
      OR (rec.value->>'questions_answered') IS NOT NULL
      OR COALESCE(rec.value->>'status', '') = 'complete'
      OR COALESCE((rec.value->>'times_completed')::int, 0) > 0;

    IF rec.key ~* p_title_re AND v_scored THEN
      IF COALESCE((rec.value->>'correct')::int, -1) IS DISTINCT FROM p_correct
         OR COALESCE((rec.value->>'incorrect')::int, -1) IS DISTINCT FROM p_incorrect
         OR COALESCE((rec.value->>(CASE WHEN p_practice THEN 'questions_answered' ELSE 'questions' END))::int, -1) IS DISTINCT FROM p_total
      THEN
        v_tasks := jsonb_set(
          v_tasks,
          ARRAY[rec.key],
          rec.value || CASE
            WHEN p_practice THEN jsonb_build_object(
              'correct', p_correct,
              'incorrect', p_incorrect,
              'questions_answered', p_total
            )
            ELSE jsonb_build_object(
              'correct', p_correct,
              'incorrect', p_incorrect,
              'questions', p_total
            )
          END,
          true
        );
      END IF;
    END IF;
  END LOOP;

  RETURN v_tasks;
END;
$$;

CREATE OR REPLACE FUNCTION af_sync_spelling_ocr_progress(
  p_profile text,
  p_task text
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_profile text := upper(btrim(COALESCE(p_profile, '')));
  v_prefix text;
  v_day date;
  v_day_text text;
  v_title_re text;
  v_correct int;
  v_total int;
  v_incorrect int;
  v_required jsonb;
  v_practice jsonb;
  v_bonus jsonb;
  v_required_new jsonb;
  v_practice_new jsonb;
  v_bonus_new jsonb;
BEGIN
  IF v_profile NOT IN ('AM', 'BM', 'TE') THEN
    RETURN;
  END IF;
  IF p_task IS NULL OR p_task !~* '^(Eng|Fr)SpellingOCR-[0-9]{4}-[0-9]{2}-[0-9]{2}-' THEN
    RETURN;
  END IF;

  v_prefix := split_part(p_task, '-', 1);
  IF lower(v_prefix) = 'engspellingocr' THEN
    v_prefix := 'EngSpellingOCR';
    v_title_re := '^eng(lish)? spelling ocr';
  ELSIF lower(v_prefix) = 'frspellingocr' THEN
    v_prefix := 'FrSpellingOCR';
    v_title_re := '^(fr|french) spelling ocr';
  ELSE
    RETURN;
  END IF;

  v_day := substring(p_task from '^[A-Za-z]+-([0-9]{4}-[0-9]{2}-[0-9]{2})-')::date;
  IF v_day IS DISTINCT FROM (now() AT TIME ZONE 'America/Toronto')::date THEN
    RETURN;
  END IF;
  v_day_text := to_char(v_day, 'YYYY-MM-DD');

  SELECT
    count(*) FILTER (
      WHERE split_part(task, '-', -1) IN ('✓', '✔')
         OR lower(split_part(task, '-', -1)) IN ('correct', 'checkmark')
    )::int,
    count(*)::int
  INTO v_correct, v_total
  FROM image_uploads
  WHERE upper(profile) = v_profile
    AND task LIKE v_prefix || '-' || v_day_text || '-%';

  IF COALESCE(v_total, 0) < 1 THEN
    RETURN;
  END IF;
  v_incorrect := v_total - COALESCE(v_correct, 0);

  SELECT required_tasks, practice_tasks, bonus_tasks
  INTO v_required, v_practice, v_bonus
  FROM user_data
  WHERE profile = v_profile
  FOR UPDATE;

  IF NOT FOUND THEN
    RETURN;
  END IF;

  v_required_new := af_merge_spelling_ocr_counts(v_required, v_title_re, v_correct, v_incorrect, v_total, false);
  v_practice_new := af_merge_spelling_ocr_counts(v_practice, v_title_re, v_correct, v_incorrect, v_total, true);
  v_bonus_new := af_merge_spelling_ocr_counts(v_bonus, v_title_re, v_correct, v_incorrect, v_total, true);

  UPDATE user_data
  SET
    required_tasks = v_required_new,
    practice_tasks = v_practice_new,
    bonus_tasks = v_bonus_new,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = v_profile
    AND (
      required_tasks IS DISTINCT FROM v_required_new
      OR practice_tasks IS DISTINCT FROM v_practice_new
      OR bonus_tasks IS DISTINCT FROM v_bonus_new
    );
END;
$$;

GRANT EXECUTE ON FUNCTION af_merge_spelling_ocr_counts(jsonb, text, int, int, int, boolean) TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION af_sync_spelling_ocr_progress(text, text) TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION af_image_uploads_sync_spelling_ocr_progress()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF TG_OP = 'UPDATE'
     AND NEW.task IS DISTINCT FROM OLD.task
     AND NEW.task ~* '^(Eng|Fr)SpellingOCR-[0-9]{4}-[0-9]{2}-[0-9]{2}-'
  THEN
    PERFORM af_sync_spelling_ocr_progress(NEW.profile, NEW.task);
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS image_uploads_sync_spelling_ocr_progress ON image_uploads;
CREATE TRIGGER image_uploads_sync_spelling_ocr_progress
  AFTER UPDATE OF task ON image_uploads
  FOR EACH ROW
  EXECUTE FUNCTION af_image_uploads_sync_spelling_ocr_progress();
