# Cloud Storage Setup Guide

This guide explains how to set up cloud storage for BaerenEd using Supabase with **pre-configured credentials**.

## Overview

The cloud storage feature allows you to:
- Store all user settings and preferences in the cloud
- Sync data across multiple devices
- Access data from any device with the app installed
- Toggle between local and cloud storage

**Credentials are embedded in the app at build time**, so you don't need to enter them on each device!

## Setup Steps

### 1. Create a Supabase Account

1. Go to [https://supabase.com](https://supabase.com)
2. Sign up for a free account
3. Create a new project

### 2. Set Up the Database

1. In your Supabase project, go to the SQL Editor
2. Run the SQL script from `supabase_setup.sql` to create the `user_data` table
3. This will create the table structure needed to store all user data

### 3. Get Your Supabase Credentials

1. In your Supabase project, go to Settings → API
2. Copy your:
   - **Project URL** (e.g., )
   - **anon/public key** (the `anon` key, not the `service_role` key)

### 4. Configure Build Settings

1. Open `local.properties` in the project root (create it if it doesn't exist)
2. Add your Supabase credentials:

```properties
SUPABASE_URL=https://xxxxx.supabase.co
SUPABASE_KEY=
```

**Important**: `local.properties` is already in `.gitignore`, so your credentials won't be committed to the repository.

### 5. Build the App

1. Rebuild the app:
   ```bash
   ./gradlew clean build
   ```

2. The Supabase credentials will be embedded in the app's `BuildConfig`
3. Install the app on your devices - **no need to enter credentials on each device!**

### 6. Enable Cloud Storage

1. Open the BaerenEd app on any device
2. Click the "☁️ Cloud OFF" button in the header
3. The app will automatically use the pre-configured Supabase credentials
4. Cloud storage will be enabled and your data will sync

## How It Works

### Build-Time Configuration

- Supabase URL and API key are read from `local.properties` during build
- They're embedded in `BuildConfig.SUPABASE_URL` and `BuildConfig.SUPABASE_KEY`
- The app uses these values automatically - no manual entry needed

### Login Flow

When cloud storage is enabled:
1. The app starts with `LoginActivity` instead of `MainActivity`
2. You select a profile (AM or BM)
3. The app downloads data from the cloud for that profile
4. You're taken to the main screen with your synced data

### Data Synchronization

The following data is synced to the cloud:
- Profile settings (PIN, email, admin PIN)
- Daily progress (completed tasks, stars, coins)
- Game progress (last question index for each game)
- Video progress (last video index for each video list)
- Time tracking (sessions, time spent)
- Pokemon collection (unlocked count)

### Toggle Between Local and Cloud

- **Cloud OFF**: All data is stored locally only (default behavior)
- **Cloud ON**: All data is synced to/from the cloud

When you toggle:
- **OFF → ON**: Current local data is uploaded to cloud
- **ON → OFF**: Cloud sync stops, app uses local data only

## Database Schema

The `user_data` table stores:
- `profile`: "AM" or "BM"
- `pin`, `email`, `admin_pin`: User settings
- `completed_tasks`, `completed_task_names`: Daily progress
- `game_progress`: JSON object mapping game IDs to progress indices
- `video_progress`: JSON object mapping video files to indices
- `daily_sessions`: Array of time tracking sessions
- `last_updated`: Timestamp of last update

## Security Notes

- The default SQL setup allows all operations (for development)
- For production, you should create more restrictive Row Level Security (RLS) policies
- The anon key is safe to use in the app (it's public)
- Never commit your service_role key
- **Never commit `local.properties`** - it's already in `.gitignore`

## Troubleshooting

### "Supabase not configured"
- Make sure you've added `SUPABASE_URL` and `SUPABASE_KEY` to `local.properties`
- Rebuild the app after adding credentials
- Check that the values are correct (no extra spaces, correct URL format)

### "Failed to upload/download"
- Check your internet connection
- Verify your Supabase credentials are correct in `local.properties`
- Make sure the `user_data` table exists in your database
- Check Supabase logs for any errors

### Data not syncing
- Make sure cloud storage is enabled (button shows "☁️ Cloud ON")
- Try toggling cloud storage off and on again
- Check that you're logged in with the correct profile

## Free Tier Limits

Supabase free tier includes:
- 500 MB database storage
- 2 GB bandwidth per month
- 50,000 monthly active users

This should be sufficient for most use cases.

## Building for Multiple Devices

Once you've configured `local.properties` and built the app:
1. All APKs built from this project will have the credentials embedded
2. Install the same APK on all your kids' devices
3. No need to configure each device individually
4. Just enable cloud storage on each device and select the profile


