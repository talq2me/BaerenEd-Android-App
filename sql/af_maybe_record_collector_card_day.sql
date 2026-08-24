-- Call sites: af_update_task_completion (required), af_update_tasks_checklist_items (mark done).
--
-- When all visible required work for today is done (same definition as
-- af_get_required_progress_today — checklist with stars<=0 excluded), upsert one
-- collector_card_days row for today's Toronto date. Idempotent per profile/day.

DROP FUNCTION IF EXISTS af_maybe_record_collector_card_day(text);

CREATE OR REPLACE FUNCTION af_maybe_record_collector_card_day(p_profile text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  progress jsonb;
  all_done boolean;
  today date;
BEGIN
  progress := af_get_required_progress_today(p_profile);
  all_done := COALESCE((progress->>'all_done')::boolean, false);
  IF NOT all_done THEN
    RETURN;
  END IF;

  today := (NOW() AT TIME ZONE 'America/Toronto')::date;

  INSERT INTO collector_card_days (profile, completion_date, earned_at, paid_out)
  VALUES (
    p_profile,
    today,
    (NOW() AT TIME ZONE 'America/Toronto'),
    false
  )
  ON CONFLICT (profile, completion_date) DO NOTHING;
END;
$$;

GRANT EXECUTE ON FUNCTION af_maybe_record_collector_card_day(text) TO anon, authenticated, service_role;
