-- BaerenEd: Shared helper for reward minutes. Same rules as DailyProgressManager.convertStarsToMinutes:
-- 1 star = 1 min, 2 stars = 3 min, 3 stars = 5 min; 4+ uses (stars/3)*5 + remainder (1->1, 2->3).
-- Deploy this once; used by af_update_required_task, af_update_practice_task, af_update_checklist_item.

CREATE OR REPLACE FUNCTION af_stars_to_minutes(p_stars int)
RETURNS int
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT CASE
    WHEN p_stars IS NULL OR p_stars <= 0 THEN 0
    WHEN p_stars = 1 THEN 1
    WHEN p_stars = 2 THEN 3
    WHEN p_stars = 3 THEN 5
    ELSE (p_stars/3)*5 + CASE p_stars % 3 WHEN 1 THEN 1 WHEN 2 THEN 3 ELSE 0 END
  END
$$;

GRANT EXECUTE ON FUNCTION af_stars_to_minutes(int) TO anon, authenticated, service_role;
