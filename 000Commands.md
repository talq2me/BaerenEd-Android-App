#Perform Backups/Restores
There is a md that explains how to do this

#Adding new Tasks to config
Once you add tasks to the kid profile, you need to run the following to add the tasks to the master list that shows in the Edit Schedule parent report (in a powershell terminal run):
    python scripts/generate_schedule_master.py