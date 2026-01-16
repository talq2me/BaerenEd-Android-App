-- SQL script to remove the daily reset trigger from the database
-- This should be run in Supabase SQL Editor to remove the trigger-based reset mechanism
-- Daily reset is now handled entirely by the app code

-- Drop the trigger
DROP TRIGGER IF EXISTS daily_progress_reset_trigger ON user_data;

-- Drop the function (optional - can keep for reference, but not needed)
DROP FUNCTION IF EXISTS check_daily_reset();

-- Note: The reset_daily_progress() function is kept for manual use if needed
-- but is no longer called automatically by triggers
