-- Call sites: af_update_task_completion (required), af_update_tasks_checklist_items (mark done).
--
-- Summer spelling pool: when all visible required work for today is done, advance
-- engSpellingDrag and frSpellingDrag by 5 (mod pool size), at most once per calendar day (Toronto).
-- Games read these keys with ?pool=5&poolKey=... and do not write indices on session complete.

DROP FUNCTION IF EXISTS af_maybe_advance_spelling_pools(text);

CREATE OR REPLACE FUNCTION af_maybe_advance_spelling_pools(p_profile text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  progress jsonb;
  all_done boolean;
  today text;
  cur jsonb;
  last_adv text;
  en_idx int;
  fr_idx int;
  pool_size int := 100;
  step int := 5;
BEGIN
  progress := af_get_required_progress_today(p_profile);
  all_done := COALESCE((progress->>'all_done')::boolean, false);
  IF NOT all_done THEN
    RETURN;
  END IF;

  today := to_char((NOW() AT TIME ZONE 'America/Toronto')::date, 'YYYY-MM-DD');

  SELECT COALESCE(game_indices, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;
  last_adv := cur->>'_spellingPoolAdvancedOn';
  IF last_adv = today THEN
    RETURN;
  END IF;

  en_idx := COALESCE((cur->>'engSpellingDrag')::int, 0);
  fr_idx := COALESCE((cur->>'frSpellingDrag')::int, 0);

  cur := jsonb_set(cur, ARRAY['engSpellingDrag'], to_jsonb((en_idx + step) % pool_size), true);
  cur := jsonb_set(cur, ARRAY['frSpellingDrag'], to_jsonb((fr_idx + step) % pool_size), true);
  cur := jsonb_set(cur, ARRAY['_spellingPoolAdvancedOn'], to_jsonb(today), true);

  UPDATE user_data
  SET
    game_indices = cur,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_maybe_advance_spelling_pools(text) TO anon, authenticated, service_role;
