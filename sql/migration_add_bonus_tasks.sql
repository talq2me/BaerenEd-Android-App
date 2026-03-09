-- Add bonus_tasks JSONB to user_data. Practice (optional) and bonus sections are stored separately
-- so practice map "all complete" reset only affects optional tasks.
-- practice_tasks = section id "optional"; bonus_tasks = section id "bonus".

ALTER TABLE user_data
  ADD COLUMN IF NOT EXISTS bonus_tasks JSONB DEFAULT '{}'::jsonb;

COMMENT ON COLUMN user_data.bonus_tasks IS 'Bonus Training Map task progress: task title -> { times_completed, correct, incorrect, questions_answered, ... }. Separate from practice_tasks (Extra Practice Map).';
