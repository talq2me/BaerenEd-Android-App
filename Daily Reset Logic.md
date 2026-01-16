BaerenEd Daily reset and sync overview

*remove trigger from db for resetting the progress, instead, lets make the settings menu item for 'reset all progress' set the cloud.profile.last_reset to now() at EST - 1 day and  local.profile.last_reset to now() at EST - 1 day which should force a reset the next time a screen is loaded*

**IMPORTANT CLARIFICATIONS:**

1. **Date Comparison**: To determine if it is "today", assume both timestamps are in EST, compare the date part to now() at EST using only that date part. All times read and written should be in EST.

2. **GitHub JSON Source of Truth**: Always check GitHub first for JSON as it is the source of truth. If the app cannot do a diff between GitHub JSON and local JSON, always pull from GitHub and overwrite local when GitHub is available.

3. **Task Preservation**: For tasks removed from JSON, remove the task from the JSON variable. Add any new tasks to the JSON variable. For any tasks updated, update only the star_count. Preserve existing complete, correct/incorrect, and times_completed progress.

4. **Sync Methods**: All or nothing operations. This allows them to be retried later when they are called again. The last_updated was already modified before the sync, so it should not be changed in update_cloud_with_local() or update_local_with_cloud() methods.

5. **Network Failures**: Retry a few times if it won't cause problems (performance issues or crashes). Otherwise, don't retry.

6. **BaerenLock App Lists**: The user_data table has columns reward_apps, blacklisted_apps, white_listed_apps for the specific profile. These fields are JSONB.

7. **Timestamp Format**: The database uses TIMESTAMP(3) format and stores now() at EST. Store the same format in local storage to make comparisons easier. Example format: 2026-01-14 11:48:34.401

8. **Settings Changes**: When settings change, update local immediately. The settings screen goes away and we load the app screen, which triggers a cloud sync that pushes the changes. No need to call update_cloud_with_local() directly from settings.

*Sync Process for BaerenEd*
The daily_reset_process() and then cloud_sync() shall run during the following events.
1. BaerenEd BattleHub Load
2. BaerenEd Trainer Map Load

variables involved in daily reset process: 
I will refer to these using the format as follows: local.profile.last_reset, cloud.profile.last_reset. 

Both in format EST: 2026-01-14 11:48:34.401. Example of the variable: local.BM.last_reset = 2026-01-14 11:48:34.401, cloud.BM.last_reset = 2026-01-14 11:48:34.401

cloud.profile.last_reset actually refers to the record matching the current profile in the cloud user_data.last_reset date/time. But For simplicity I will just call it cloud.profile.last_reset. The local.profile.last_reset refers to the local storage profile's last_reset, but I will refer to it as local.profile.last_reset.


Methods:

daily_reset_process():
---if local.profile.last_reset is today
        ---do nothing.
---else local.profile.last_reset is old, 
        ---compare with cloud.profile.last_reset
                ---cloud.profile.last_reset not available (either network problem or not found, whatever)
                    ---call reset_local()
                ---cloud.profile.last_reset is today's date
                    ---attempt cloud sync()
                ---cloud.profile.last_reset is older than today's date
                    ---call reset_local()

reset_local():
---set local.profile.last_reset = now() at EST
---reset local data (reset local.profile.berries = 0, reset local.profile.banked_mins = 0, local.profile.required_tasks set to null, local.profile.practice_tasks  set to null,  local.profile.checklist_items set to null) 
   ---set local.profile.last_updated = local.profile.last_reset
---call get_content_from_json()


cloud_sync():
---compare local.profile.last_updated with cloud.profile.last_updated
      ---if local.profile.last_updated = cloud.profile.last_updated or cloud.profile.last_updated cannot be found (due to network or some issue)
           ---do nothing
      ---if local.profile.last_updated more recent than cloud.profile.last_updated 
           ---call update_cloud_with_local()
      ---else if local.profile.last_updated older than cloud.profile.last_updated
           ---call update_local_with_cloud()

update_cloud_with_local():
   ---All or nothing operation - if any part fails, entire operation fails (allows retry later)
   ---set cloud.profile.last_reset = local.profile.last_reset, cloud.profile.last_updated = local.profile.last_updated, cloud.profile.required_tasks = local.profile.required_tasks, cloud.profile.checklist_items = local.profile.checklist_items, cloud.profile.practice_tasks = local.profile.practice_tasks, cloud.profile.berries_earned = local.profile.berries_earned, cloud.profile.banked_mins = local.profile.banked_mins, cloud.profile.game_indices = local.profile.game_indices, cloud.profile.pokemon_unlocked = local.profile.pokemon_unlocked, cloud.parent_email = local.parent_email, cloud.parent_pin = local.parent_pin, cloud.device.active_profile = local.device.active_profile
   ---Note: last_updated was already modified before calling this method, do not change it here

update_local_with_cloud():
   ---All or nothing operation - if any part fails, entire operation fails (allows retry later)
   ---set local.profile.last_reset = cloud.profile.last_reset, local.profile.last_updated = cloud.profile.last_updated, local.profile.required_tasks = cloud.profile.required_tasks, local.profile.checklist_items = cloud.profile.checklist_items, local.profile.practice_tasks = cloud.profile.practice_tasks, local.profile.berries_earned = cloud.profile.berries_earned, local.profile.banked_mins = cloud.profile.banked_mins, local.profile.game_indices = cloud.profile.game_indices, local.profile.pokemon_unlocked = cloud.profile.pokemon_unlocked, local.parent_email = cloud.parent_email, local.parent_pin = cloud.parent_pin, local.device.active_profile = cloud.device.active_profile
   ---Note: last_updated was already modified before calling this method, do not change it here

