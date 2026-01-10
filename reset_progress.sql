-- Reset Daily Progress SQL Script
-- This script resets daily progress for a specific profile or all profiles
-- Run this in Supabase SQL Editor to reset progress in the cloud database
-- 
-- Usage:
--   To reset for a specific profile (e.g., "AM"):
--   SELECT reset_profile_progress('AM');
--
--   To reset for all profiles:
--   SELECT reset_all_profiles_progress();

-- Function to reset progress for a specific profile
CREATE OR REPLACE FUNCTION reset_profile_progress(target_profile TEXT)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE user_data SET
        -- Clear all required tasks (mark all as incomplete)
        required_tasks = '{}'::jsonb,
        
        -- Clear checklist items done status (keep structure, set done=false for all items)
        checklist_items = (
            SELECT jsonb_object_agg(
                key,
                jsonb_set(
                    value,
                    '{done}',
                    'false'::jsonb
                )
            )
            FROM jsonb_each(checklist_items)
        ),
        
        -- Reset berries earned
        berries_earned = 0,
        
        -- Reset banked reward minutes
        banked_mins = 0,
        
        -- Update reset timestamp
        last_reset = NOW() AT TIME ZONE 'EST',
        last_updated = NOW() AT TIME ZONE 'EST'
    WHERE profile = target_profile;
    
    RAISE NOTICE 'Progress reset completed for profile: %', target_profile;
END;
$$;

-- Function to reset progress for all profiles
CREATE OR REPLACE FUNCTION reset_all_profiles_progress()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE user_data SET
        -- Clear all required tasks (mark all as incomplete)
        required_tasks = '{}'::jsonb,
        
        -- Clear checklist items done status (keep structure, set done=false for all items)
        checklist_items = (
            SELECT jsonb_object_agg(
                key,
                jsonb_set(
                    value,
                    '{done}',
                    'false'::jsonb
                )
            )
            FROM jsonb_each(checklist_items)
        ),
        
        -- Reset berries earned
        berries_earned = 0,
        
        -- Reset banked reward minutes
        banked_mins = 0,
        
        -- Update reset timestamp
        last_reset = NOW() AT TIME ZONE 'EST',
        last_updated = NOW() AT TIME ZONE 'EST';
    
    RAISE NOTICE 'Progress reset completed for all profiles';
END;
$$;
