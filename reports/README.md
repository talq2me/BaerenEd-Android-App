# Parent Progress Report - GitHub Pages

This is a simple HTML-based parent report viewer that connects to your Supabase database to display daily progress reports for your children.

## Features

- **Landing Page**: Select which child's report to view (AM or BM)
- **Progress Report**: Displays comprehensive daily progress including:
  - Overall progress metrics (completion rate, stars, berries)
  - Required tasks status
  - Extra practice tasks
  - Detailed task breakdown table
  - Achievements and insights

## Setup

1. **Deploy to GitHub Pages**:
   - Create a new repository or use an existing one
   - Upload `index.html` and `report.html` to the repository
   - Go to Settings → Pages
   - Select the branch and folder (usually `main` and `/root`)
   - Your site will be available at `https://yourusername.github.io/repository-name/`

2. **Configure Supabase Credentials**:
   - When you first visit the site, you'll see a configuration section
   - Enter your Supabase URL (e.g., `https://xxxxx.supabase.co`)
   - Enter your Supabase API Key (anon/public key)
   - Click "Save Configuration"
   - Your credentials are stored locally in your browser (not sent anywhere else)

## Usage

1. Visit your GitHub Pages URL
2. Configure Supabase credentials if not already done
3. Click on a child's name to view their report
4. The report will automatically fetch the latest data from Supabase

## Data Structure

The report reads from the `user_data` table in Supabase with the following structure:
- `profile`: The child's profile identifier (AM or BM)
- `required_tasks`: JSONB object with required task completion data
- `practice_tasks`: JSONB object with practice task data
- `berries_earned`: Number of berries earned
- `possible_stars`: Total possible stars
- `last_reset`: Timestamp of last daily reset

## Security Note

This page uses the Supabase REST API with your API key. The key is stored locally in your browser's localStorage. For production use, consider:
- Using Row Level Security (RLS) policies in Supabase
- Creating a read-only API key
- Implementing authentication if needed

## Customization

You can customize the report by editing `report.html`:
- Modify the styling in the `<style>` section
- Adjust the data display logic in the JavaScript section
- Add additional metrics or sections as needed

## Troubleshooting

**"Configuration Required" error**:
- Make sure you've entered your Supabase URL and API Key on the landing page
- Check that the credentials are correct

**"No data found" error**:
- Verify that the profile name (AM or BM) exists in your Supabase `user_data` table
- Check that your Supabase RLS policies allow reading the data

**Data not updating**:
- The report fetches data when the page loads
- Refresh the page to get the latest data
- Check your Supabase database to ensure data is being saved
