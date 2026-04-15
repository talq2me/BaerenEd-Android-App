-- Call sites (BaerenEd Android, this repo): none (settings row via af_get_settings_row).
-- BaerenEd: af_get_settings_last_updated
-- (from af_data_access_rpcs.sql)

CREATE OR REPLACE FUNCTION af_get_settings_last_updated()
RETURNS text
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT to_char(s.last_updated, 'YYYY-MM-DD HH24:MI:SS.MS')
  FROM settings s
  WHERE s.id = 1
  LIMIT 1;
$$;
GRANT EXECUTE ON FUNCTION af_get_settings_last_updated() TO anon, authenticated, service_role;
