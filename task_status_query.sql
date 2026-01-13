-- Query to see task names and completion status in rows
-- Shows both required tasks and practice tasks for a specific profile

-- For a specific profile (e.g., 'AM' or 'BM'):
SELECT 
    profile,
    'required' AS task_type,
    task_name,
    COALESCE(task_data->>'status', 'N/A') AS status,
    COALESCE((task_data->>'correct')::INT, 0) AS correct,
    COALESCE((task_data->>'incorrect')::INT, 0) AS incorrect,
    COALESCE((task_data->>'questions')::INT, 0) AS questions
FROM user_data,
     jsonb_each(required_tasks) AS tasks(task_name, task_data)
WHERE profile = 'AM'  -- Change to 'BM' for the other profile
UNION ALL
SELECT 
    profile,
    'practice' AS task_type,
    task_name,
    CASE 
        WHEN COALESCE((task_data->>'times_completed')::INT, 0) > 0 THEN 'completed'
        ELSE 'not_completed'
    END AS status,
    COALESCE((task_data->>'correct')::INT, 0) AS correct,
    COALESCE((task_data->>'incorrect')::INT, 0) AS incorrect,
    COALESCE((task_data->>'questions_answered')::INT, 0) AS questions
FROM user_data,
     jsonb_each(practice_tasks) AS tasks(task_name, task_data)
WHERE profile = 'AM'  -- Change to 'BM' for the other profile
ORDER BY task_type, task_name;

-- Alternative: See all tasks for both profiles in one query
SELECT 
    profile,
    'required' AS task_type,
    task_name,
    COALESCE(task_data->>'status', 'N/A') AS status,
    COALESCE((task_data->>'correct')::INT, 0) AS correct,
    COALESCE((task_data->>'incorrect')::INT, 0) AS incorrect,
    COALESCE((task_data->>'questions')::INT, 0) AS questions
FROM user_data,
     jsonb_each(required_tasks) AS tasks(task_name, task_data)
UNION ALL
SELECT 
    profile,
    'practice' AS task_type,
    task_name,
    CASE 
        WHEN COALESCE((task_data->>'times_completed')::INT, 0) > 0 THEN 'completed'
        ELSE 'not_completed'
    END AS status,
    COALESCE((task_data->>'correct')::INT, 0) AS correct,
    COALESCE((task_data->>'incorrect')::INT, 0) AS incorrect,
    COALESCE((task_data->>'questions_answered')::INT, 0) AS questions
FROM user_data,
     jsonb_each(practice_tasks) AS tasks(task_name, task_data)
ORDER BY profile, task_type, task_name;

-- Simplified version: Just task name and status
SELECT 
    profile,
    task_name,
    CASE 
        WHEN task_type = 'required' THEN COALESCE(task_data->>'status', 'N/A')
        WHEN task_type = 'practice' THEN 
            CASE 
                WHEN COALESCE((task_data->>'times_completed')::INT, 0) > 0 THEN 'completed'
                ELSE 'not_completed'
            END
    END AS status
FROM (
    SELECT 
        profile,
        'required' AS task_type,
        task_name,
        task_data
    FROM user_data,
         jsonb_each(required_tasks) AS tasks(task_name, task_data)
    UNION ALL
    SELECT 
        profile,
        'practice' AS task_type,
        task_name,
        task_data
    FROM user_data,
         jsonb_each(practice_tasks) AS tasks(task_name, task_data)
) AS all_tasks
ORDER BY profile, task_name;
