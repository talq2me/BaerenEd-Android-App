# Perform Backups/Restores
There is a md that explains how to do this in /sql/BACKUP.md
In a gitbash terminal run: bash sql/backup_supabase_db.sh

# Adding new Tasks to config
Edit Schedule Save writes `{profile}_config.json` and `schedule_master_{profile}.json` to GitHub, so you do not need to regenerate masters after editing in that report.

To rebuild masters from local config files (powershell):
**python scripts/generate_schedule_master.py**


# Generating json for books from scans:

cd /c/Sandbox/Baeren/BaerenEd-Android-App-1/tools/story-pipeline

mkdir -p stories/my-book/pages

put the scans in this folder then run

source .venv/Scripts/activate
python -m src.pipeline

Re-run a book — delete stories/<book_id>/processed.flag and run python -m src.pipeline again.