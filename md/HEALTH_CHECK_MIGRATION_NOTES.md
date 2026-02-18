# Health Check Migration Notes

## Changes Made

Health check data has been moved from `user_data` table (per profile) to `devices` table (per device), since health checks are device-specific (accessibility service status, permissions, etc. are per device, not per profile).

## Migration Steps

1. Run `migrate_health_check_to_devices.sql` in your Supabase SQL Editor
2. This will:
   - Add health check columns to `devices` table
   - Remove health check columns from `user_data` table

## Code Changes

- `CloudSyncManager.syncHealthCheckToCloud()` now syncs to `devices` table using `device_id`
- Health check runs on `onCreate()` as well as `onResume()` to catch issues immediately
- Health check detection is more conservative - returns false when no data exists (fresh install)

## Reporting Queries That Need Updating

If you have any reporting queries that read health check data from `user_data` table, they need to be updated to read from `devices` table instead.

Example old query:
```sql
SELECT profile, baerenlock_health_status, baerenlock_health_issues 
FROM user_data 
WHERE profile IN ('AM', 'BM')
```

Example new query:
```sql
SELECT device_id, device_name, active_profile, baerenlock_health_status, baerenlock_health_issues 
FROM devices
```

## Files That Need Updating

- `reports/index.html` (line ~268) - queries `user_data` table for health check data, needs to query `devices` table instead
