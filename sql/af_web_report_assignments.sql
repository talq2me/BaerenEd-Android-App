-- Call sites (parent reports, this repo):
--   reports/daily_progress_report.html — today's required, practice, and checklist lists.
--   reports/index.html — home progress counts and chore titles.
--   reports/schedule.html — week grid.
-- All web assignments for one profile, including ones that are off and checklist.
-- The tablet still reads the GitHub JSON configs.

CREATE OR REPLACE FUNCTION af_web_report_assignments(p_profile text)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_profile text := upper(trim(p_profile));
  v_rows jsonb;
BEGIN
  IF v_profile IS NULL OR v_profile = '' OR v_profile NOT IN ('AM', 'BM', 'TE') THEN
    RAISE EXCEPTION 'Invalid profile: %', p_profile;
  END IF;

  SELECT COALESCE(jsonb_agg(item ORDER BY section_order, sort_order), '[]'::jsonb)
  INTO v_rows
  FROM (
    SELECT
      CASE a.section
        WHEN 'required' THEN 1
        WHEN 'optional' THEN 2
        WHEN 'bonus' THEN 3
        WHEN 'checklist' THEN 4
        ELSE 5
      END AS section_order,
      a.sort_order,
      jsonb_build_object(
        'section', a.section,
        'title', a.title,
        'launch', a.launch,
        'enabled', a.enabled,
        'sortOrder', a.sort_order,
        'stars', a.stars,
        'url', a.url,
        'webGame', a.web_game,
        'totalQuestions', a.total_questions,
        'displayDays', a.display_days,
        'chromePage', a.chrome_page,
        'videoSequence', a.video_sequence,
        'video', a.video,
        'description', a.description
      ) AS item
    FROM web_assignments a
    WHERE a.profile = v_profile
  ) listed;

  RETURN COALESCE(v_rows, '[]'::jsonb);
END;
$$;

GRANT EXECUTE ON FUNCTION af_web_report_assignments(text) TO anon, authenticated, service_role;
