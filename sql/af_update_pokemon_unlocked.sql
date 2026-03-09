-- BaerenEd: Update pokemon_unlocked for a profile (scalar param only).
-- Call: POST /rest/v1/rpc/af_update_pokemon_unlocked with body {"p_profile": "TE", "p_pokemon_unlocked": 3}

CREATE OR REPLACE FUNCTION af_update_pokemon_unlocked(
  p_profile text,
  p_pokemon_unlocked int
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  UPDATE user_data
  SET
    pokemon_unlocked = p_pokemon_unlocked,
    last_updated = (NOW() AT TIME ZONE 'America/New_York')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_pokemon_unlocked(text, int) TO anon, authenticated, service_role;