get_content_from_json():
---read the profile_config.json from github (ALWAYS check GitHub first - it is the source of truth)
    ---if github json found
        ---overwrite local copy of json with github version (if diff is not possible, always overwrite when GitHub is available)
        ---update local.profile.last_updated to now() EST
        ---use local json (now updated from GitHub)
            ---if local.required_tasks is null
                ---construct the proper jsonb for local.profile.required_tasks, local.profile.practice_tasks, local.profile.checklist_items and populate local.profile.required_tasks, local.profile.practice_tasks, local.profile.checklist_items with the jsonb for those variables
            ---else preserve any existing complete, correct/incorrect and times_completed progress and only update differences:
                ---remove tasks that no longer exist in the JSON
                ---add new tasks from the JSON
                ---update star_count for existing tasks (preserve complete, correct, incorrect, times_completed)

    ---else use local json (GitHub not available)
            ---if local.required_tasks is null
                ---construct the proper jsonb for local.profile.required_tasks, local.profile.practice_tasks, local.profile.checklist_items and populate local.profile.required_tasks, local.profile.practice_tasks, local.profile.checklist_items with the jsonb for those variables


For any Game/task/video task that uses json:
---on load 
---pull json from github (ALWAYS check GitHub first - it is the source of truth)
   ---if github json found, update local json copy (always overwrite when GitHub is available)
   ---if github json not available, use local json copy
---use local json for game
---begin game/task/video at json indices from local.profile.game_indices
---on game complete
   ---update local.profile.game_indices with new index for game json
   ---update local.profile.required_tasks or practice_tasks with complete status, questions correct, incorrect
   ---update local.profile.last_updated
   --call update_cloud_with_local()


Any time any of the following settings are changed in BaerenEd:
reward time, active profile, parent pin, parent email, unlocked pokemon
   --update the appropriate local.profile.variable with the new value and also update local.profile.last_updated
   --Note: No need to call update_cloud_with_local() directly - the settings screen closes and loads an app screen which triggers cloud_sync() automatically


*Sync process for BaerenLock*
The daily_reset_process() and then cloud_sync() are run during the following events:
1. BaerenLock main screen load/on focus

daily_reset_process():
---if local.profile.last_reset is today (compare date part only, both in EST)
        ---do nothing.
---else local.profile.last_reset is old, 
        ---compare with cloud.profile.last_reset (retry a few times if network issue, but don't retry if it causes performance problems or crashes)
                ---cloud.profile.last_reset not available (either network problem or not found, whatever) after retries
                    ---call reset_local()
                ---cloud.profile.last_reset is today's date (compare date part only, both in EST)
                    ---attempt cloud sync()
                ---cloud.profile.last_reset is older than today's date (compare date part only, both in EST)
                    ---call reset_local()


reset_local():
---set local.profile.last_reset = now() at EST
---reset local data (reset local.profile.berries = 0, reset local.profile.banked_mins = 0, cloud.profile.required_tasks set to null, cloud.profile.practice_tasks  set to null,  cloud.profile.checklist_items set to null) 
   ---set local.profile.last_updated = local.profile.last_reset



cloud_sync():
---compare local.profile.last_updated with cloud.profile.last_updated
      ---if local.profile.last_updated = cloud.profile.last_updated or cloud.profile.last_updated cannot be found (due to network or some issue)
           ---do nothing
      ---if local.profile.last_updated more recent than cloud.profile.last_updated 
           ---call update_cloud_with_local()
      ---else if local.profile.last_updated older than cloud.profile.last_updated
           ---call update_local_with_cloud()

update_cloud_with_local():
   ---All or nothing operation - if any part fails, entire operation fails (allows retry later)
   ---set cloud.profile.last_reset = local.profile.last_reset, cloud.profile.last_updated = local.profile.last_updated, cloud.profile.reward_apps = local.profile.reward_apps, cloud.profile.blacklisted_apps = local.profile.blacklisted_apps, cloud.profile.white_listed_apps = local.profile.white_listed_apps, cloud.profile.berries_earned = local.profile.berries_earned, cloud.profile.banked_mins = local.profile.banked_mins, cloud.parent_email = local.parent_email, cloud.parent_pin = local.parent_pin, cloud.device.active_profile = local.device.active_profile
   ---Note: last_updated was already modified before calling this method, do not change it here

update_local_with_cloud():
   ---All or nothing operation - if any part fails, entire operation fails (allows retry later)
   ---set local.profile.last_reset = cloud.profile.last_reset, local.profile.last_updated = cloud.profile.last_updated, local.profile.reward_apps = cloud.profile.reward_apps, local.profile.blacklisted_apps = cloud.profile.blacklisted_apps, local.profile.white_listed_apps = cloud.profile.white_listed_apps, local.profile.berries_earned = cloud.profile.berries_earned, local.profile.banked_mins = cloud.profile.banked_mins, local.parent_email = cloud.parent_email, local.parent_pin = cloud.parent_pin, local.device.active_profile = cloud.device.active_profile
   ---Note: last_updated was already modified before calling this method, do not change it here


Any time any of the following settings are changed in BaerenLock:
reward time, active profile, parent pin, parent email, whitelisted apps, blacklisted apps (blocked apps), reward apps
   --update the appropriate local.profile.variable with the new value and also update local.profile.last_updated
   --Note: No need to call update_cloud_with_local() directly - the settings screen closes and loads the main screen which triggers cloud_sync() automatically