-- Supabase Database Setup for BaerenEd Cloud Storage
-- Run this SQL in your Supabase SQL Editor to create the necessary table
-- This script is designed to be re-runnable

-- ============================================================================
-- UPGRADE SCRIPT: Remove timezone from timestamp fields
-- ============================================================================
-- If upgrading from a version that had TIMESTAMP WITH TIME ZONE, run these
-- commands to convert all timestamp fields to TIMESTAMP(3) without timezone.
-- All timestamps are stored in EST and should be compared as EST times.
-- The code expects timestamps without timezone suffixes (e.g., no +00:00 or -05:00).
--
-- Run these ALTER statements to remove timezone from timestamp fields:
--
-- ALTER TABLE settings ALTER COLUMN last_updated TYPE TIMESTAMP(3) USING (last_updated AT TIME ZONE 'EST');
-- ALTER TABLE user_data ALTER COLUMN last_updated TYPE TIMESTAMP(3) USING (last_updated AT TIME ZONE 'EST');
-- ALTER TABLE user_data ALTER COLUMN last_reset TYPE TIMESTAMP(3) USING (last_reset AT TIME ZONE 'EST');
-- ALTER TABLE devices ALTER COLUMN last_updated TYPE TIMESTAMP(3) USING (last_updated AT TIME ZONE 'EST');
-- ALTER TABLE devices ALTER COLUMN baerenlock_last_health_check TYPE TIMESTAMP(3) USING (baerenlock_last_health_check AT TIME ZONE 'EST');
--
-- ============================================================================
-- Previous upgrade scripts (for reference):
--
-- Note: If upgrading from previous version, you may need to run:
-- ALTER TABLE user_data ALTER COLUMN last_updated TYPE TIMESTAMP(3) USING last_updated AT TIME ZONE 'UTC' AT TIME ZONE 'GMT';
-- ALTER TABLE user_data ALTER COLUMN last_reset TYPE TIMESTAMP(3) USING last_reset AT TIME ZONE 'UTC' AT TIME ZONE 'EST';
--
-- Upgrade script to add checklist_items column (run this if the column doesn't exist):
-- ALTER TABLE user_data ADD COLUMN IF NOT EXISTS checklist_items JSONB DEFAULT '{}'::jsonb;
--
-- NOTE: Health check columns were moved from settings -> user_data -> devices table
-- Health checks are device-specific, not profile-specific
-- See migrate_health_check_to_devices.sql for the latest migration script

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

    -- NOTE: BaerenLock health check information is stored in devices table (per device, not per profile)
    -- See migrate_health_check_to_devices.sql for migration if upgrading from older version

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
insert into user_data (profile) values ('AM') ON CONFLICT (profile) DO NOTHING;
insert into user_data (profile) values ('BM') ON CONFLICT (profile) DO NOTHING;
INSERT INTO user_data (profile) VALUES ('TE') ON CONFLICT (profile) DO NOTHING;

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
    aggressive_cleanup BOOLEAN DEFAULT true,
    last_updated TIMESTAMP(3) DEFAULT (NOW() AT TIME ZONE 'EST')
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

-- Optional: Create a function to automatically update last_updated timestamp for settings
CREATE OR REPLACE FUNCTION update_settings_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.last_updated = NOW() AT TIME ZONE 'EST';
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger to automatically update last_updated on settings table
CREATE TRIGGER update_settings_timestamp
    BEFORE UPDATE ON settings
    FOR EACH ROW
    EXECUTE FUNCTION update_settings_timestamp();

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

-- NOTE: Daily reset trigger has been removed.
-- Daily reset is now handled by the app code (daily_reset_process() method).
-- The app compares local.profile.last_reset with cloud.profile.last_reset to determine when to reset.
-- To manually trigger a reset, set cloud.profile.last_reset and local.profile.last_reset to now() at EST - 1 day.

-- Add devices table to store active profile per device
-- This allows BaerenLock and BaerenEd to sync the active profile between apps on the same device

-- Create the devices table
CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY, -- Android device ID (ANDROID_ID from Settings.Secure)
    device_name TEXT, -- User-friendly device name (e.g., "Samsung Galaxy Tab", "Pixel 5")
    active_profile TEXT NOT NULL DEFAULT 'AM', -- Active profile: "AM" or "BM"

    -- BaerenLock health check information (per profile/device)
    baerenlock_health_status TEXT, -- "healthy" or "unhealthy"
    baerenlock_health_issues TEXT, -- Description of health issues (e.g., "Accessibility service is disabled")
    baerenlock_last_health_check TIMESTAMP(3), -- Timestamp of last health check
    
    last_updated TIMESTAMP(3) DEFAULT (NOW() AT TIME ZONE 'EST')
);

-- Create index on device_id for faster lookups (though it's already the primary key)
CREATE INDEX IF NOT EXISTS idx_devices_device_id ON devices(device_id);

-- Enable Row Level Security (RLS)
ALTER TABLE devices ENABLE ROW LEVEL SECURITY;

-- Drop existing policy if it exists
DROP POLICY IF EXISTS "Allow all operations on devices" ON devices;

-- Allow all operations (for development)
-- In production, you should create more restrictive policies
CREATE POLICY "Allow all operations on devices" ON devices
    FOR ALL
    USING (true)
    WITH CHECK (true);

-- Create a function to automatically update last_updated timestamp for devices
CREATE OR REPLACE FUNCTION update_devices_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.last_updated = NOW() AT TIME ZONE 'EST';
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger to automatically update last_updated on devices table
CREATE TRIGGER update_devices_timestamp
    BEFORE UPDATE ON devices
    FOR EACH ROW
    EXECUTE FUNCTION update_devices_timestamp();

-- Create the image_uploads table to store photos of completed work
-- This allows parents to verify that children completed tasks (e.g., spelling on paper)
CREATE TABLE IF NOT EXISTS image_uploads (
    id BIGSERIAL PRIMARY KEY,
    profile TEXT NOT NULL, -- "AM" or "BM"
    task TEXT NOT NULL, -- Task name (e.g., "englishSpellingJumblePhoto", "frenchSpellingJumblePhoto")
    image TEXT NOT NULL, -- Base64 encoded image data
    capture_date_time TIMESTAMP(3) DEFAULT (NOW() AT TIME ZONE 'EST'), -- When the photo was taken/uploaded (in EST)
    
    -- Ensure one image per profile/task combination (new uploads overwrite previous ones)
    UNIQUE(profile, task)
);

-- Create index on profile and task for faster lookups
CREATE INDEX IF NOT EXISTS idx_image_uploads_profile_task ON image_uploads(profile, task);

-- Enable Row Level Security (RLS)
ALTER TABLE image_uploads ENABLE ROW LEVEL SECURITY;

-- Drop existing policy if it exists
DROP POLICY IF EXISTS "Allow all operations on image_uploads" ON image_uploads;

-- Allow all operations (for development)
-- In production, you should create more restrictive policies
CREATE POLICY "Allow all operations on image_uploads" ON image_uploads
    FOR ALL
    USING (true)
    WITH CHECK (true);
