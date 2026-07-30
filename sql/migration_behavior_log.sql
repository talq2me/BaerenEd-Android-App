-- Parent Behavior Log: table for manual behavior event logging from the reports UI.
CREATE TABLE IF NOT EXISTS behavior_log (
    id BIGSERIAL PRIMARY KEY,
    profile TEXT NOT NULL,
    behavior TEXT NOT NULL,
    category TEXT NOT NULL,
    log_date_time TIMESTAMP(3) NOT NULL DEFAULT (NOW() AT TIME ZONE 'America/Toronto')
);

CREATE INDEX IF NOT EXISTS idx_behavior_log_profile_log_date_time
    ON behavior_log (profile, log_date_time DESC);

CREATE INDEX IF NOT EXISTS idx_behavior_log_profile_category_log_date_time
    ON behavior_log (profile, category, log_date_time DESC);

ALTER TABLE behavior_log ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow all operations" ON behavior_log;

CREATE POLICY "Allow all operations" ON behavior_log
    FOR ALL
    USING (true)
    WITH CHECK (true);
