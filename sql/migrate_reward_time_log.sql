-- One-time migration: reward session audit trail (start / pause / expiry).
-- Deploy before updated af_reward_time_use / pause / expire functions that INSERT here.
-- Timestamps: TIMESTAMP(3) America/Toronto wall time (matches user_data convention).

CREATE TABLE IF NOT EXISTS reward_time_log (
    id BIGSERIAL PRIMARY KEY,
    profile TEXT NOT NULL,
    event TEXT NOT NULL,
    reward_mins_remaining INTEGER NOT NULL,
    logged_at TIMESTAMP(3) NOT NULL DEFAULT (NOW() AT TIME ZONE 'America/Toronto')
);

CREATE INDEX IF NOT EXISTS idx_reward_time_log_profile_logged_at
    ON reward_time_log (profile, logged_at DESC);

ALTER TABLE reward_time_log ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow all operations" ON reward_time_log;

CREATE POLICY "Allow all operations" ON reward_time_log
    FOR ALL
    USING (true)
    WITH CHECK (true);
