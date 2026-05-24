-- Add parent-configurable reward audio monitoring settings (BaerenLock GuardianForegroundService).
ALTER TABLE settings
    ADD COLUMN IF NOT EXISTS reward_audio_monitor_enabled BOOLEAN DEFAULT true;

ALTER TABLE settings
    ADD COLUMN IF NOT EXISTS reward_audio_loudness_threshold INTEGER DEFAULT 75;
