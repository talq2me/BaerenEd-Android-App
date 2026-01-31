package com.talq2me.baerened

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Chores 4 $$: list of chores with checkboxes. Check = add coins_reward to coins_earned, uncheck = deduct.
 * Saving updates last_updated so cloud sync runs on return to Battle Hub.
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

        progressManager.loadChoresFromJsonIfNeeded(profile)
        chores = progressManager.getChores(profile).toMutableList()

        choresListLeft = findViewById(R.id.choresListLeft)
        choresListRight = findViewById(R.id.choresListRight)
        buildChoreRows()

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
        // Set last_updated so cloud sync runs when returning to Battle Hub
        DailyResetAndSyncManager(this).advanceLocalTimestampForProfile(profile)
    }
}
