-- ============================================================================
-- UPGRADE SCRIPT: Remove Automatic Timestamp Update Triggers
-- ============================================================================
-- This script removes all triggers that automatically update last_updated
-- timestamps on tables involved in cloud synchronization.
--
-- WHY: These triggers were causing sync issues because they overwrite
--      client-provided timestamps, breaking the "newest dataset wins" logic.
--      The client code explicitly manages timestamps, so these triggers
--      should not be active.
--
-- WHEN TO RUN: Run this script if you have an existing database with these
--             triggers and are experiencing sync issues where local updates
--             are not being uploaded to the cloud.
--
-- SAFE TO RUN: Yes - this script only drops triggers and functions, it does
--             not modify any data. The client code will continue to manage
--             timestamps explicitly.
--
-- ============================================================================

-- Drop all timestamp update triggers
DROP TRIGGER IF EXISTS update_user_data_timestamp ON user_data;
DROP TRIGGER IF EXISTS update_settings_timestamp ON settings;
DROP TRIGGER IF EXISTS update_devices_timestamp ON devices;

-- Drop the trigger functions (they're no longer needed)
DROP FUNCTION IF EXISTS update_last_updated();
DROP FUNCTION IF EXISTS update_settings_timestamp();
DROP FUNCTION IF EXISTS update_devices_timestamp();

-- Verify triggers are removed (optional - uncomment to check)
-- SELECT 
--     trigger_name, 
--     event_object_table, 
--     action_statement 
-- FROM information_schema.triggers 
-- WHERE trigger_name IN (
--     'update_user_data_timestamp',
--     'update_settings_timestamp',
--     'update_devices_timestamp'
-- );
-- Expected result: 0 rows (all triggers removed)

-- ============================================================================
-- WHAT CHANGED:
-- ============================================================================
-- Before: Database triggers automatically updated last_updated on every UPDATE
--         This caused timestamps to be overwritten, breaking sync logic.
--
-- After:  Client code explicitly manages all timestamps. The "newest dataset
--         wins" sync logic now works correctly because timestamps are not
--         modified by database triggers.
--
-- ============================================================================
-- NOTES:
-- ============================================================================
-- - The client code (CloudSyncService, DailyResetAndSyncManager) explicitly
--   sets last_updated timestamps when uploading data.
-- - Timestamps are compared using "newest dataset wins" logic.
-- - This change ensures that client-provided timestamps are preserved.
--
-- ============================================================================
