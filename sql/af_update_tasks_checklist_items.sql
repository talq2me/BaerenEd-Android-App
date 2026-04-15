-- Call sites (BaerenEd Android, this repo):
--   SupabaseInterface.invokeAfUpdateChecklistItem; DailyProgressManager (SingleItemUpdate.ChecklistItem).

-- BaerenEd: Update a single checklist item by label. Sets done for the item.
-- When p_done is true and item was not done, adds item's stars (from DB) to berries_earned and to banked_mins.
-- Requires af_get_stars_to_minutes (af_get_stars_to_minutes.sql). Identifies item by p_item_label (key in checklist_items JSONB).
-- Call: POST /rest/v1/rpc/af_update_tasks_checklist_items with body e.g.
--   {"p_profile": "TE", "p_item_label": "Laundry", "p_done": true}

CREATE OR REPLACE FUNCTION af_update_tasks_checklist_items(
  p_profile text,
  p_item_label text,
  p_done boolean
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  cur jsonb;
  existing jsonb;
  new_item jsonb;
  old_done boolean;
  item_stars int;
  add_berries int := 0;
  add_mins int := 0;
BEGIN
  SELECT COALESCE(checklist_items, '{}'::jsonb) INTO cur FROM user_data WHERE profile = p_profile;
  existing := cur->p_item_label;
  old_done := COALESCE((existing->>'done')::boolean, false);

  new_item := COALESCE(existing, '{}'::jsonb) || jsonb_build_object('done', p_done);

  IF p_done AND NOT old_done THEN
    item_stars := COALESCE((new_item->>'stars')::int, 0);
    IF item_stars > 0 THEN
      add_berries := item_stars;
      add_mins := af_get_stars_to_minutes(item_stars);
    END IF;
  END IF;

  UPDATE user_data
  SET
    checklist_items = jsonb_set(cur, ARRAY[p_item_label], new_item, true),
    berries_earned = COALESCE(berries_earned, 0) + add_berries,
    banked_mins = COALESCE(banked_mins, 0) + add_mins,
    last_updated = (NOW() AT TIME ZONE 'America/Toronto')
  WHERE profile = p_profile;
END;
$$;

GRANT EXECUTE ON FUNCTION af_update_tasks_checklist_items(text, text, boolean) TO anon, authenticated, service_role;
