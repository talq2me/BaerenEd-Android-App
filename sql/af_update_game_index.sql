-- BaerenEd: Update a single key in game_indices for a profile (scalar params only).
-- If the game key is not yet in the JSON, it is added (new games get an entry on first play).
-- Call: POST /rest/v1/rpc/af_update_game_index with body {"p_profile": "TE", "p_game_key": "spellingRace", "p_index": 2}

CREATE OR REPLACE FUNCTION af_update_game_index(
  p_profile text,
  p_game_key text,
  p_index int
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  cur jsonb;
BEGIN
  SELECT COALESCE(game_indices, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;
  UPDATE user_data
  SET
    game_indices = jsonb_set(COALESCE(cur, '{}'::jsonb), ARRAY[p_game_key], to_jsonb(p_index)::jsonb, true),
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_game_index(text, text, int) TO anon, authenticated, service_role;
