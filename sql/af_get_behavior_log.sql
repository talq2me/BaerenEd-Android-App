-- Call sites (reports):
--   reports/behavior_log.html — list mode (today) and graph mode (date range).

-- Returns behavior_log rows for a profile between inclusive start/end (America/Toronto wall clock).
-- Ordered most recent first.

CREATE OR REPLACE FUNCTION af_get_behavior_log(
    p_profile text,
    p_start_date_time timestamp,
    p_end_date_time timestamp
)
RETURNS TABLE (
    id bigint,
    profile text,
    behavior text,
    category text,
    log_date_time timestamp
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT
        bl.id,
        bl.profile,
        bl.behavior,
        bl.category,
        bl.log_date_time
    FROM behavior_log bl
    WHERE bl.profile = p_profile
      AND bl.log_date_time >= p_start_date_time
      AND bl.log_date_time < p_end_date_time
    ORDER BY bl.log_date_time DESC, bl.id DESC;
$$;

GRANT EXECUTE ON FUNCTION af_get_behavior_log(text, timestamp, timestamp) TO anon, authenticated, service_role;
