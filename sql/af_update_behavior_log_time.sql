-- Call sites (reports):
--   reports/behavior_log.html — parent taps a list item to edit that event's log time.

-- Updates log_date_time for one behavior_log row (America/Toronto wall clock).
-- Returns the updated row.

CREATE OR REPLACE FUNCTION af_update_behavior_log_time(
    p_id bigint,
    p_log_date_time timestamp
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
    IF p_id IS NULL THEN
        RAISE EXCEPTION 'p_id is required';
    END IF;
    IF p_log_date_time IS NULL THEN
        RAISE EXCEPTION 'p_log_date_time is required';
    END IF;

    RETURN QUERY
    UPDATE behavior_log bl
    SET log_date_time = p_log_date_time
    WHERE bl.id = p_id
    RETURNING
        bl.id,
        bl.profile,
        bl.behavior,
        bl.category,
        bl.log_date_time;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'behavior_log row not found for id %', p_id;
    END IF;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_behavior_log_time(bigint, timestamp) TO anon, authenticated, service_role;
