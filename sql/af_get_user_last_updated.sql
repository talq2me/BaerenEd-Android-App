-- Call sites (BaerenEd Android, this repo): none (timestamps come from af_get_user_data / sync flow).
-- BaerenEd: af_get_user_last_updated
-- (from af_data_access_rpcs.sql)

CREATE OR REPLACE FUNCTION af_get_user_last_updated(p_profile text)
RETURNS text
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT to_char(ud.last_updated, 'YYYY-MM-DD HH24:MI:SS.MS')
  FROM user_data ud
  WHERE ud.profile = p_profile
  LIMIT 1;
$$;
GRANT EXECUTE ON FUNCTION af_get_user_last_updated(text) TO anon, authenticated, service_role;
