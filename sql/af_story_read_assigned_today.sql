-- Call sites (BaerenEd Android, this repo):
--   sql/af_get_tasks_required.sql
--   sql/af_update_tasks_from_config_required.sql
--   sql/af_get_current_required_tasks.sql

-- storyRead task.url: bookId?start=YYYY-MM-DD&days=0,1-3,4-8
-- p_next_page is game_indices['storyRead_<bookId>'] (0/missing = 1).
-- Returns true when the kid has a non-empty catch-up session today, or the schedule is past.

DROP FUNCTION IF EXISTS af_story_read_assigned_today(text, date, int);

CREATE OR REPLACE FUNCTION af_story_read_assigned_today(
  p_url text,
  p_today date,
  p_next_page int
)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
  v_q text;
  v_start text;
  v_days text;
  v_start_date date;
  v_day_index int;
  v_next int;
  v_due_end int := 0;
  v_token text;
  v_end int;
  v_i int;
  v_days_arr text[];
  v_dash int;
  v_n int;
BEGIN
  IF p_url IS NULL OR btrim(p_url) = '' THEN
    RETURN true;
  END IF;
  v_next := COALESCE(NULLIF(p_next_page, 0), 1);
  v_q := substring(p_url from '\?(.*)$');
  IF v_q IS NULL OR v_q = '' THEN
    RETURN true;
  END IF;

  v_start := (regexp_match(v_q, '(?:^|&)start=([^&]*)'))[1];
  v_days := (regexp_match(v_q, '(?:^|&)days=([^&]*)'))[1];

  IF v_days IS NULL OR btrim(v_days) = '' THEN
    RETURN true;
  END IF;

  IF v_start IS NOT NULL AND btrim(v_start) <> '' THEN
    BEGIN
      v_start_date := v_start::date;
    EXCEPTION WHEN others THEN
      v_start_date := NULL;
    END;
  END IF;

  IF v_start_date IS NOT NULL AND p_today < v_start_date THEN
    RETURN false;
  END IF;

  v_days_arr := string_to_array(v_days, ',');
  v_n := COALESCE(array_length(v_days_arr, 1), 0);

  IF v_start_date IS NULL THEN
    v_day_index := GREATEST(v_n - 1, 0);
  ELSE
    v_day_index := (p_today - v_start_date);
  END IF;

  IF v_day_index >= v_n THEN
    RETURN true;
  END IF;

  FOR v_i IN 1..LEAST(v_day_index + 1, v_n) LOOP
    v_token := btrim(COALESCE(v_days_arr[v_i], ''));
    IF v_token = '' OR v_token = '0' THEN
      CONTINUE;
    END IF;
    v_dash := position('-' in v_token);
    BEGIN
      IF v_dash > 0 THEN
        v_end := substring(v_token from v_dash + 1)::int;
      ELSE
        v_end := v_token::int;
      END IF;
    EXCEPTION WHEN others THEN
      v_end := 0;
    END;
    IF v_end > v_due_end THEN
      v_due_end := v_end;
    END IF;
  END LOOP;

  RETURN v_due_end >= v_next;
END;
$$;

GRANT EXECUTE ON FUNCTION af_story_read_assigned_today(text, date, int) TO anon, authenticated, service_role;
