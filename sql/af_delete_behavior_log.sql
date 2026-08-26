-- Call sites (reports):
--   reports/behavior_log.html — parent taps trash while editing a list item to delete that log row.

-- Deletes one behavior_log row by id.
-- Returns the deleted row.

CREATE OR REPLACE FUNCTION af_delete_behavior_log(
    p_id bigint
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

    RETURN QUERY
    DELETE FROM behavior_log bl
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

GRANT EXECUTE ON FUNCTION af_delete_behavior_log(bigint) TO anon, authenticated, service_role;
