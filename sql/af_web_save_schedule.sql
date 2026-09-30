-- Call site: reports/schedule_editor.html on main.
-- Updates web_assignments for the web home list.
-- Does not write user_data or the GitHub config the tablet editor uses.

CREATE OR REPLACE FUNCTION af_web_save_schedule(
  p_profile text,
  p_rows jsonb,
  p_replace_checklist boolean DEFAULT false
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_profile text := upper(trim(p_profile));
  r record;
  v_launch text;
  v_section text;
  v_n int;
  v_updated boolean;
BEGIN
  IF v_profile IS NULL OR v_profile = '' OR v_profile NOT IN ('AM', 'BM', 'TE') THEN
    RAISE EXCEPTION 'Invalid profile: %', p_profile;
  END IF;
  IF p_rows IS NULL OR jsonb_typeof(p_rows) <> 'array' THEN
    RAISE EXCEPTION 'p_rows must be a json array';
  END IF;

  FOR r IN
    SELECT *
    FROM jsonb_to_recordset(p_rows) AS x(
      section text,
      title text,
      launch text,
      enabled boolean,
      display_days text,
      stars int,
      total_questions int,
      url text,
      web_game boolean,
      description text
    )
  LOOP
    v_section := lower(trim(r.section));
    IF v_section IS NULL OR v_section NOT IN ('required', 'optional') THEN
      CONTINUE;
    END IF;
    IF r.title IS NULL OR btrim(r.title) = '' THEN
      CONTINUE;
    END IF;

    v_launch := nullif(btrim(r.launch), '');
    IF v_launch IS NOT NULL THEN
      INSERT INTO web_games (launch) VALUES (v_launch) ON CONFLICT DO NOTHING;
    END IF;

    v_updated := false;

    UPDATE web_assignments
    SET enabled = COALESCE(r.enabled, enabled),
        display_days = nullif(btrim(r.display_days), ''),
        stars = r.stars,
        total_questions = r.total_questions,
        url = COALESCE(nullif(btrim(r.url), ''), url),
        web_game = COALESCE(r.web_game, web_game),
        description = COALESCE(nullif(btrim(r.description), ''), description)
    WHERE profile = v_profile
      AND section = v_section
      AND title = r.title
      AND launch IS NOT DISTINCT FROM v_launch;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n > 0 THEN
      v_updated := true;
    ELSIF v_launch IS NOT NULL
      AND (
        SELECT count(*)
        FROM web_assignments
        WHERE profile = v_profile
          AND section = v_section
          AND title = r.title
      ) = 1
    THEN
      UPDATE web_assignments
      SET enabled = COALESCE(r.enabled, enabled),
          display_days = nullif(btrim(r.display_days), ''),
          stars = r.stars,
          total_questions = r.total_questions,
          url = COALESCE(nullif(btrim(r.url), ''), url),
          web_game = COALESCE(r.web_game, web_game),
          description = COALESCE(nullif(btrim(r.description), ''), description),
          launch = v_launch
      WHERE profile = v_profile
        AND section = v_section
        AND title = r.title;
      GET DIAGNOSTICS v_n = ROW_COUNT;
      v_updated := v_n > 0;
    END IF;

    IF NOT v_updated THEN
      INSERT INTO web_assignments (
        profile, section, launch, title, enabled, sort_order, stars, url,
        web_game, total_questions, display_days, description
      )
      VALUES (
        v_profile,
        v_section,
        v_launch,
        r.title,
        COALESCE(r.enabled, true),
        COALESCE((
          SELECT max(sort_order)
          FROM web_assignments
          WHERE profile = v_profile AND section = v_section
        ), 0) + 1,
        r.stars,
        nullif(btrim(r.url), ''),
        COALESCE(r.web_game, false),
        r.total_questions,
        nullif(btrim(r.display_days), ''),
        nullif(btrim(r.description), '')
      );
    END IF;
  END LOOP;

  IF COALESCE(p_replace_checklist, false) THEN
    DELETE FROM web_assignments a
    WHERE a.profile = v_profile
      AND a.section = 'checklist'
      AND NOT EXISTS (
        SELECT 1
        FROM jsonb_to_recordset(p_rows) AS x(section text, title text)
        WHERE lower(trim(x.section)) = 'checklist'
          AND x.title = a.title
      );
  END IF;
END;
$$;

GRANT EXECUTE ON FUNCTION af_web_save_schedule(text, jsonb, boolean) TO anon, authenticated, service_role;
