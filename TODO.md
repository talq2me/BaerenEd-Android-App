\
remove 2 extra options in baerened settings>profiles


spelling gives option of loading OCR or Picture version


bank account real $$ account
daily tracking of coins earned > parent approval for earned coins > some kind of submittal for approval each week?

parent report with weekly schedule with toggle on/off to enable/disable tasks for that day


chores 4 $ report shows  chores completed today because it doesn't refresh to pull the current days complete chores**or the daily reset is not resetting these!!!

google classroom on baels tablet.



reward time:
- on going to baerenlock banked_mins used to calculate reward_expiry and set in db for profile.
- each min baerenlock checks current time against expiry and if current time > expiry lock down reward apps. if current time > expiry, show reward apps.
- to pause time, if kid goes back to baerened or baerenlock, for each minute one of these is in foreground, it will add 1 min back to expiry time. --assumes baerenlock can track if IT or BaerenEd is in the foreground and accurately time it once reward time has been granted. Does not need to grant 1 min if time has already expired, only if there is time remaining (essentially not taking  time away each min not in a reward app during reward time).

Or.. a pause button in baerenlock to pause reward time. this essentially exits reward time, takes reward apps away and blocks them again and puts a big 'reward time paused' screen on the screen. takes away the reward expiry date and puts them back into BaerenEd. Or perhaps they do this fro BaerenEd so we can just null out expiry and have BaerenLock go back into lockdown mode. should likely have a 'use reward time' button to initiate the unlock of reward apps in baerenlock. then the pause reward time could also be in baerenlock... this is better. so they control when the unlock starts and when it stops,

Flow: banked time accumulated. go to baerenlock to click 'Use Reward Time' which unlocks reward apps and puts reward_expiry in db based on num banked_mins from now EST.  Then every min baerenlock checks current time against reward_expiry and if > locks, if < keeps open. 
If kid his pause reward time: baerenLock locks reward apps and uses reward_expiry - now to get new banked_mins. It writes this banked_mins to db and then clears out the reward_expiry.
Unpause: baerenLock writes new reward_expiry and enters reward time again.
parent report to add time. now increases the reward_expiry by x mins. 
this will prevent reward mins brom being in race condition between parent report and the lock decrement each min.