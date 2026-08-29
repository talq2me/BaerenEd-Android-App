package com.talq2me.baerened

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Page-by-page French story: TTS chapter mode (catch-up range) or review (no TTS).
 * Content from GitHub Pages `stories/<bookId>/`.
 */
class StoryReadActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "StoryRead"
        const val EXTRA_STORY_URL = "story_url"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SECTION_ID = "section_id"
        const val EXTRA_STARS = "stars"
        const val EXTRA_TASK_TITLE = "task_title"
        const val REQUEST_CODE = 1009
    }

    private lateinit var imageView: ImageView
    private lateinit var englishOverlay: ScrollView
    private lateinit var englishText: TextView
    private lateinit var pageLabel: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnEnglish: Button
    private lateinit var btnNext: Button

    private var segments: List<StoryReadSegment> = emptyList()
    private var spec: StoryReadSchedule.Spec = StoryReadSchedule.Spec("", null, emptyList())
    private var session: StoryReadSchedule.Session? = null
    private var sessionPages: List<StoryReadSegment> = emptyList()
    private var pageIndex: Int = 0
    private var englishVisible: Boolean = false
    private var buttonsUnlocked: Boolean = false

    private var ttsDoneUtteranceId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ttsWatchdog: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_read)
        TtsManager.restoreDefaultSpeechRate()

        imageView = findViewById(R.id.story_image)
        englishOverlay = findViewById(R.id.story_english_overlay)
        englishText = findViewById(R.id.story_english_text)
        pageLabel = findViewById(R.id.story_page_label)
        btnPrev = findViewById(R.id.story_btn_prev)
        btnEnglish = findViewById(R.id.story_btn_english)
        btnNext = findViewById(R.id.story_btn_next)

        val rawUrl = intent.getStringExtra(EXTRA_STORY_URL)?.trim().orEmpty()
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val sectionId = intent.getStringExtra(EXTRA_SECTION_ID)
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "French story"

        val timeTracker = TimeTracker(this)
        val uniqueTaskId = if (taskId != null && sectionId != null) {
            DailyProgressManager(this).getUniqueTaskId(taskId, taskTitle, sectionId)
        } else {
            taskId ?: "storyRead"
        }
        timeTracker.startActivity(uniqueTaskId, "storyRead", taskTitle)

        btnEnglish.setOnClickListener { toggleEnglish() }
        btnNext.setOnClickListener { goNext() }
        btnPrev.setOnClickListener { goPrev() }

        lifecycleScope.launch {
            spec = StoryReadSchedule.parseUrl(rawUrl)
            if (spec.bookId.isBlank()) {
                failAndFinish("No story book configured.")
                return@launch
            }
            val loaded = withContext(Dispatchers.IO) { loadStory(spec.bookId) }
            if (loaded == null || loaded.segments.isEmpty()) {
                failAndFinish("Could not load story from GitHub Pages.")
                return@launch
            }
            segments = loaded.segments.sortedBy { it.page }
            val bookLast = segments.last().page
            val dpm = DailyProgressManager(this@StoryReadActivity)
            val profile = dpm.getCurrentKid()
            val nextUnread = dpm.getGameIndexFromCache(profile, StoryReadSchedule.gameKey(spec.bookId))
            val sess = StoryReadSchedule.session(
                spec,
                StoryReadSchedule.todayToronto(),
                nextUnread,
                bookLast
            )
            if (sess == null) {
                failAndFinish("No story pages assigned today.")
                return@launch
            }
            session = sess
            sessionPages = segments.filter { it.page in sess.startPage..sess.endPage }
            if (sessionPages.isEmpty()) {
                failAndFinish("No story pages in today's range.")
                return@launch
            }
            pageIndex = 0
            setupTtsListener()
            showPage(0)
        }
    }

    private fun failAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        TimeTracker(this).endActivity("storyRead")
        finish()
    }

    private fun loadStory(bookId: String): StoryReadRoot? {
        val body = GitHubPagesAssets.fetchText("stories/$bookId/story.json") ?: return null
        return try {
            Gson().fromJson(body, StoryReadRoot::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Parse story.json failed for $bookId", e)
            null
        }
    }

    private fun setupTtsListener() {
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == null || utteranceId != ttsDoneUtteranceId) return
                runOnUiThread { onTtsFinished(utteranceId) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == null || utteranceId != ttsDoneUtteranceId) return
                runOnUiThread { onTtsFinished(utteranceId) }
            }
        }
        TtsManager.setOnUtteranceProgressListener(listener)
    }

    private fun onTtsFinished(utteranceId: String) {
        if (ttsDoneUtteranceId != utteranceId) return
        cancelTtsWatchdog()
        ttsDoneUtteranceId = null
        unlockButtons()
    }

    private fun showPage(index: Int) {
        val sess = session ?: return
        if (index !in sessionPages.indices) return
        pageIndex = index
        val page = sessionPages[index]
        englishVisible = false
        englishOverlay.visibility = View.GONE
        englishText.text = page.englishText.orEmpty()
        pageLabel.text = "Page ${page.page} of ${sess.endPage}"
        loadPageImage(page)
        if (sess.review) {
            buttonsUnlocked = true
            btnEnglish.visibility = View.VISIBLE
            btnNext.visibility = View.VISIBLE
            btnPrev.visibility = if (index > 0) View.VISIBLE else View.GONE
        } else {
            buttonsUnlocked = false
            btnEnglish.visibility = View.GONE
            btnNext.visibility = View.GONE
            btnPrev.visibility = View.GONE
            startPageTts(page)
        }
    }

    private fun loadPageImage(page: StoryReadSegment) {
        val imageRel = page.image?.trim().orEmpty()
        if (imageRel.isEmpty()) {
            imageView.setImageDrawable(null)
            return
        }
        val assetPath = "stories/${spec.bookId}/$imageRel".replace('\\', '/')
        val targetIndex = pageIndex
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = GitHubPagesAssets.fetchBitmapWithWebpFallback(assetPath)
            withContext(Dispatchers.Main) {
                if (pageIndex != targetIndex) return@withContext
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun startPageTts(page: StoryReadSegment) {
        cancelTtsWatchdog()
        TtsManager.stop()
        val text = page.frenchText?.trim().orEmpty()
        if (text.isEmpty()) {
            unlockButtons()
            return
        }
        val speed = page.tts?.speed
        if (speed != null && speed in 0.1f..2.0f) {
            TtsManager.setSpeechRate(speed)
        } else {
            TtsManager.restoreDefaultSpeechRate()
        }
        val locale = localeForVoice(page.tts?.voice)
        val uid = "story_page_${page.page}_${System.currentTimeMillis()}"
        ttsDoneUtteranceId = uid
        scheduleTtsWatchdog(text, uid)
        TtsManager.whenReady(Runnable {
            TtsManager.speak(text, locale, TextToSpeech.QUEUE_FLUSH, uid)
        })
    }

    private fun localeForVoice(voice: String?): Locale {
        val v = voice?.lowercase(Locale.US).orEmpty()
        return if (v.startsWith("fr")) Locale.FRENCH else Locale.FRENCH
    }

    private fun scheduleTtsWatchdog(text: String, expectedUid: String) {
        cancelTtsWatchdog()
        val chars = text.trim().length.coerceAtLeast(1)
        val delay = (chars * 90L) + 2500L
        val r = Runnable {
            if (ttsDoneUtteranceId == expectedUid) {
                Log.w(TAG, "TTS watchdog fired uid=$expectedUid")
                onTtsFinished(expectedUid)
            }
        }
        ttsWatchdog = r
        handler.postDelayed(r, delay)
    }

    private fun cancelTtsWatchdog() {
        ttsWatchdog?.let { handler.removeCallbacks(it) }
        ttsWatchdog = null
    }

    private fun unlockButtons() {
        buttonsUnlocked = true
        btnEnglish.visibility = View.VISIBLE
        btnNext.visibility = View.VISIBLE
        val sess = session
        btnPrev.visibility = if (sess?.review == true && pageIndex > 0) View.VISIBLE else View.GONE
    }

    private fun toggleEnglish() {
        if (!buttonsUnlocked) return
        englishVisible = !englishVisible
        englishOverlay.visibility = if (englishVisible) View.VISIBLE else View.GONE
    }

    private fun goNext() {
        if (!buttonsUnlocked) return
        val sess = session ?: return
        if (pageIndex < sessionPages.lastIndex) {
            showPage(pageIndex + 1)
            return
        }
        if (sess.review) {
            finishWithSuccess(saveIndex = false)
        } else {
            finishWithSuccess(saveIndex = true)
        }
    }

    private fun goPrev() {
        if (!buttonsUnlocked) return
        if (session?.review != true) return
        if (pageIndex > 0) showPage(pageIndex - 1)
    }

    private fun finishWithSuccess(saveIndex: Boolean) {
        cancelTtsWatchdog()
        TtsManager.stop()
        TimeTracker(this).endActivity("storyRead")
        val resultIntent = android.content.Intent().apply {
            putExtra(EXTRA_TASK_ID, intent.getStringExtra(EXTRA_TASK_ID))
            putExtra(EXTRA_TASK_TITLE, intent.getStringExtra(EXTRA_TASK_TITLE))
            putExtra(EXTRA_SECTION_ID, intent.getStringExtra(EXTRA_SECTION_ID))
            putExtra(EXTRA_STARS, intent.getIntExtra(EXTRA_STARS, 0))
        }
        val sess = session
        if (!saveIndex || sess == null || spec.bookId.isBlank()) {
            setResult(RESULT_OK, resultIntent)
            finish()
            return
        }
        val nextUnread = sess.endPage + 1
        lifecycleScope.launch(Dispatchers.IO) {
            val dpm = DailyProgressManager(this@StoryReadActivity)
            val profile = dpm.getCurrentKid()
            val r = dpm.updateGameIndexInDbSync(profile, StoryReadSchedule.gameKey(spec.bookId), nextUnread)
            if (r.isFailure) {
                Log.e(TAG, "Failed to save story nextUnread=$nextUnread", r.exceptionOrNull())
            }
            withContext(Dispatchers.Main) {
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        cancelTtsWatchdog()
        TtsManager.stop()
        TimeTracker(this).endActivity("storyRead")
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    override fun onDestroy() {
        cancelTtsWatchdog()
        TtsManager.stop()
        TtsManager.setOnUtteranceProgressListener(null)
        super.onDestroy()
    }
}
