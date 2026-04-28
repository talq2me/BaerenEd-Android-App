package com.talq2me.baerened

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RewardSpinnerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RewardSpinnerActivity"
    }

    private lateinit var wheelView: RewardWheelView
    private lateinit var spinButton: Button
    private lateinit var resultText: TextView
    private var spinning = false
    private var rewards: List<SupabaseInterface.RewardSpinnerItem> = emptyList()
    private var rewardsLoaded = false
    private var dailyPrizeResult: SupabaseInterface.DailyPrizeResult? = null
    private var prizeRevealed = false
    private var resolvingPrize = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reward_spinner)

        wheelView = findViewById(R.id.rewardWheelView)
        spinButton = findViewById(R.id.spinRewardButton)
        resultText = findViewById(R.id.rewardResultText)

        findViewById<Button>(R.id.rewardSpinnerBackButton).setOnClickListener { finish() }
        spinButton.setOnClickListener {
            if (spinning) {
                Toast.makeText(this, "Already spinning...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (hasUnlockedPrize()) {
                showUnlockedPrize()
                return@setOnClickListener
            }
            if (resolvingPrize) {
                Toast.makeText(this, "Already checking…", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            resultText.text = "Checking today's prize..."
            resolveDailyPrize(fromUserTap = true)
        }
        spinButton.isEnabled = false
        spinButton.text = "Spin"

        loadRewards()
        resolveDailyPrize(fromUserTap = false)
    }

    private fun hasUnlockedPrize(): Boolean =
        !dailyPrizeResult?.prizeUnlocked.isNullOrBlank()

    private fun loadRewards() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = SupabaseInterface().getRewardSpinnerItems()
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { items ->
                        val filtered = items.filter { it.percent > 0 }
                        if (filtered.isEmpty()) {
                            spinButton.isEnabled = false
                            resultText.text = "No rewards configured."
                            wheelView.setRewards(emptyList())
                            return@fold
                        }
                        rewards = filtered
                        wheelView.setRewards(filtered)
                        rewardsLoaded = true
                        maybeRenderDailyPrizeResult()
                    },
                    onFailure = { e ->
                        spinButton.isEnabled = false
                        resultText.text = "Could not load rewards."
                        Toast.makeText(this@RewardSpinnerActivity, e.message ?: "Reward load failed", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private fun resolveDailyPrize(fromUserTap: Boolean) {
        resolvingPrize = true
        if (fromUserTap) {
            spinButton.isEnabled = false
        }
        lifecycleScope.launch {
            val rawProfile = SettingsManager.readProfile(this@RewardSpinnerActivity) ?: "AM"
            val profile = when (rawProfile) {
                "A" -> "AM"
                "B" -> "BM"
                else -> rawProfile
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    SupabaseInterface().invokeAfGetOrUnlockDailyPrize(profile)
                }
                result.fold(
                    onSuccess = { r ->
                        Log.d(
                            TAG,
                            "af_get_or_unlock_daily_prize ok: profile=$profile eligible=${r.eligible} newly=${r.newlyUnlocked} prize=${r.prizeUnlocked} err=${r.serverError}"
                        )
                        dailyPrizeResult = r
                        maybeRenderDailyPrizeResult()
                    },
                    onFailure = { e ->
                        Log.e(TAG, "af_get_or_unlock_daily_prize failed", e)
                        prizeRevealed = false
                        spinButton.isEnabled = !hasUnlockedPrize()
                        spinButton.text = "Spin"
                        resultText.text = "Could not reach server for today's prize."
                        Toast.makeText(this@RewardSpinnerActivity, e.message ?: "Daily prize failed", Toast.LENGTH_LONG).show()
                    }
                )
            } finally {
                resolvingPrize = false
            }
        }
    }

    private fun maybeRenderDailyPrizeResult() {
        val prizeResult = dailyPrizeResult ?: return

        val prize = prizeResult.prizeUnlocked?.trim().orEmpty()
        if (prize.isEmpty()) {
            prizeRevealed = false
            spinButton.isEnabled = true
            spinButton.text = "Spin"
            resultText.text = messageForNoPrizeYet(prizeResult)
            return
        }

        // Server returned a concrete prize; keep button enabled to re-show the same unlocked prize.
        spinButton.isEnabled = true
        spinButton.text = "Show Prize"
        resolvingPrize = false

        if (!rewardsLoaded) {
            resultText.text = "Today's prize: $prize (loading wheel…)"
            return
        }

        val selectedIndex = rewards.indexOfFirst { it.name.equals(prize, ignoreCase = true) }
        if (selectedIndex < 0) {
            wheelView.rotationDegrees = 0f
            prizeRevealed = true
            resultText.text = "Today's prize: $prize"
            return
        }

        wheelView.rotationDegrees = wheelView.computeLandingRotation(selectedIndex)
        prizeRevealed = true
        resultText.text = "Today's prize: ${rewards[selectedIndex].name}"
    }

    private fun showUnlockedPrize() {
        val prize = dailyPrizeResult?.prizeUnlocked?.trim().orEmpty()
        if (prize.isEmpty()) return
        if (!rewardsLoaded) {
            resultText.text = "Today's prize: $prize"
            return
        }
        val selectedIndex = rewards.indexOfFirst { it.name.equals(prize, ignoreCase = true) }
        if (selectedIndex >= 0) {
            wheelView.rotationDegrees = wheelView.computeLandingRotation(selectedIndex)
            resultText.text = "Today's prize: ${rewards[selectedIndex].name}"
        } else {
            resultText.text = "Today's prize: $prize"
        }
    }

    private fun messageForNoPrizeYet(r: SupabaseInterface.DailyPrizeResult): String {
        val err = r.serverError?.trim().orEmpty()
        if (err.isNotEmpty()) return "Server checked — $err Tap Spin to try again."
        if (!r.eligible) {
            return "Server checked — you're not eligible yet. Complete today's required tasks and checklist items, then tap Spin again."
        }
        return "Server checked — no prize yet. Tap Spin to try again."
    }

    private fun spinToIndex(selectedIndex: Int, onDone: () -> Unit) {
        if (spinning || rewards.isEmpty()) return
        val targetAngle = wheelView.computeLandingRotation(selectedIndex)
        val start = wheelView.rotationDegrees
        val fullSpins = 7 * 360f
        val animator = ValueAnimator.ofFloat(start, start + fullSpins + targetAngle).apply {
            duration = 4600L
            interpolator = DecelerateInterpolator()
            addUpdateListener { valueAnimator ->
                wheelView.rotationDegrees = valueAnimator.animatedValue as Float
            }
        }
        spinning = true
        spinButton.isEnabled = false
        resultText.text = "Spinning..."
        animator.start()
        animator.doOnEnd {
            spinning = false
            onDone()
        }
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) = block()
            override fun onAnimationCancel(animation: android.animation.Animator) = block()
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
    }
}

class RewardWheelView(context: android.content.Context, attrs: android.util.AttributeSet?) : View(context, attrs) {
    constructor(context: android.content.Context) : this(context, null)

    var rotationDegrees: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private var rewards: List<SupabaseInterface.RewardSpinnerItem> = emptyList()
    private val arcRect = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val colors = listOf(
        Color.parseColor("#FFCDD2"),
        Color.parseColor("#FFE0B2"),
        Color.parseColor("#FFF9C4"),
        Color.parseColor("#C8E6C9"),
        Color.parseColor("#B3E5FC"),
        Color.parseColor("#D1C4E9")
    )

    fun setRewards(items: List<SupabaseInterface.RewardSpinnerItem>) {
        rewards = items
        invalidate()
    }

    /**
     * Returns extra rotation needed so selected segment center lands at 0 degrees pointer (top).
     */
    fun computeLandingRotation(selectedIndex: Int): Float {
        if (rewards.isEmpty()) return 0f
        val total = rewards.sumOf { it.percent.coerceAtLeast(0) }.toFloat().coerceAtLeast(1f)
        var start = -90f
        rewards.forEachIndexed { i, reward ->
            val sweep = (reward.percent.coerceAtLeast(0) / total) * 360f
            if (i == selectedIndex) {
                val center = start + sweep / 2f
                return 270f - center
            }
            start += sweep
        }
        return 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.44f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.save()
        canvas.rotate(rotationDegrees, cx, cy)
        if (rewards.isEmpty()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY }
            canvas.drawCircle(cx, cy, radius, p)
        } else {
            val total = rewards.sumOf { it.percent.coerceAtLeast(0) }.toFloat().coerceAtLeast(1f)
            var start = -90f
            rewards.forEachIndexed { idx, reward ->
                val sweep = (reward.percent.coerceAtLeast(0) / total) * 360f
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = colors[idx % colors.size]
                }
                canvas.drawArc(arcRect, start, sweep, true, fill)
                canvas.drawArc(arcRect, start, sweep, true, borderPaint)

                val textAngle = Math.toRadians((start + sweep / 2f).toDouble())
                val tx = (cx + radius * 0.62f * kotlin.math.cos(textAngle)).toFloat()
                val ty = (cy + radius * 0.62f * kotlin.math.sin(textAngle)).toFloat()
                val maxCharsPerLine = when {
                    sweep < 40f -> 6
                    sweep < 70f -> 8
                    else -> 10
                }
                val wrappedLines = wrapLabel(reward.name, maxCharsPerLine)
                canvas.save()
                canvas.rotate((start + sweep / 2f) + 90f, tx, ty)
                val lineHeight = textPaint.textSize * 0.95f
                val firstLineY = ty - ((wrappedLines.size - 1) * lineHeight / 2f)
                wrappedLines.forEachIndexed { lineIndex, line ->
                    canvas.drawText(line, tx, firstLineY + lineIndex * lineHeight, textPaint)
                }
                canvas.restore()
                start += sweep
            }
        }
        canvas.restore()

        // Pointer at top
        val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
        val pointer = android.graphics.Path().apply {
            moveTo(cx, cy - radius - 10f)
            lineTo(cx - 24f, cy - radius - 56f)
            lineTo(cx + 24f, cy - radius - 56f)
            close()
        }
        canvas.drawPath(pointer, pointerPaint)
    }

    private fun wrapLabel(text: String, maxCharsPerLine: Int): List<String> {
        if (text.length <= maxCharsPerLine) return listOf(text)
        val words = text.split(" ")
        if (words.size == 1) {
            return text.chunked(maxCharsPerLine).take(3).mapIndexed { index, chunk ->
                if (index == 2 && text.length > maxCharsPerLine * 3) "$chunk..." else chunk
            }
        }
        val lines = mutableListOf<String>()
        var currentLine = ""
        for (word in words) {
            val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (candidate.length <= maxCharsPerLine) {
                currentLine = candidate
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                currentLine = word
            }
            if (lines.size == 3) break
        }
        if (currentLine.isNotEmpty() && lines.size < 3) {
            lines.add(currentLine)
        }
        if (lines.size == 3 && words.joinToString(" ").length > lines.joinToString(" ").length) {
            lines[2] = lines[2].take((maxCharsPerLine - 1).coerceAtLeast(1)) + "..."
        }
        return lines.ifEmpty { listOf(text.take(maxCharsPerLine)) }
    }
}
