-- Supabase Database Setup for BaerenEd Cloud Storage
-- Run this SQL in your Supabase SQL Editor to create the necessary table
-- This script is designed to be re-runnable

-- Note: If upgrading from previous version, you may need to run:
-- ALTER TABLE user_data ALTER COLUMN last_updated TYPE TIMESTAMP(3) USING last_updated AT TIME ZONE 'UTC' AT TIME ZONE 'GMT';
-- ALTER TABLE user_data ALTER COLUMN last_reset TYPE TIMESTAMP(3) USING last_reset AT TIME ZONE 'UTC' AT TIME ZONE 'EST';
--
-- Upgrade script to add checklist_items column (run this if the column doesn't exist):
-- ALTER TABLE user_data ADD COLUMN IF NOT EXISTS checklist_items JSONB DEFAULT '{}'::jsonb;

-- Drop existing trigger and functions if they exist (for clean re-run)
DROP TRIGGER IF EXISTS daily_progress_reset_trigger ON user_data;
DROP TRIGGER IF EXISTS update_user_data_timestamp ON user_data;
DROP FUNCTION IF EXISTS check_daily_reset();
DROP FUNCTION IF EXISTS reset_daily_progress();
DROP FUNCTION IF EXISTS update_last_updated();

-- Drop and recreate tables (optional - uncomment if you want to start fresh)
-- DROP TABLE IF EXISTS user_data;
-- DROP TABLE IF EXISTS settings;

-- Create the user_data table to store user progress data
CREATE TABLE IF NOT EXISTS user_data (
    id BIGSERIAL PRIMARY KEY,
    profile TEXT NOT NULL, -- "AM" or "BM"

    -- Daily reset timestamp (stored in EST/UTC)
    last_reset TIMESTAMP(3),

    -- Required tasks progress (JSONB: { "taskName": { "status": "complete"/"incomplete", "correct": int, "incorrect": int, "questions": int } })
    required_tasks JSONB DEFAULT '{}'::jsonb,

    -- Practice tasks progress (JSONB: { "taskName": { "times_completed": int, "correct": int, "incorrect": int, "questions_answered": int } })
    practice_tasks JSONB DEFAULT '{}'::jsonb,

    -- Checklist items progress (JSONB: { "itemName": { "done": bool, "stars": int, "displayDays": string } })
    checklist_items JSONB DEFAULT '{}'::jsonb,

    -- Progress metrics
    possible_stars INTEGER DEFAULT 0,
    banked_mins INTEGER DEFAULT 0,
    berries_earned INTEGER DEFAULT 0,

    -- Pokemon data
    pokemon_unlocked INTEGER DEFAULT 0,

    -- Game indices for all game types (JSONB: { "gameId": index })
    -- Includes regular games, web games, and videos
    game_indices JSONB DEFAULT '{}'::jsonb,

    -- Metadata (stored in EST/UTC)
    last_updated TIMESTAMP(3) DEFAULT (NOW() AT TIME ZONE 'EST'),

    
    reward_apps TEXT, -- JSON array of package names as string
    blacklisted_apps TEXT, -- JSON array of package names as string
    white_listed_apps TEXT, -- JSON array of package names as string

    -- Ensure one record per profile
    UNIQUE(profile)
);

-- Create index on profile for faster lookups
CREATE INDEX IF NOT EXISTS idx_user_data_profile ON user_data(profile);

-- Enable Row Level Security (RLS) - adjust policies based on your security needs
ALTER TABLE user_data ENABLE ROW LEVEL SECURITY;

-- Drop existing policy if it exists
DROP POLICY IF EXISTS "Allow all operations" ON user_data;

--insert default profile data: 
insert into user_data (profile) values ('AM');
insert into user_data (profile) values ('BM');

-- Create a policy that allows all operations (for development)
-- In production, you should create more restrictive policies
CREATE POLICY "Allow all operations" ON user_data
    FOR ALL
    USING (true)
    WITH CHECK (true);

-- Optional: Create a function to automatically update last_updated timestamp
CREATE OR REPLACE FUNCTION update_last_updated()
RETURNS TRIGGER AS $$
BEGIN
    NEW.last_updated = NOW() AT TIME ZONE 'EST';
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger to automatically update last_updated
CREATE TRIGGER update_user_data_timestamp
    BEFORE UPDATE ON user_data
    FOR EACH ROW
    EXECUTE FUNCTION update_last_updated();

-- Create the settings table to store parent settings
CREATE TABLE IF NOT EXISTS settings (
    id BIGSERIAL PRIMARY KEY,
    parent_email VARCHAR(128),
    pin VARCHAR(8),
    aggressive_cleanup BOOLEAN DEFAULT true
);


-- Enable RLS
ALTER TABLE settings ENABLE ROW LEVEL SECURITY;

-- Drop existing policy if it exists
DROP POLICY IF EXISTS "Allow all operations on settings" ON settings;

-- Allow all operations (for development)
CREATE POLICY "Allow all operations on settings" ON settings
    FOR ALL
    USING (true)
    WITH CHECK (true);


--insert default settings data: 
insert into settings (parent_email, pin, aggressive_cleanup) values ('parent@gmail.com', '1234', true);

-- Create function to reset daily progress for all profiles
CREATE OR REPLACE FUNCTION reset_daily_progress()
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    reset_date TEXT;
BEGIN
    -- Get current date/time in readable format
    reset_date := TO_CHAR(NOW(), 'DD-MM-YYYY HH:MI:SS AM');

    -- Reset daily progress for all profiles
    UPDATE user_data SET
        required_tasks = '{}'::jsonb,
        practice_tasks = '{}'::jsonb,
        last_reset = NOW() AT TIME ZONE 'EST',
        berries_earned = 0,
        last_updated = NOW() AT TIME ZONE 'EST';

    -- Log the reset operation
    RAISE NOTICE 'Daily progress reset completed for all profiles at %', NOW();
END;
$$;

-- Create a cron job extension if it doesn't exist (Supabase may have pg_cron available)
-- Note: This requires pg_cron extension to be enabled in Supabase
-- You may need to enable it in your Supabase project settings or contact support

-- Create the cron job to run reset_daily_progress at midnight every day
-- SELECT cron.schedule('reset-daily-progress', '0 0 * * *', 'SELECT reset_daily_progress();');

-- Alternative: If pg_cron is not available, you can create a manual trigger
-- that checks the date on each update and resets if it's a new day

-- Create function to automatically reset progress when date changes
CREATE OR REPLACE FUNCTION check_daily_reset()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    today_date TEXT;
    stored_reset_date TEXT;
BEGIN
    -- Get current date in YYYY-MM-DD format for comparison
    today_date := CURRENT_DATE::TEXT;

    -- Only reset if the stored date is different from today
    -- This prevents resetting on every update, only resets when it's actually a new day
    -- Compare the date part of the stored timestamp with today's date (in EST)
    IF OLD.last_reset IS NULL OR DATE(OLD.last_reset AT TIME ZONE 'EST') != DATE(NOW() AT TIME ZONE 'EST') THEN
        -- It's a new day! Reset the progress in the NEW record before it's saved
        NEW.required_tasks := '{}'::jsonb;
        NEW.practice_tasks := '{}'::jsonb;
        NEW.last_reset := NOW() AT TIME ZONE 'EST';
        NEW.berries_earned := 0;
        NEW.last_updated := NOW() AT TIME ZONE 'EST';

        RAISE NOTICE 'Database auto-reset: Progress reset for profile % on new day % (was %)', NEW.profile, today_date, stored_reset_date;
    END IF;

    RETURN NEW;
END;
$$;

-- Create trigger that runs on every update to check for daily reset
CREATE TRIGGER daily_progress_reset_trigger
    BEFORE UPDATE ON user_data
    FOR EACH ROW
    EXECUTE FUNCTION check_daily_reset();

