package com.talq2me.baerened

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chores 4 $$: list of chores with checkboxes. Check = add coins_reward to coins_earned, uncheck = deduct.
 * Fetches from DB when displayed so list shows DB-backed data (session); chore toggles sync via RPC.
 */
class ChoresActivity : AppCompatActivity() {

    private lateinit var progressManager: DailyProgressManager
    private lateinit var choresListLeft: LinearLayout
    private lateinit var choresListRight: LinearLayout
    private var chores: MutableList<ChoreProgress> = mutableListOf()
    private var profile: String = "AM"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chores)

        progressManager = DailyProgressManager(this)
        profile = SettingsManager.readProfile(this) ?: "AM"
        choresListLeft = findViewById(R.id.choresListLeft)
        choresListRight = findViewById(R.id.choresListRight)

        lifecycleScope.launch(Dispatchers.IO) {
            val resetResult = DailyResetAndSyncManager(this@ChoresActivity).dailyResetProcessAndSync(profile)
            if (resetResult.isFailure) {
                val errMsg = resetResult.exceptionOrNull()?.message ?: "unknown error"
                Log.e("ChoresActivity", "Failed to load chores for profile=$profile: $errMsg", resetResult.exceptionOrNull())
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChoresActivity, "Failed to load chores from DB.", Toast.LENGTH_LONG).show()
                    chores = mutableListOf()
                    buildChoreRows()
                }
                return@launch
            }

            val fromDb = progressManager.getChores(profile).toMutableList()
            withContext(Dispatchers.Main) {
                if (fromDb.isEmpty()) {
                    Toast.makeText(this@ChoresActivity, "No chores returned by DB for today.", Toast.LENGTH_LONG).show()
                }
                chores = fromDb
                buildChoreRows()
            }
        }

        findViewById<Button>(R.id.choresDoneButton).setOnClickListener { finish() }
    }

    private fun buildChoreRows() {
        choresListLeft.removeAllViews()
        choresListRight.removeAllViews()
        chores.forEachIndexed { index, chore ->
            val checkBox = CheckBox(this).apply {
                isChecked = chore.done
                text = "${chore.description}  (+${chore.coinsReward} 🪙)"
                setOnCheckedChangeListener { _, isChecked ->
                    onChoreCheckedChanged(index, isChecked)
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 16)
                addView(checkBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val column = if (index % 2 == 0) choresListLeft else choresListRight
            column.addView(row)
        }
    }

    private fun onChoreCheckedChanged(index: Int, isChecked: Boolean) {
        if (index !in chores.indices) return
        val chore = chores[index]
        val delta = if (isChecked) chore.coinsReward else -chore.coinsReward
        progressManager.addCoinsEarned(profile, delta)
        chores[index] = chore.copy(done = isChecked)
        progressManager.saveChores(profile, chores)
        progressManager.notifyChoreUpdated(profile, chore.choreId, isChecked)
        // Set last_updated so cloud sync runs when returning to Battle Hub
        DailyResetAndSyncManager(this).advanceLocalTimestampForProfile(profile)
    }
}
