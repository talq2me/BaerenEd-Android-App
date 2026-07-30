-- Call sites (reports):
--   reports/behavior_log.html — parent taps a behavior button to insert a log row.

-- Inserts one behavior_log row with America/Toronto wall-clock log_date_time.
-- Returns the inserted row so the UI can refresh immediately if desired.

CREATE OR REPLACE FUNCTION af_log_behavior(
    p_profile text,
    p_behavior text,
    p_category text
)
RETURNS TABLE (
    id bigint,
    profile text,
    behavior text,
    category text,
    log_date_time timestamp
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF p_profile IS NULL OR btrim(p_profile) = '' THEN
        RAISE EXCEPTION 'p_profile is required';
    END IF;
    IF p_behavior IS NULL OR btrim(p_behavior) = '' THEN
        RAISE EXCEPTION 'p_behavior is required';
    END IF;
    IF p_category IS NULL OR btrim(p_category) = '' THEN
        RAISE EXCEPTION 'p_category is required';
    END IF;

    RETURN QUERY
    INSERT INTO behavior_log (profile, behavior, category, log_date_time)
    VALUES (
        btrim(p_profile),
        btrim(p_behavior),
        btrim(p_category),
        (NOW() AT TIME ZONE 'America/Toronto')
    )
    RETURNING
        behavior_log.id,
        behavior_log.profile,
        behavior_log.behavior,
        behavior_log.category,
        behavior_log.log_date_time;
END;
$$;

GRANT EXECUTE ON FUNCTION af_log_behavior(text, text, text) TO anon, authenticated, service_role;
