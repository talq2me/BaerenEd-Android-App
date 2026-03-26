package com.talq2me.baerened

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import java.util.Locale

/**
 * Page-by-page reading game:
 * - Shows page image + text
 * - Speaks page text and question prompts via TTS
 * - For tappable word questions: kid taps the correct word *inside the page text*
 * - For comprehension: optional multiple-choice question per page
 */
class TappableTextActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TappableText"

        const val EXTRA_TAPPABLE_TEXT_FILE = "tappable_text_file"

        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SECTION_ID = "section_id"
        const val EXTRA_STARS = "stars"
        const val EXTRA_TASK_TITLE = "task_title"
    }

    private var game: TappableTextRoot? = null
    private var currentPageIndex: Int = 0

    private lateinit var imageView: ImageView
    private lateinit var textContainer: ScrollView
    private lateinit var pageText: TextView
    private lateinit var titleText: TextView
    private lateinit var questionContainer: LinearLayout
    private lateinit var questionTitle: TextView
    private lateinit var optionsContainer: LinearLayout
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button

    private val ttsHighlightColor = 0x40FFEB3B.toInt()

    private val wordTapWrongMessage = "Try again!"
    private val wordTapCorrectMessage = "Correct!"

    private sealed class PageQuestion {
        data class TapWord(val question: TappableWordQuestion) : PageQuestion()
        data class Comprehension(val question: TappableTextComprehensionQuestion) : PageQuestion()
    }

    private data class WordSpan(
        val start: Int,
        val end: Int,
        val normalizedToken: String
    )

    private var currentQuestions: List<PageQuestion> = emptyList()
    private var currentQuestionIndex: Int = 0

    private var interactionEnabled: Boolean = false
    private var activeTapCorrectNormalizedToken: String? = null

    private var currentWordSpans: List<WordSpan> = emptyList()
    private var currentPageSpannable: SpannableStringBuilder? = null

    private var ttsPageDoneUtteranceId: String? = null
    private var ttsQuestionDoneUtteranceId: String? = null

    // Used to debounce rapid taps while TTS transitions.
    private val tapDebounceHandler = Handler(Looper.getMainLooper())
    private var tapDebounceRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tappable_text)

        val tappableTextFile = intent.getStringExtra(EXTRA_TAPPABLE_TEXT_FILE)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val sectionId = intent.getStringExtra(EXTRA_SECTION_ID)
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "Tappable Text"

        // Time tracking (for rewards / reporting). This matches the BookReader approach.
        val timeTracker = TimeTracker(this)
        val uniqueTaskId = if (taskId != null && sectionId != null) {
            DailyProgressManager(this).getUniqueTaskId(taskId, sectionId)
        } else {
            taskId ?: "tappableText"
        }
        timeTracker.startActivity(uniqueTaskId, "tappableText", taskTitle)

        if (tappableTextFile.isNullOrBlank()) {
            Toast.makeText(this, "No tappableText file specified", Toast.LENGTH_LONG).show()
            timeTracker.endActivity("tappableText")
            finish()
            return
        }

        game = loadGame(tappableTextFile)
        if (game == null) {
            Toast.makeText(this, "Could not load tappableText game", Toast.LENGTH_LONG).show()
            timeTracker.endActivity("tappableText")
            finish()
            return
        }

        bindViews()
        setupTtsListener()

        btnPrev.isEnabled = false
        btnNext.isEnabled = false

        // Hide book title/end; we only show page text + questions.
        titleText.visibility = View.GONE
        btnNext.visibility = View.GONE
        btnPrev.visibility = View.GONE

        val sortedPages = game!!.pages.sortedBy { it.pageNumber }
        game = game!!.copy(pages = sortedPages)

        currentPageIndex = 0
        startPage(currentPageIndex)
    }

    private fun bindViews() {
        imageView = findViewById(R.id.book_image)
        textContainer = findViewById(R.id.book_text_container)
        pageText = findViewById(R.id.book_page_text)
        titleText = findViewById(R.id.book_title_text)
        questionContainer = findViewById(R.id.book_question_container)
        questionTitle = findViewById(R.id.book_question_title)
        optionsContainer = findViewById(R.id.book_options_container)
        btnPrev = findViewById(R.id.book_btn_prev)
        btnNext = findViewById(R.id.book_btn_next)

        // ClickableSpan needs a movement method to receive touch events.
        pageText.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun loadGame(tappableTextFile: String): TappableTextRoot? {
        val fileName = when {
            tappableTextFile.startsWith("file=") -> tappableTextFile.removePrefix("file=").trim()
            tappableTextFile.endsWith(".json") -> tappableTextFile
            else -> "$tappableTextFile.json"
        }

        return try {
            val path = "tappableText/$fileName"
            assets.open(path).bufferedReader().use { json ->
                Gson().fromJson(json, TappableTextRoot::class.java)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading tappableText from $fileName", e)
            null
        }
    }

    private fun setupTtsListener() {
        val listener = object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // No-op: we don't do time-based word highlighting in this game.
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == null) return
                runOnUiThread {
                    if (utteranceId == ttsPageDoneUtteranceId) {
                        ttsPageDoneUtteranceId = null
                        beginPageQuestions()
                    } else if (utteranceId == ttsQuestionDoneUtteranceId) {
                        ttsQuestionDoneUtteranceId = null
                        enableCurrentQuestionInteraction()
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId == null) return
                runOnUiThread {
                    if (utteranceId == ttsPageDoneUtteranceId) {
                        ttsPageDoneUtteranceId = null
                        beginPageQuestions()
                    } else if (utteranceId == ttsQuestionDoneUtteranceId) {
                        ttsQuestionDoneUtteranceId = null
                        enableCurrentQuestionInteraction()
                    }
                }
            }
        }
        TtsManager.setOnUtteranceProgressListener(listener)
    }

    private fun speakSingleLine(language: String?, text: String, doneUtteranceId: String) {
        val locale = when (language?.lowercase()?.take(2)) {
            "fr" -> Locale.FRENCH
            else -> Locale.US
        }
        TtsManager.whenReady(Runnable {
            TtsManager.speak(text, locale, TextToSpeech.QUEUE_FLUSH, doneUtteranceId)
        })
    }

    private fun speakTextByChunks(language: String?, fullText: String, doneUtteranceId: String) {
        val locale = when (language?.lowercase()?.take(2)) {
            "fr" -> Locale.FRENCH
            else -> Locale.US
        }
        TtsManager.whenReady(Runnable {
            val chunks = fullText.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            if (chunks.isEmpty()) {
                // Nothing to speak; move on so the user isn't stuck.
                runOnUiThread {
                    if (doneUtteranceId == ttsPageDoneUtteranceId) {
                        ttsPageDoneUtteranceId = null
                    }
                    beginPageQuestions()
                }
                return@Runnable
            }
            chunks.forEachIndexed { i, chunk ->
                val uid = if (i == chunks.lastIndex) doneUtteranceId else "tt_page_chunk_$i"
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                TtsManager.speak(chunk, locale, mode, uid)
            }
        })
    }

    private fun startPage(pageIndex: Int) {
        val g = game ?: return
        if (pageIndex !in g.pages.indices) return

        interactionEnabled = false
        activeTapCorrectNormalizedToken = null
        optionsContainer.removeAllViews()
        questionContainer.visibility = View.GONE

        currentWordSpans = emptyList()
        currentPageSpannable = null

        val page = g.pages[pageIndex]
        loadPageImage(page)

        val fullText = page.text.joinToString(" ")
        pageText.text = ""
        setClickableWordsForPage(fullText)
        pageText.text = currentPageSpannable

        // Prepare question list for this page.
        currentQuestions = buildQuestionsForPage(page)
        currentQuestionIndex = 0

        // Speak the page.
        val utteranceId = "tt_page_${pageIndex}_done"
        ttsPageDoneUtteranceId = utteranceId
        ttsQuestionDoneUtteranceId = null

        // Speak even if there are no questions (still reads the story).
        speakTextByChunks(g.language, fullText, utteranceId)

        tapDebounceRunnable = null
    }

    private fun beginPageQuestions() {
        val g = game ?: return
        if (currentPageIndex !in g.pages.indices) return

        if (currentQuestions.isEmpty()) {
            advancePage()
            return
        }
        // Ask first question (speaks question prompt, then enables interaction).
        showQuestion(currentQuestionIndex)
    }

    private fun buildQuestionsForPage(page: TappableTextPage): List<PageQuestion> {
        val out = mutableListOf<PageQuestion>()
        page.tappableWordQuestions.forEach { out.add(PageQuestion.TapWord(it)) }
        page.comprehensionQuestion?.let { out.add(PageQuestion.Comprehension(it)) }
        return out
    }

    private fun showQuestion(questionIndex: Int) {
        val g = game ?: return
        if (questionIndex !in currentQuestions.indices) return

        val q = currentQuestions[questionIndex]
        questionContainer.visibility = View.VISIBLE
        optionsContainer.removeAllViews()
        interactionEnabled = false
        activeTapCorrectNormalizedToken = null

        val prompt = when (q) {
            is PageQuestion.TapWord -> q.question.prompt
            is PageQuestion.Comprehension -> q.question.prompt
        }

        questionTitle.text = prompt
        btnNext.isEnabled = false

        // Speak prompt, then enable interactions.
        val utteranceId = "tt_page_${currentPageIndex}_q_${questionIndex}_done"
        ttsQuestionDoneUtteranceId = utteranceId
        ttsPageDoneUtteranceId = null
        speakSingleLine(g.language, prompt, utteranceId)

        when (q) {
            is PageQuestion.TapWord -> {
                optionsContainer.visibility = View.GONE
            }
            is PageQuestion.Comprehension -> {
                optionsContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun enableCurrentQuestionInteraction() {
        if (currentQuestions.isEmpty()) return
        if (currentQuestionIndex !in currentQuestions.indices) return

        val q = currentQuestions[currentQuestionIndex]
        interactionEnabled = true

        when (q) {
            is PageQuestion.TapWord -> {
                activeTapCorrectNormalizedToken = normalizeWord(q.question.correctWord)
                optionsContainer.visibility = View.GONE
            }
            is PageQuestion.Comprehension -> {
                activeTapCorrectNormalizedToken = null
                optionsContainer.visibility = View.VISIBLE
                populateComprehensionOptions(q.question)
            }
        }
    }

    private fun populateComprehensionOptions(question: TappableTextComprehensionQuestion) {
        optionsContainer.removeAllViews()
        question.options.forEachIndexed { i, opt ->
            val btn = Button(this).apply {
                text = opt
                textSize = 20f
                setPadding(32, 24, 32, 24)
                minimumHeight = 96
                setBackgroundResource(R.drawable.button_rounded_choice)
                setOnClickListener {
                    if (!interactionEnabled) return@setOnClickListener
                    if (tapDebounceRunnable != null) {
                        tapDebounceHandler.removeCallbacks(tapDebounceRunnable!!)
                    }
                    val r = Runnable { /* no-op; just debouncing */ }
                    tapDebounceRunnable = r
                    tapDebounceHandler.postDelayed(r, 50)

                    if (i == question.correctIndex) {
                        interactionEnabled = false
                        Toast.makeText(this@TappableTextActivity, wordTapCorrectMessage, Toast.LENGTH_SHORT).show()
                        onQuestionAnsweredCorrect()
                    } else {
                        Toast.makeText(this@TappableTextActivity, wordTapWrongMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
            optionsContainer.addView(btn, params)
        }
    }

    private fun onQuestionAnsweredCorrect() {
        val nextIdx = currentQuestionIndex + 1
        if (nextIdx < currentQuestions.size) {
            currentQuestionIndex = nextIdx
            showQuestion(currentQuestionIndex)
            return
        }
        // Page completed, advance.
        advancePage()
    }

    private fun advancePage() {
        val g = game ?: return
        val nextPage = currentPageIndex + 1
        if (nextPage > g.pages.lastIndex) {
            // Finish with success.
            finishWithSuccess()
            return
        }
        currentPageIndex = nextPage
        startPage(currentPageIndex)
    }

    private fun finishWithSuccess() {
        // Make sure to end tracking; TimeTracker will close the running session.
        TimeTracker(this).endActivity("tappableText")
        val resultIntent = android.content.Intent().apply {
            putExtra(EXTRA_TASK_ID, intent.getStringExtra(EXTRA_TASK_ID))
            putExtra(EXTRA_TASK_TITLE, intent.getStringExtra(EXTRA_TASK_TITLE))
            putExtra(EXTRA_SECTION_ID, intent.getStringExtra(EXTRA_SECTION_ID))
            putExtra(EXTRA_STARS, intent.getIntExtra(EXTRA_STARS, 0))
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    override fun onBackPressed() {
        TtsManager.stop()
        TimeTracker(this).endActivity("tappableText")
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    private fun normalizeWord(raw: String): String {
        // Normalize for taps:
        // - Lowercase
        // - Replace curly apostrophes with ASCII
        // - Strip leading/trailing non-letter characters (keep internal apostrophes)
        val s = raw.trim().lowercase(Locale.getDefault())
            .replace('’', '\'')
            .replace('‑', '-')
        val stripped = s.replace(Regex("^[^\\p{L}']+|[^\\p{L}']+$"), "")
        return stripped
    }

    private fun setClickableWordsForPage(fullText: String) {
        val tokens = fullText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            currentWordSpans = emptyList()
            currentPageSpannable = SpannableStringBuilder(fullText)
            return
        }

        val spans = mutableListOf<WordSpan>()
        val spannable = SpannableStringBuilder(fullText)

        var searchStart = 0
        tokens.forEach { token ->
            val start = fullText.indexOf(token, searchStart).coerceAtLeast(0)
            val end = (start + token.length).coerceAtMost(fullText.length)
            if (end <= start) return@forEach

            val normalized = normalizeWord(token)
            spans.add(WordSpan(start, end, normalized))

            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        if (!interactionEnabled) return
                        val correct = activeTapCorrectNormalizedToken ?: return
                        val clickedNormalizedToken = normalized
                        if (clickedNormalizedToken == correct) {
                            // Highlight all matching instances for clarity.
                            highlightCorrectWord(correct)
                            interactionEnabled = false
                            onQuestionAnsweredCorrect()
                        } else {
                            Toast.makeText(this@TappableTextActivity, wordTapWrongMessage, Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun updateDrawState(ds: android.text.TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = false
                    }
                },
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            searchStart = end
        }

        currentWordSpans = spans
        currentPageSpannable = spannable
    }

    private fun highlightCorrectWord(correctNormalizedToken: String) {
        val spannable = currentPageSpannable ?: return
        currentWordSpans.forEach { w ->
            if (w.normalizedToken == correctNormalizedToken) {
                spannable.setSpan(
                    BackgroundColorSpan(ttsHighlightColor),
                    w.start,
                    w.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        pageText.text = spannable
        Toast.makeText(this, wordTapCorrectMessage, Toast.LENGTH_SHORT).show()
    }

    private fun loadPageImage(page: TappableTextPage) {
        val imageId = page.image?.imageId ?: return
        val pathWebp = "books/images/$imageId.webp"
        val pathPng = "books/images/$imageId.png"
        try {
            assets.open(pathWebp).use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                imageView.setImageBitmap(bitmap)
            }
        } catch (_: Exception) {
            try {
                assets.open(pathPng).use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    imageView.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load image: $pathWebp or $pathPng", e)
                imageView.setImageDrawable(null)
            }
        }

        imageView.visibility = View.VISIBLE
        textContainer.visibility = View.VISIBLE
    }
}

