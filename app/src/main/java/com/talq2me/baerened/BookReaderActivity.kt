package com.talq2me.baerened

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Displays a book from assets/books/ (JSON) with page image, text, TTS, and optional quiz.
 * - Page 0: Cover (first page image + title).
 * - Pages 1..N: Content (image + text, TTS; > enabled only after TTS finishes).
 * - Page N+1: "fin" (fr) or "the end" (en).
 * - Then questions; > enabled only after correct answer per question.
 */
class BookReaderActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BookReader"
        const val EXTRA_BOOK_FILE = "book_file"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SECTION_ID = "section_id"
        const val EXTRA_STARS = "stars"
        const val EXTRA_TASK_TITLE = "task_title"
    }

    private var book: BookJson? = null
    private var currentIndex: Int = 0
    private var selectedOptionIndex: Int = -1

    private lateinit var imageView: ImageView
    private lateinit var textContainer: ScrollView
    private lateinit var pageText: TextView
    private lateinit var titleText: TextView
    private lateinit var endText: TextView
    private lateinit var questionContainer: LinearLayout
    private lateinit var questionTitle: TextView
    private lateinit var optionsContainer: LinearLayout
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button

    private lateinit var timeTracker: TimeTracker
    private var ttsUtteranceIdForPage: String? = null
    /** Character ranges (start, end) for each spoken chunk on the current content page. */
    private var currentPageChunkSpans: List<Pair<Int, Int>> = emptyList()
    /** Character ranges (start, end) for each word on the current page, for time-based word highlight. */
    private var currentPageWordSpans: List<Pair<Int, Int>> = emptyList()
    private val ttsHighlightColor = 0x40FFEB3B.toInt() // soft yellow
    /** ~2.4 syl/s at rate 0.85; used to estimate word timings for highlight. */
    private val estimatedSyllablesPerSecond = 2.4f
    private val ttsHighlightHandler = Handler(Looper.getMainLooper())
    private val pendingWordHighlightRunnables = mutableListOf<Runnable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_reader)

        val bookFile = intent.getStringExtra(EXTRA_BOOK_FILE)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val sectionId = intent.getStringExtra(EXTRA_SECTION_ID)
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "Book"

        timeTracker = TimeTracker(this)
        val uniqueTaskId = if (taskId != null && sectionId != null) {
            DailyProgressManager(this).getUniqueTaskId(taskId, taskTitle, sectionId)
        } else {
            taskId ?: "bookReader"
        }
        timeTracker.startActivity(uniqueTaskId, "bookReader", taskTitle)

        if (bookFile.isNullOrEmpty()) {
            Toast.makeText(this, "No book file specified", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        book = loadBook(bookFile)
        if (book == null) {
            Toast.makeText(this, "Could not load book: $bookFile", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        bindViews()
        setupTtsListener()
        showScreen(0)
    }

    private fun loadBook(bookFile: String): BookJson? {
        val fileName = when {
            bookFile.startsWith("file=") -> bookFile.removePrefix("file=").trim()
            bookFile.endsWith(".json") -> bookFile
            else -> "$bookFile.json"
        }
        return try {
            val path = "books/$fileName"
            assets.open(path).bufferedReader().use { it.readText() }.let { json ->
                Gson().fromJson(json, BookJson::class.java)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading book from $fileName", e)
            null
        }
    }

    private fun bindViews() {
        imageView = findViewById(R.id.book_image)
        // Resize image to fit screen: cap height so image scales down on small/large screens
        val displayMetrics = resources.displayMetrics
        val maxImageHeight = (displayMetrics.heightPixels * 0.55).toInt().coerceAtLeast(180)
        imageView.maxHeight = maxImageHeight
        textContainer = findViewById(R.id.book_text_container)
        pageText = findViewById(R.id.book_page_text)
        titleText = findViewById(R.id.book_title_text)
        endText = findViewById(R.id.book_end_text)
        questionContainer = findViewById(R.id.book_question_container)
        questionTitle = findViewById(R.id.book_question_title)
        optionsContainer = findViewById(R.id.book_options_container)
        btnPrev = findViewById(R.id.book_btn_prev)
        btnNext = findViewById(R.id.book_btn_next)

        btnPrev.setOnClickListener { goPrev() }
        btnNext.setOnClickListener { goNext() }
    }

    private fun setupTtsListener() {
        val chunkUidRegex = Regex("""book_tts_\d+_chunk_(\d+)""")
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    cancelPendingWordHighlights()
                    val chunkIndex = utteranceId?.let { id ->
                        chunkUidRegex.find(id)?.groupValues?.get(1)?.toIntOrNull()
                            ?: if (id == ttsUtteranceIdForPage) currentPageChunkSpans.lastIndex.takeIf { it >= 0 } else null
                    }
                    if (chunkIndex == null || currentPageChunkSpans.getOrNull(chunkIndex) == null) return@runOnUiThread
                    val (cStart, cEnd) = currentPageChunkSpans[chunkIndex]
                    val full = pageText.text.toString()
                    if (full.isEmpty()) return@runOnUiThread
                    // Words in this chunk (overlap with [cStart, cEnd])
                    val wordIndices = currentPageWordSpans.mapIndexed { i, (wStart, wEnd) ->
                        if (wStart < cEnd && wEnd > cStart) i else null
                    }.filterNotNull()
                    if (wordIndices.isEmpty()) {
                        val span = SpannableStringBuilder(full)
                        span.setSpan(BackgroundColorSpan(ttsHighlightColor), cStart, cEnd.coerceAtMost(full.length), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        pageText.setText(span)
                        return@runOnUiThread
                    }
                    val syllableCounts = wordIndices.map { i ->
                        val (ws, we) = currentPageWordSpans[i]
                        estimateSyllables(full.substring(ws.coerceIn(0, full.length), we.coerceAtMost(full.length)))
                    }
                    val totalSyllables = syllableCounts.sum()
                    if (totalSyllables <= 0) return@runOnUiThread
                    val durationMs = (totalSyllables / estimatedSyllablesPerSecond * 1000).toLong()
                    var accSyllables = 0
                    wordIndices.forEachIndexed { idx, wi ->
                        val (wStart, wEnd) = currentPageWordSpans[wi]
                        val delayMs = (accSyllables.toFloat() / totalSyllables * durationMs).toLong()
                        accSyllables += syllableCounts[idx]
                        val r = Runnable {
                            if (wStart in 0..full.length && wEnd in 0..full.length && wStart <= wEnd) {
                                val sp = SpannableStringBuilder(full)
                                sp.setSpan(BackgroundColorSpan(ttsHighlightColor), wStart, wEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                pageText.setText(sp)
                            }
                        }
                        pendingWordHighlightRunnables.add(r)
                        ttsHighlightHandler.postDelayed(r, delayMs)
                    }
                }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    if (utteranceId != null && ttsUtteranceIdForPage == utteranceId) {
                        ttsUtteranceIdForPage = null
                        cancelPendingWordHighlights()
                        pageText.text = pageText.text.toString()
                        enableNextButton()
                    }
                }
            }
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    if (utteranceId != null && ttsUtteranceIdForPage == utteranceId) {
                        ttsUtteranceIdForPage = null
                        cancelPendingWordHighlights()
                        enableNextButton()
                    }
                }
            }
        }
        TtsManager.setOnUtteranceProgressListener(listener)
    }

    private fun totalScreens(): Int {
        val b = book ?: return 0
        val pageCount = b.pages.size
        return 1 + pageCount + 1 + b.questions.size // cover + content + end + questions
    }

    private fun showScreen(index: Int) {
        currentIndex = index
        val b = book ?: return
        val pageCount = b.pages.size
        val endIndex = pageCount + 1
        val firstQuestionIndex = pageCount + 2

        // Reset content visibility
        imageView.visibility = View.VISIBLE
        textContainer.visibility = View.GONE
        titleText.visibility = View.GONE
        endText.visibility = View.GONE
        questionContainer.visibility = View.GONE
        pageText.text = ""

        btnPrev.isEnabled = index > 0

        when {
            index == 0 -> showCover(b)
            index in 1..pageCount -> showContentPage(b, index - 1)
            index == endIndex -> showEndPage(b)
            index >= firstQuestionIndex -> showQuestion(b, index - firstQuestionIndex)
        }
    }

    private fun showCover(b: BookJson) {
        titleText.visibility = View.VISIBLE
        titleText.text = b.title ?: ""
        val firstPage = b.pages.firstOrNull()
        if (firstPage != null) {
            loadPageImage(firstPage)
        } else {
            imageView.setImageDrawable(null)
        }
        textContainer.visibility = View.GONE
        endText.visibility = View.GONE
        questionContainer.visibility = View.GONE
        val titleToSpeak = b.title?.trim()
        if (titleToSpeak.isNullOrEmpty()) {
            enableNextButton()
        } else {
            btnNext.isEnabled = false
            ttsUtteranceIdForPage = "book_tts_cover_done"
            speakSingleLine(b.language, titleToSpeak, ttsUtteranceIdForPage!!)
        }
    }

    private fun showContentPage(b: BookJson, pageIndex: Int) {
        val page = b.pages.getOrNull(pageIndex) ?: return
        titleText.visibility = View.GONE
        endText.visibility = View.GONE
        questionContainer.visibility = View.GONE
        loadPageImage(page)
        val fullText = page.text.joinToString(" ")
        pageText.text = fullText
        textContainer.visibility = View.VISIBLE

        currentPageChunkSpans = buildChunkSpans(fullText)
        currentPageWordSpans = buildWordSpans(fullText)

        btnNext.isEnabled = false
        ttsUtteranceIdForPage = "book_tts_${currentIndex}_done"
        speakPageTextByChunks(b.language, fullText, ttsUtteranceIdForPage!!)
    }

    private fun buildWordSpans(fullText: String): List<Pair<Int, Int>> {
        val words = fullText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val spans = mutableListOf<Pair<Int, Int>>()
        var searchStart = 0
        for (w in words) {
            val start = fullText.indexOf(w, searchStart).coerceAtLeast(0)
            val end = start + w.length
            spans.add(start to end)
            searchStart = end
        }
        return spans
    }

    /** Splits by sentence boundaries so TTS gets context (e.g. "à" pronounced correctly in "va à l'école"). */
    private fun buildChunkSpans(fullText: String): List<Pair<Int, Int>> {
        val chunks = fullText.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        if (chunks.isEmpty()) {
            if (fullText.isNotBlank()) return listOf(0 to fullText.length)
            return emptyList()
        }
        val spans = mutableListOf<Pair<Int, Int>>()
        var searchStart = 0
        for (chunk in chunks) {
            val start = fullText.indexOf(chunk, searchStart).coerceIn(0, fullText.length)
            val end = (start + chunk.length).coerceAtMost(fullText.length)
            if (end > start) spans.add(start to end)
            searchStart = end
        }
        return spans
    }

    /** Rough syllable count for timing (French/English). Vowel groups + silent-e heuristic. */
    private fun estimateSyllables(word: String): Int {
        if (word.isBlank()) return 1
        val v = Regex("""[aeiouyàâäéèêëïîôùûüœæAEIOUYÀÂÄÉÈÊËÏÎÔÙÛÜŒÆ]+""")
        val groups = v.findAll(word).toList()
        var count = groups.size.coerceAtLeast(1)
        val last = word.last().lowercaseChar()
        if (count > 1 && (last == 'e' || last == 'é') && !word.lowercase().endsWith("ée"))
            count-- // silent e / e muet
        return count.coerceAtLeast(1)
    }

    private fun cancelPendingWordHighlights() {
        pendingWordHighlightRunnables.forEach { ttsHighlightHandler.removeCallbacks(it) }
        pendingWordHighlightRunnables.clear()
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

    /** Speaks the page text by sentence/chunk so TTS has context (e.g. French "à" is pronounced correctly, not "a accent grave"). */
    private fun speakPageTextByChunks(language: String?, fullText: String, doneUtteranceId: String) {
        val locale = when (language?.lowercase()?.take(2)) {
            "fr" -> Locale.FRENCH
            else -> Locale.US
        }
        TtsManager.whenReady(Runnable {
            val chunks = fullText.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            if (chunks.isEmpty()) {
                runOnUiThread { enableNextButton() }
                return@Runnable
            }
            chunks.forEachIndexed { i, chunk ->
                val uid = if (i == chunks.lastIndex) doneUtteranceId else "book_tts_${currentIndex}_chunk_$i"
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                TtsManager.speak(chunk, locale, mode, uid)
            }
        })
    }

    private fun showEndPage(b: BookJson) {
        titleText.visibility = View.GONE
        textContainer.visibility = View.GONE
        imageView.visibility = View.GONE
        endText.visibility = View.VISIBLE
        val endStr = if (b.language?.lowercase()?.startsWith("fr") == true) "fin" else "The End"
        endText.text = endStr
        questionContainer.visibility = View.GONE
        btnNext.isEnabled = false
        ttsUtteranceIdForPage = "book_tts_end_done"
        speakSingleLine(b.language, endStr, ttsUtteranceIdForPage!!)
    }

    private fun showQuestion(b: BookJson, questionIndex: Int) {
        val questions = b.questions
        if (questionIndex !in questions.indices) return
        val q = questions[questionIndex]
        titleText.visibility = View.GONE
        textContainer.visibility = View.GONE
        endText.visibility = View.GONE
        imageView.visibility = View.GONE
        questionContainer.visibility = View.VISIBLE
        questionTitle.text = q.question
        optionsContainer.removeAllViews()
        selectedOptionIndex = -1
        q.options.forEachIndexed { i, opt ->
            val btn = Button(this).apply {
                text = opt
                textSize = 20f
                setPadding(32, 24, 32, 24)
                minimumHeight = 96
                setBackgroundResource(R.drawable.button_rounded_choice)
                setOnClickListener {
                    selectedOptionIndex = i
                    for (j in 0 until optionsContainer.childCount) {
                        (optionsContainer.getChildAt(j) as? Button)?.alpha = if (j == i) 1f else 0.6f
                    }
                    if (i == q.correctIndex) {
                        showCorrectCelebration()
                        enableNextButton()
                    } else {
                        Toast.makeText(this@BookReaderActivity, "Try again!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
            optionsContainer.addView(btn, params)
        }
        btnNext.isEnabled = false
    }

    private fun loadPageImage(page: BookPage) {
        val imageId = page.image?.imageId ?: return
        // Prefer .webp (smaller), fall back to .png for older assets
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
    }

    private fun enableNextButton() {
        btnNext.isEnabled = true
    }

    private fun showCorrectCelebration() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x40000000)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val message = TextView(this).apply {
            text = "Correct! 🎉"
            textSize = 32f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            scaleX = 0.3f
            scaleY = 0.3f
        }
        overlay.addView(message)
        val density = resources.displayMetrics.density
        val particleSize = (14 * density).toInt()
        val burstRadius = (90 * density).toFloat()
        for (i in 0 until 12) {
            val angle = Math.toRadians((i * 30).toDouble())
            val dx = (burstRadius * cos(angle)).toFloat()
            val dy = (burstRadius * sin(angle)).toFloat()
            val p = View(this).apply {
                setBackgroundResource(R.drawable.celebration_particle)
                layoutParams = FrameLayout.LayoutParams(particleSize, particleSize).apply {
                    gravity = Gravity.CENTER
                }
                translationX = 0f
                translationY = 0f
                alpha = 1f
            }
            overlay.addView(p)
        }
        content.addView(overlay)
        message.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(250)
            .withEndAction {
                message.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            .start()
        overlay.post {
            for (i in 0 until overlay.childCount) {
                val v = overlay.getChildAt(i)
                if (v is View && v != message) {
                    val angle = Math.toRadians(((i - 1) * 30).toDouble())
                    val dx = (burstRadius * cos(angle)).toFloat()
                    val dy = (burstRadius * sin(angle)).toFloat()
                    v.animate()
                        .translationX(dx)
                        .translationY(dy)
                        .alpha(0f)
                        .setDuration(600)
                        .start()
                }
            }
        }
        overlay.postDelayed({
            if (overlay.parent != null) {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
            }
        }, 1500)
    }

    private fun goPrev() {
        if (currentIndex <= 0) return
        TtsManager.stop()
        ttsUtteranceIdForPage = null
        cancelPendingWordHighlights()
        showScreen(currentIndex - 1)
    }

    private fun goNext() {
        if (currentIndex >= totalScreens() - 1) {
            finishWithSuccess()
            return
        }
        TtsManager.stop()
        ttsUtteranceIdForPage = null
        cancelPendingWordHighlights()
        showScreen(currentIndex + 1)
    }

    private fun finishWithSuccess() {
        timeTracker.endActivity("bookReader")
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
        timeTracker.endActivity("bookReader")
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    override fun onDestroy() {
        TtsManager.stop()
        cancelPendingWordHighlights()
        TtsManager.setOnUtteranceProgressListener(null)
        super.onDestroy()
    }
}
