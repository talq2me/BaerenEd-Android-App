-- Call site: web/index.html loadProfile.
-- Today's visible assignments for one profile. Checklist stays in the table and is not listed.

CREATE OR REPLACE FUNCTION af_web_list_tasks(p_profile text)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_profile text := upper(trim(p_profile));
  v_today text;
  v_rows jsonb;
BEGIN
  IF v_profile IS NULL OR v_profile = '' OR v_profile NOT IN ('AM', 'BM', 'TE') THEN
    RAISE EXCEPTION 'Invalid profile: %', p_profile;
  END IF;

  v_today := (ARRAY['sun','mon','tue','wed','thu','fri','sat'])[
    extract(dow FROM (NOW() AT TIME ZONE 'America/Toronto'))::int + 1
  ];

  SELECT COALESCE(jsonb_agg(item ORDER BY section_order, sort_order), '[]'::jsonb)
  INTO v_rows
  FROM (
    SELECT
      CASE a.section
        WHEN 'required' THEN 1
        WHEN 'optional' THEN 2
        WHEN 'bonus' THEN 3
        ELSE 4
      END AS section_order,
      a.sort_order,
      jsonb_build_object(
        'section', a.section,
        'title', a.title,
        'launch', a.launch,
        'stars', a.stars,
        'url', a.url,
        'webGame', a.web_game,
        'totalQuestions', a.total_questions,
        'chromePage', a.chrome_page,
        'videoSequence', a.video_sequence,
        'video', a.video
      ) AS item
    FROM web_assignments a
    WHERE a.profile = v_profile
      AND a.enabled
      AND a.section <> 'checklist'
      AND (
        a.display_days IS NULL
        OR btrim(a.display_days) = ''
        OR position(v_today IN lower(a.display_days)) > 0
      )
  ) listed;

  RETURN COALESCE(v_rows, '[]'::jsonb);
END;
$$;

GRANT EXECUTE ON FUNCTION af_web_list_tasks(text) TO anon, authenticated, service_role;
