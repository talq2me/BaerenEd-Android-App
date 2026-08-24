-- Call sites (parent HTML reports):
--   reports/collector_cards.html — mark N oldest unpaid collector-card days as paid out.
--
-- Marks the oldest unpaid collector_card_days rows for p_profile (by completion_date).
-- Returns jsonb: { profile, requested, paid_out_count, remaining_unpaid }.

DROP FUNCTION IF EXISTS af_payout_collector_cards(text, int);

CREATE OR REPLACE FUNCTION af_payout_collector_cards(
  p_profile text,
  p_count int
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_count int := GREATEST(COALESCE(p_count, 0), 0);
  v_paid int := 0;
  v_remaining int := 0;
  v_now timestamp(3) := (NOW() AT TIME ZONE 'America/Toronto');
BEGIN
  IF v_count > 0 THEN
    WITH to_pay AS (
      SELECT id
      FROM collector_card_days
      WHERE profile = p_profile
        AND paid_out = false
      ORDER BY completion_date ASC, id ASC
      LIMIT v_count
    ),
    updated AS (
      UPDATE collector_card_days c
      SET
        paid_out = true,
        paid_out_at = v_now
      FROM to_pay t
      WHERE c.id = t.id
      RETURNING c.id
    )
    SELECT COUNT(*) INTO v_paid FROM updated;
  END IF;

  SELECT COUNT(*) INTO v_remaining
  FROM collector_card_days
  WHERE profile = p_profile
    AND paid_out = false;

  RETURN jsonb_build_object(
    'profile', p_profile,
    'requested', v_count,
    'paid_out_count', v_paid,
    'remaining_unpaid', v_remaining
  );
END;
$$;

GRANT EXECUTE ON FUNCTION af_payout_collector_cards(text, int) TO anon, authenticated, service_role;
