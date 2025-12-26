-- Supabase Database Setup for BaerenEd Cloud Storage
-- Run this SQL in your Supabase SQL Editor to create the necessary table

-- Create the user_data table to store all user settings and preferences
CREATE TABLE IF NOT EXISTS user_data (
    id BIGSERIAL PRIMARY KEY,
    profile TEXT NOT NULL, -- "AM" or "BM"
    pin TEXT,
    email TEXT,
    admin_pin TEXT,
    
    -- Daily progress data (stored as JSONB for flexibility)
    completed_tasks JSONB DEFAULT '{}'::jsonb,
    completed_task_names JSONB DEFAULT '{}'::jsonb,
    last_reset_date TEXT,
    total_possible_stars INTEGER DEFAULT 0,
    banked_reward_minutes INTEGER DEFAULT 0,
    
    -- Pokemon data
    pokemon_unlocked INTEGER DEFAULT 0,
    last_pokemon_unlock_date TEXT,
    
    -- Game progress (stored as JSONB: { "gameId": index })
    game_progress JSONB DEFAULT '{}'::jsonb,
    
    -- Video progress (stored as JSONB: { "videoFile": index })
    video_progress JSONB DEFAULT '{}'::jsonb,
    
    -- Time tracking sessions (stored as JSONB array)
    daily_sessions JSONB DEFAULT '[]'::jsonb,
    last_time_tracker_reset TEXT,
    
    -- Metadata
    last_updated BIGINT DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    device_id TEXT,
    
    -- Ensure one record per profile
    UNIQUE(profile)
);

-- Create index on profile for faster lookups
CREATE INDEX IF NOT EXISTS idx_user_data_profile ON user_data(profile);

-- Enable Row Level Security (RLS) - adjust policies based on your security needs
ALTER TABLE user_data ENABLE ROW LEVEL SECURITY;

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
    NEW.last_updated = EXTRACT(EPOCH FROM NOW()) * 1000;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger to automatically update last_updated
CREATE TRIGGER update_user_data_timestamp
    BEFORE UPDATE ON user_data
    FOR EACH ROW
    EXECUTE FUNCTION update_last_updated();


