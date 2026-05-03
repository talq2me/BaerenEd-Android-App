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
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale

/**
 * Page-by-page reading game:
 * - Shows page image + text
 * - Speaks page text and question prompts via TTS
 * - For tappable word questions: kid taps the correct word *inside the page text*
 * - For comprehension: optional multiple-choice question per page
 * - Easy mode ([EXTRA_EASY_MODE]): tap prompts become English, e.g. "Fridge in French is frigo. Tap the word frigo on the page."
 */
class TappableTextActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TappableText"

        const val EXTRA_TAPPABLE_TEXT_FILE = "tappable_text_file"

        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SECTION_ID = "section_id"
        const val EXTRA_STARS = "stars"
        const val EXTRA_TASK_TITLE = "task_title"

        /**
         * When true: English tap hints ([buildEasyTapPrompt]); after 3 wrong taps, the answer is
         * highlighted in green; a correct tap speaks the French word, then a US phrase like
         * "chat means cat" when the prompt has an English gloss; wrong taps speak the tapped word
         * in the book language. While the tap question is still locked (prompt TTS), any word tap
         * is read aloud in the book language.
         */
        const val EXTRA_EASY_MODE = "easy_mode"

        /**
         * [DailyProgressManager.getGameIndexFromCache] / [DailyProgressManager.updateGameIndexInDbSync] key
         * for which tappable book to play next (same pattern as other games' game_indices).
         * Playing book at index `i` saves `(i + 1) % n` on successful completion.
         *
         * When rotating with a language filter ([EXTRA_TAPPABLE_TEXT_FILE] contains `lang=xx` or
         * `language=xx`), the key becomes `tappableTextBooks_xx` so FR and EN rotations do not share
         * the same index.
         */
        const val GAME_KEY_TAPPABLE_BOOK_ROTATION = "tappableTextBooks"
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
    /** Stronger green so reveal-after-3-wrongs is obvious on all screens. */
    private val tapRevealHighlightColor = 0xCC00C853.toInt()

    private val wordTapWrongMessage = "Try again!"
    private val wordTapCorrectMessage = "Correct!"
    private val tapRevealToastMessage = "Tap the green highlighted words."

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

    /** When true, [rotationGameKey] is advanced after a successful run. */
    private var useBookRotation: Boolean = false
    private var rotationGameKey: String = GAME_KEY_TAPPABLE_BOOK_ROTATION
    private var rotationBookIndex: Int = 0
    private var rotationBookCount: Int = 0

    /** Younger-learner prompts for tap questions (English hint + tap instruction). */
    private var easyMode: Boolean = false

    /** Wrong taps for the current TapWord question (easy mode reveal after 3). */
    private var tapWordWrongGuesses: Int = 0
    private var tapAnswerRevealed: Boolean = false

    /** Utterance id for FR+EN chain after a correct TapWord tap in easy mode. */
    private val utteranceTapSuccessDone = "tt_tap_success_done"
    private val utteranceTapSuccessFr = "tt_tap_success_fr"

    /** After French surface word finishes, speak this English gloss (easy mode). */
    private var pendingTapSuccessEnglishGloss: String? = null

    /** French surface for the "… means …" US follow-up after [utteranceTapSuccessFr]. */
    private var pendingTapSuccessFrenchSurface: String? = null
    private var resolveErrorMessage: String? = null

    private val firstPageNarrationDelayMs = 4000L
    private val pageTurnNarrationDelayMs = 2000L
    private val narrationWordStepMinMs = 180L
    private val narrationWordStepMaxMs = 650L
    private val narrationWordMsPerChar = 42L
    private val narrationWordBaseMs = 150L
    private val narrationHighlightColor = 0x66FFD54F.toInt()
    private var narrationWordRunnable: Runnable? = null
    private var narrationWordCursor: Int = 0
    private var narrationHighlightSpan: BackgroundColorSpan? = null
    private val questionUnlockFallbackMs = 9000L
    private var questionUnlockRunnable: Runnable? = null
    private val httpClient = OkHttpClient.Builder().build()
    private val githubAssetsBase = "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets"
    private val githubTreeApi = "https://api.github.com/repos/talq2me/BaerenEd-Android-App/git/trees/main?recursive=1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tappable_text)

        easyMode = intent.getBooleanExtra(EXTRA_EASY_MODE, false)
        Log.d(TAG, "onCreate: easyMode=$easyMode")

        val rawUrl = intent.getStringExtra(EXTRA_TAPPABLE_TEXT_FILE)?.trim().orEmpty()
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val sectionId = intent.getStringExtra(EXTRA_SECTION_ID)
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "Tappable Text"

        // Time tracking (for rewards / reporting). This matches the BookReader approach.
        val timeTracker = TimeTracker(this)
        val uniqueTaskId = if (taskId != null && sectionId != null) {
            DailyProgressManager(this).getUniqueTaskId(taskId, taskTitle, sectionId)
        } else {
            taskId ?: "tappableText"
        }
        timeTracker.startActivity(uniqueTaskId, "tappableText", taskTitle)

        lifecycleScope.launch {
            val resolvedFileName = withContext(Dispatchers.IO) { resolveTappableJsonFileName(rawUrl) }
            if (resolvedFileName == null) {
                Toast.makeText(
                    this@TappableTextActivity,
                    resolveErrorMessage ?: "No tappable books found from GitHub Pages (check language filter if you used lang=).",
                    Toast.LENGTH_LONG
                ).show()
                timeTracker.endActivity("tappableText")
                finish()
                return@launch
            }

            Log.d(
                TAG,
                if (useBookRotation) {
                    "Book rotation: playing $resolvedFileName (index $rotationBookIndex / $rotationBookCount, key=$rotationGameKey)"
                } else {
                    "Single book: $resolvedFileName"
                }
            )

            val loadedGame = withContext(Dispatchers.IO) { loadGame(resolvedFileName) }
            game = loadedGame
            if (loadedGame == null) {
                Toast.makeText(this@TappableTextActivity, "Could not load tappableText game", Toast.LENGTH_LONG).show()
                timeTracker.endActivity("tappableText")
                finish()
                return@launch
            }

            bindViews()
            setupTtsListener()

            btnPrev.isEnabled = false
            btnNext.isEnabled = false

            // Hide book title/end; we only show page text + questions.
            titleText.visibility = View.GONE
            btnNext.visibility = View.GONE
            btnPrev.visibility = View.GONE

            game = loadedGame.copy(pages = loadedGame.pages.sortedBy { it.pageNumber })
            currentPageIndex = 0
            startPage(currentPageIndex)
        }
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

    /**
     * Resolves which `*_tappable.json` to load.
     *
     * **Rotation** (per-kid index in [DailyProgressManager], key [rotationGameKey]):
     * - Empty url, `rotate`, or `list`
     * - Query-only url, e.g. `lang=fr` or `language=en` (optional `&` / `?` separators)
     * - Optional `ufli=only` (basename starts with `ufli`, case-insensitive) or `ufli=no` / `ufli=exclude` (exclude those).
     *
     * **Single book** (no rotation index update):
     * - Bare filename, e.g. `milo-sandwich-geant-g4_tappable` or `file=milo-sandwich-geant-g4_tappable.json`
     * - May combine `file=...&lang=fr` (language is ignored for loading; file wins)
     */
    private suspend fun resolveTappableJsonFileName(rawUrl: String): String? {
        resolveErrorMessage = null
        rotationGameKey = GAME_KEY_TAPPABLE_BOOK_ROTATION
        val spec = parseTappableUrlSpec(rawUrl.trim())
        val langFilter = spec.languageFilter?.takeIf { it.length >= 2 }

        if (!spec.useRotation && spec.explicitFile != null) {
            useBookRotation = false
            return normalizeTappableJsonFileName(spec.explicitFile)
        }

        useBookRotation = true
        rotationGameKey = buildTappableRotationGameKey(langFilter, spec.ufliFilenameMode)
        val files = discoverTappableBookFiles(langFilter, spec.ufliFilenameMode)
        if (files.isEmpty()) return null

        rotationBookCount = files.size
        val dpm = DailyProgressManager(this)
        val profile = dpm.getCurrentKid()
        val refreshed = dpm.refetchSessionFromDb(profile)
        if (refreshed.isFailure) {
            val msg = refreshed.exceptionOrNull()?.message ?: "Unknown DB error"
            Log.e(TAG, "Book rotation refused: DB refetch failed for profile=$profile key=$rotationGameKey: $msg")
            resolveErrorMessage = "Could not load tappable book index from DB. Please try again."
            return null
        }
        val dbIndex = dpm.getGameIndexFromCache(profile, rotationGameKey)
        rotationBookIndex = dbIndex.mod(rotationBookCount)
        Log.d(TAG, "Book rotation index from DB key=$rotationGameKey raw=$dbIndex size=$rotationBookCount")
        return files[rotationBookIndex]
    }

    /**
     * Parsed [Intent] extra [EXTRA_TAPPABLE_TEXT_FILE] / task.url for tappableText.
     */
    private enum class UfliFilenameMode {
        ANY,
        ONLY_UFLI_PREFIX,
        EXCLUDE_UFLI_PREFIX
    }

    private data class TappableUrlSpec(
        val useRotation: Boolean,
        val explicitFile: String?,
        val languageFilter: String?,
        val ufliFilenameMode: UfliFilenameMode = UfliFilenameMode.ANY
    )

    private fun parseUfliFilenameMode(value: String): UfliFilenameMode {
        val v = value.trim().lowercase(Locale.US)
        if (v.isEmpty()) return UfliFilenameMode.ANY
        if (v == "only" || v == "yes" || v == "1" || v == "true") return UfliFilenameMode.ONLY_UFLI_PREFIX
        if (v == "no" || v == "exclude" || v == "0" || v == "false") return UfliFilenameMode.EXCLUDE_UFLI_PREFIX
        return UfliFilenameMode.ANY
    }

    private fun parseTappableUrlSpec(rawUrl: String): TappableUrlSpec {
        val t = rawUrl.trim()
        if (t.isEmpty() || t.equals("rotate", ignoreCase = true) || t.equals("list", ignoreCase = true)) {
            return TappableUrlSpec(true, null, null, UfliFilenameMode.ANY)
        }
        val segments = t.split('?', '&').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.size == 1 && !segments[0].contains('=')) {
            return TappableUrlSpec(false, segments[0], null, UfliFilenameMode.ANY)
        }
        var filePart: String? = null
        var langPart: String? = null
        var ufliMode = UfliFilenameMode.ANY
        for (seg in segments) {
            val eqIdx = seg.indexOf('=')
            if (eqIdx < 0) continue
            val key = seg.substring(0, eqIdx).trim().lowercase(Locale.US)
            val value = seg.substring(eqIdx + 1).trim()
            if (value.isEmpty()) continue
            when (key) {
                "file" -> filePart = value
                "lang", "language" -> langPart = normalizeLangFilter(value)
                "ufli" -> ufliMode = parseUfliFilenameMode(value)
            }
        }
        return if (!filePart.isNullOrBlank()) {
            TappableUrlSpec(false, filePart, langPart?.takeIf { it.length >= 2 }, ufliMode)
        } else {
            TappableUrlSpec(true, null, langPart?.takeIf { it.length >= 2 }, ufliMode)
        }
    }

    private fun buildTappableRotationGameKey(langFilter: String?, ufliMode: UfliFilenameMode): String {
        val base = GAME_KEY_TAPPABLE_BOOK_ROTATION
        val lang = langFilter?.takeIf { it.length >= 2 }
        val mid = if (lang != null) "${base}_$lang" else base
        return when (ufliMode) {
            UfliFilenameMode.ONLY_UFLI_PREFIX -> "${mid}_ufli"
            UfliFilenameMode.EXCLUDE_UFLI_PREFIX -> "${mid}_noufli"
            UfliFilenameMode.ANY -> mid
        }
    }

    private fun basenameForTappableFilter(path: String): String {
        val name = path.substringAfterLast('/').trim()
        return name.removeSuffix(".json").removeSuffix(".JSON").lowercase(Locale.US)
    }

    private fun basenameStartsWithUfli(path: String): Boolean =
        basenameForTappableFilter(path).startsWith("ufli")

    private fun discoverTappableBookFiles(languageFilter: String?, ufliMode: UfliFilenameMode): List<String> {
        val all = fetchTappableBookFilesFromGithub()
        val byLang = run {
            val filt = languageFilter?.trim()?.lowercase(Locale.US)?.takeIf { it.length >= 2 }
                ?: return@run all
            all.filter { fileName -> readRootLanguageFromTappableRemote(fileName) == filt }
        }
        return when (ufliMode) {
            UfliFilenameMode.ANY -> byLang
            UfliFilenameMode.ONLY_UFLI_PREFIX -> byLang.filter { basenameStartsWithUfli(it) }
            UfliFilenameMode.EXCLUDE_UFLI_PREFIX -> byLang.filter { !basenameStartsWithUfli(it) }
        }
    }

    /** First 2-letter tag from values like `fr`, `FR`, `fr-CA`. */
    private fun normalizeLangFilter(value: String): String {
        val token = value.lowercase(Locale.US)
            .split(",", "_", " ", "-")
            .firstOrNull { it.isNotBlank() }
            ?: return ""
        return token.take(2)
    }

    private fun normalizeTappableJsonFileName(raw: String): String {
        val trimmed = raw.removePrefix("file=").trim()
        return if (trimmed.endsWith(".json", ignoreCase = true)) trimmed else "$trimmed.json"
    }

    private fun loadGame(tappableTextFile: String): TappableTextRoot? {
        val fileName = normalizeTappableJsonFileName(tappableTextFile)

        return try {
            val path = "tappableText/$fileName"
            val body = fetchGithubText(path)
            if (body.isNullOrBlank()) {
                Log.e(TAG, "Error loading tappableText from GitHub path=$path: empty response")
                return null
            }
            Gson().fromJson(body, TappableTextRoot::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading tappableText from $fileName", e)
            null
        }
    }

    private fun fetchGithubText(assetPath: String): String? {
        return try {
            val url = "$githubAssetsBase/$assetPath?nocache=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchGithubText failed [$assetPath]: HTTP ${response.code}")
                    return null
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchGithubText exception [$assetPath]", e)
            null
        }
    }

    private fun fetchTappableBookFilesFromGithub(): List<String> {
        return try {
            val request = Request.Builder().url(githubTreeApi).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchTappableBookFilesFromGithub failed: HTTP ${response.code}")
                    return emptyList()
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return emptyList()
                val root = com.google.gson.JsonParser.parseString(body).asJsonObject
                val tree = root.getAsJsonArray("tree") ?: return emptyList()
                val prefix = "app/src/main/assets/tappableText/"
                val files = mutableListOf<String>()
                tree.forEach { node ->
                    val obj = node.asJsonObject
                    val type = obj.get("type")?.asString ?: return@forEach
                    if (type != "blob") return@forEach
                    val fullPath = obj.get("path")?.asString ?: return@forEach
                    if (!fullPath.startsWith(prefix)) return@forEach
                    if (!fullPath.endsWith("_tappable.json", ignoreCase = true)) return@forEach
                    files.add(fullPath.removePrefix(prefix))
                }
                files.sorted()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchTappableBookFilesFromGithub exception", e)
            emptyList()
        }
    }

    private fun readRootLanguageFromTappableRemote(fileName: String): String? {
        return try {
            val body = fetchGithubText("tappableText/$fileName") ?: return null
            val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
            val lang = obj.get("language")?.asString?.trim()?.lowercase(Locale.US) ?: return null
            lang.take(2)
        } catch (e: Exception) {
            Log.w(TAG, "readRootLanguageFromTappableRemote: $fileName", e)
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
                    when (utteranceId) {
                        ttsPageDoneUtteranceId -> {
                            stopNarrationWordHighlight()
                            ttsPageDoneUtteranceId = null
                            beginPageQuestions()
                        }
                        ttsQuestionDoneUtteranceId -> {
                            cancelQuestionUnlockFallback()
                            ttsQuestionDoneUtteranceId = null
                            enableCurrentQuestionInteraction()
                        }
                        utteranceTapSuccessFr -> {
                            val surface = pendingTapSuccessFrenchSurface?.trim().orEmpty()
                            pendingTapSuccessFrenchSurface = null
                            val gloss = pendingTapSuccessEnglishGloss?.trim().orEmpty()
                            pendingTapSuccessEnglishGloss = null
                            if (gloss.isNotEmpty() && surface.isNotEmpty()) {
                                TtsManager.speak(
                                    "$surface means $gloss",
                                    Locale.US,
                                    TextToSpeech.QUEUE_FLUSH,
                                    utteranceTapSuccessDone
                                )
                            } else if (gloss.isNotEmpty()) {
                                TtsManager.speak(
                                    "means $gloss",
                                    Locale.US,
                                    TextToSpeech.QUEUE_FLUSH,
                                    utteranceTapSuccessDone
                                )
                            } else {
                                onTapSuccessTtsFinished()
                            }
                        }
                        utteranceTapSuccessDone -> onTapSuccessTtsFinished()
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId == null) return
                runOnUiThread {
                    when (utteranceId) {
                        ttsPageDoneUtteranceId -> {
                            stopNarrationWordHighlight()
                            ttsPageDoneUtteranceId = null
                            beginPageQuestions()
                        }
                        ttsQuestionDoneUtteranceId -> {
                            cancelQuestionUnlockFallback()
                            ttsQuestionDoneUtteranceId = null
                            enableCurrentQuestionInteraction()
                        }
                        utteranceTapSuccessFr -> {
                            pendingTapSuccessEnglishGloss = null
                            pendingTapSuccessFrenchSurface = null
                            onTapSuccessTtsFinished()
                        }
                        utteranceTapSuccessDone -> onTapSuccessTtsFinished()
                    }
                }
            }
        }
        TtsManager.setOnUtteranceProgressListener(listener)
    }

    private fun speakSingleLine(
        language: String?,
        text: String,
        doneUtteranceId: String,
        localeOverride: Locale? = null
    ) {
        val locale = localeOverride ?: when (language?.lowercase()?.take(2)) {
            "fr" -> Locale.FRENCH
            else -> Locale.US
        }
        if (!TtsManager.isReady()) {
            Log.w(TAG, "TTS not ready for prompt; continuing without speech (uid=$doneUtteranceId)")
            runOnUiThread {
                if (doneUtteranceId == ttsQuestionDoneUtteranceId) {
                    ttsQuestionDoneUtteranceId = null
                    enableCurrentQuestionInteraction()
                } else if (doneUtteranceId == ttsPageDoneUtteranceId) {
                    ttsPageDoneUtteranceId = null
                    beginPageQuestions()
                }
            }
            return
        }
        TtsManager.whenReady(Runnable {
            TtsManager.speak(text, locale, TextToSpeech.QUEUE_FLUSH, doneUtteranceId)
        })
    }

    /**
     * Pulls the English gloss from legacy JSON prompts such as
     * `Tape le mot qui veut dire 'fridge'.`
     */
    private fun extractEnglishGlossFromPrompt(prompt: String): String? {
        val normalized = prompt.replace('’', '\'').replace('«', '"').replace('»', '"')
        // Quoted: veut dire 'cat' / dire "dog"
        Regex("(?:veut|veux)\\s+dire\\s+['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)?.trim()?.let { if (it.isNotEmpty()) return it }
        Regex("dire\\s+['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)?.trim()?.let { if (it.isNotEmpty()) return it }
        // Unquoted trailing English word: "… qui veut dire cat" / "… veux dire cat"
        Regex("(?:veut|veux)\\s+dire\\s+([A-Za-z][A-Za-z'-]*)\\s*[.!?…]*\\s*$", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)?.trim()?.let { if (it.isNotEmpty()) return it }
        return null
    }

    /**
     * Easy-mode copy for tap questions: English instruction naming the French answer.
     */
    private fun buildEasyTapPrompt(question: TappableWordQuestion): String {
        val fr = question.correctWord.trim()
        val gloss = extractEnglishGlossFromPrompt(question.prompt)
        if (gloss.isNullOrEmpty()) {
            return "Find and tap the French word: $fr."
        }
        // Full English instruction; entire prompt is read with US TTS (see [showQuestion]).
        return "Find the French word that means $gloss. Then tap that word on the page."
    }

    private fun speakTextByChunks(language: String?, fullText: String, doneUtteranceId: String) {
        val locale = when (language?.lowercase()?.take(2)) {
            "fr" -> Locale.FRENCH
            else -> Locale.US
        }
        if (!TtsManager.isReady()) {
            Log.w(TAG, "TTS not ready for page narration; continuing without speech (uid=$doneUtteranceId)")
            runOnUiThread {
                if (doneUtteranceId == ttsPageDoneUtteranceId) {
                    ttsPageDoneUtteranceId = null
                }
                beginPageQuestions()
            }
            return
        }
        startNarrationWordHighlight(fullText)
        TtsManager.whenReady(Runnable {
            val chunks = fullText.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            if (chunks.isEmpty()) {
                stopNarrationWordHighlight()
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

        stopNarrationWordHighlight()
        interactionEnabled = false
        activeTapCorrectNormalizedToken = null
        optionsContainer.removeAllViews()
        questionContainer.visibility = View.GONE

        currentWordSpans = emptyList()
        currentPageSpannable = null

        val page = g.pages[pageIndex]
        loadPageImage(page)

        val fullText = page.text.joinToString(" ")
        textContainer.visibility = if (fullText.isNotBlank()) View.VISIBLE else View.GONE
        pageText.text = ""
        setClickableWordsForPage(fullText)
        pageText.text = currentPageSpannable

        // Prepare question list for this page.
        currentQuestions = buildQuestionsForPage(page)
        currentQuestionIndex = 0

        // Speak the page after a small delay so image/text settle before narration starts.
        val utteranceId = "tt_page_${pageIndex}_done"
        ttsPageDoneUtteranceId = utteranceId
        ttsQuestionDoneUtteranceId = null
        val narrationDelayMs = if (pageIndex == 0) firstPageNarrationDelayMs else pageTurnNarrationDelayMs
        Log.d(TAG, "Delaying page narration by ${narrationDelayMs}ms for page=$pageIndex")
        tapDebounceHandler.postDelayed(
            { speakTextByChunks(g.language, fullText, utteranceId) },
            narrationDelayMs
        )

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
        clearPageTextHighlightSpans()
        if (q is PageQuestion.TapWord) {
            tapWordWrongGuesses = 0
            tapAnswerRevealed = false
        }

        val prompt: String
        val tapPromptLocale: Locale?
        when (q) {
            is PageQuestion.TapWord -> {
                val bookLang = g.language?.lowercase(Locale.US)?.take(2).orEmpty()
                if (easyMode && bookLang == "fr") {
                    prompt = buildEasyTapPrompt(q.question)
                    tapPromptLocale = Locale.US
                } else if (easyMode) {
                    // English (or non-French) books: prompts are already in the book language.
                    prompt = q.question.prompt
                    tapPromptLocale = Locale.US
                } else {
                    prompt = q.question.prompt
                    tapPromptLocale = null
                }
            }
            is PageQuestion.Comprehension -> {
                prompt = q.question.prompt
                tapPromptLocale = null
            }
        }

        questionTitle.text = prompt
        btnNext.isEnabled = false

        // Speak prompt, then enable interactions.
        val utteranceId = "tt_page_${currentPageIndex}_q_${questionIndex}_done"
        ttsQuestionDoneUtteranceId = utteranceId
        ttsPageDoneUtteranceId = null
        scheduleQuestionUnlockFallback(utteranceId)
        speakSingleLine(g.language, prompt, utteranceId, tapPromptLocale)

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

        cancelQuestionUnlockFallback()
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
        if (!useBookRotation || rotationBookCount <= 0) {
            setResult(RESULT_OK, resultIntent)
            finish()
            return
        }
        val nextIndex = (rotationBookIndex + 1).mod(rotationBookCount)
        lifecycleScope.launch(Dispatchers.IO) {
            val dpm = DailyProgressManager(this@TappableTextActivity)
            val profile = dpm.getCurrentKid()
            val r = dpm.updateGameIndexInDbSync(profile, rotationGameKey, nextIndex)
            if (r.isFailure) {
                Log.e(TAG, "Failed to save tappable book rotation index=$nextIndex", r.exceptionOrNull())
            } else {
                Log.d(TAG, "Saved tappable book rotation nextIndex=$nextIndex for profile=$profile")
            }
            withContext(Dispatchers.Main) {
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }
    }

    override fun onBackPressed() {
        cancelQuestionUnlockFallback()
        stopNarrationWordHighlight()
        TtsManager.stop()
        TimeTracker(this).endActivity("tappableText")
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    private fun stripDiacritics(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD)
        return n.replace("\\p{M}+".toRegex(), "")
    }

    private fun normalizeWord(raw: String): String {
        // Normalize for taps:
        // - Lowercase
        // - Replace curly apostrophes with ASCII
        // - Strip leading/trailing non-letter characters (keep internal apostrophes)
        // - Strip combining marks so story text vs JSON accents still match
        val s = raw.trim().lowercase(Locale.getDefault())
            .replace('’', '\'')
            .replace('‑', '-')
        val stripped = s.replace(Regex("^[^\\p{L}']+|[^\\p{L}']+$"), "")
        return stripDiacritics(stripped)
    }

    /**
     * True if a visible token matches [correctNorm] from JSON (e.g. story has `l'envers`,
     * [correctWord] is `envers`).
     */
    private fun tokensMatchForTapAnswer(tokenNorm: String, correctNorm: String): Boolean {
        if (tokenNorm == correctNorm) return true
        if (tokenNorm.isEmpty() || correctNorm.isEmpty()) return false
        val elision = Regex("^(l|d|j|m|t|n|c|s|qu)'(.+)$", RegexOption.IGNORE_CASE)
        elision.find(tokenNorm)?.let { if (it.groupValues[2] == correctNorm) return true }
        elision.find(correctNorm)?.let { if (it.groupValues[2] == tokenNorm) return true }
        return false
    }

    /**
     * True if the tapped word is the full answer or any single word of a multi-word [correctNorm]
     * (e.g. correct "old lady" accepts a tap on "old" or "lady").
     */
    private fun tapMatchesCorrectWord(clickedNorm: String, correctNorm: String): Boolean {
        if (tokensMatchForTapAnswer(clickedNorm, correctNorm)) return true
        val parts = correctNorm.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return false
        return parts.any { part -> tokensMatchForTapAnswer(clickedNorm, part) }
    }

    /** Case-insensitive index of [needle] in [haystack], or -1. */
    private fun findIgnoreCaseIndex(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return -1
        return haystack.indexOf(needle, ignoreCase = true)
    }

    private fun clearPageTextHighlightSpans() {
        stopNarrationWordHighlight()
        val spannable = currentPageSpannable ?: return
        spannable.getSpans(0, spannable.length, BackgroundColorSpan::class.java).forEach {
            spannable.removeSpan(it)
        }
        pageText.text = spannable
    }

    private fun stopNarrationWordHighlight() {
        narrationWordRunnable?.let { tapDebounceHandler.removeCallbacks(it) }
        narrationWordRunnable = null
        narrationWordCursor = 0
        val spannable = currentPageSpannable ?: return
        narrationHighlightSpan?.let { spannable.removeSpan(it) }
        narrationHighlightSpan = null
        pageText.text = spannable
    }

    private fun cancelQuestionUnlockFallback() {
        questionUnlockRunnable?.let { tapDebounceHandler.removeCallbacks(it) }
        questionUnlockRunnable = null
    }

    private fun scheduleQuestionUnlockFallback(expectedUtteranceId: String) {
        cancelQuestionUnlockFallback()
        val r = Runnable {
            if (ttsQuestionDoneUtteranceId == expectedUtteranceId && !interactionEnabled) {
                Log.w(TAG, "Question unlock fallback fired for utterance=$expectedUtteranceId")
                ttsQuestionDoneUtteranceId = null
                enableCurrentQuestionInteraction()
            }
        }
        questionUnlockRunnable = r
        tapDebounceHandler.postDelayed(r, questionUnlockFallbackMs)
    }

    private fun startNarrationWordHighlight(fullText: String) {
        stopNarrationWordHighlight()
        if (currentWordSpans.isEmpty()) return
        narrationWordCursor = 0
        val words = currentWordSpans

        fun scheduleNext() {
            if (narrationWordCursor >= words.size) return
            val word = words[narrationWordCursor++]
            val spanBuilder = currentPageSpannable ?: return
            narrationHighlightSpan?.let { spanBuilder.removeSpan(it) }
            val span = BackgroundColorSpan(narrationHighlightColor)
            narrationHighlightSpan = span
            spanBuilder.setSpan(
                span,
                word.start,
                word.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            pageText.text = spanBuilder

            val surface = fullText.substring(word.start, word.end)
            val estMs = (narrationWordBaseMs + narrationWordMsPerChar * surface.length)
                .coerceIn(narrationWordStepMinMs, narrationWordStepMaxMs)
            val next = Runnable { scheduleNext() }
            narrationWordRunnable = next
            tapDebounceHandler.postDelayed(next, estMs)
        }

        scheduleNext()
    }

    private fun onTapSuccessTtsFinished() {
        onQuestionAnsweredCorrect()
    }

    private fun speakExploreWord(surfaceWord: String) {
        val g = game ?: return
        val loc = when (g.language?.lowercase()?.take(2)) {
            "fr" -> Locale.FRENCH
            else -> Locale.US
        }
        runOnUiThread {
            TtsManager.whenReady(Runnable {
                TtsManager.speak(surfaceWord.trim(), loc, TextToSpeech.QUEUE_FLUSH, "tt_explore_word")
            })
        }
    }

    private fun speakTapSuccessThenAdvance(surfaceWord: String, question: TappableWordQuestion) {
        val g = game ?: run {
            onQuestionAnsweredCorrect()
            return
        }
        val isFrench = g.language?.lowercase()?.take(2) == "fr"
        val gloss = extractEnglishGlossFromPrompt(question.prompt)
        TtsManager.whenReady(Runnable {
            when {
                isFrench && gloss != null -> {
                    pendingTapSuccessFrenchSurface = surfaceWord.trim()
                    pendingTapSuccessEnglishGloss = gloss
                    TtsManager.speak(surfaceWord.trim(), Locale.FRENCH, TextToSpeech.QUEUE_FLUSH, utteranceTapSuccessFr)
                }
                isFrench -> {
                    pendingTapSuccessFrenchSurface = null
                    pendingTapSuccessEnglishGloss = null
                    TtsManager.speak(surfaceWord.trim(), Locale.FRENCH, TextToSpeech.QUEUE_FLUSH, utteranceTapSuccessDone)
                }
                else -> {
                    pendingTapSuccessFrenchSurface = null
                    pendingTapSuccessEnglishGloss = null
                    TtsManager.speak(surfaceWord.trim(), Locale.US, TextToSpeech.QUEUE_FLUSH, utteranceTapSuccessDone)
                }
            }
        })
    }

    private fun revealTapAnswerHighlights(correctNormalizedToken: String) {
        val spannable = currentPageSpannable ?: return
        clearPageTextHighlightSpans()
        var highlightedAny = false
        currentWordSpans.forEach { w ->
            if (tapMatchesCorrectWord(w.normalizedToken, correctNormalizedToken)) {
                spannable.setSpan(
                    BackgroundColorSpan(tapRevealHighlightColor),
                    w.start,
                    w.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                highlightedAny = true
            }
        }
        if (!highlightedAny) {
            val q = currentQuestions.getOrNull(currentQuestionIndex) as? PageQuestion.TapWord
            val surface = q?.question?.correctWord?.trim().orEmpty()
            if (surface.isNotEmpty()) {
                val haystack = spannable.toString()
                val idx = findIgnoreCaseIndex(haystack, surface)
                if (idx >= 0) {
                    val end = (idx + surface.length).coerceAtMost(haystack.length)
                    spannable.setSpan(
                        BackgroundColorSpan(tapRevealHighlightColor),
                        idx,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    highlightedAny = true
                }
            }
        }
        pageText.text = spannable
        if (highlightedAny) {
            Toast.makeText(this, tapRevealToastMessage, Toast.LENGTH_LONG).show()
        } else {
            Log.w(TAG, "revealTapAnswerHighlights: no span matched correct=$correctNormalizedToken")
            Toast.makeText(this, "Could not highlight the answer on this page.", Toast.LENGTH_LONG).show()
        }
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
                        if (widget !is TextView) return
                        val tv = widget
                        val rawSurface = tv.text.subSequence(start, end).toString()

                        // Easy mode: hear any word while waiting for the tap question to unlock (TTS still playing).
                        if (easyMode && (activeTapCorrectNormalizedToken == null || !interactionEnabled)) {
                            speakExploreWord(rawSurface)
                            return
                        }
                        if (!interactionEnabled) return

                        val correct = activeTapCorrectNormalizedToken ?: return
                        val qNow = currentQuestions.getOrNull(currentQuestionIndex)
                        if (qNow !is PageQuestion.TapWord) return

                        val clickedNormalizedToken = normalized
                        if (tapMatchesCorrectWord(clickedNormalizedToken, correct)) {
                            interactionEnabled = false
                            highlightCorrectWord(correct)
                            if (easyMode) {
                                Toast.makeText(this@TappableTextActivity, wordTapCorrectMessage, Toast.LENGTH_SHORT).show()
                                speakTapSuccessThenAdvance(rawSurface, qNow.question)
                            } else {
                                Toast.makeText(this@TappableTextActivity, wordTapCorrectMessage, Toast.LENGTH_SHORT).show()
                                onQuestionAnsweredCorrect()
                            }
                        } else {
                            if (easyMode) {
                                speakExploreWord(rawSurface)
                            }
                            if (easyMode && !tapAnswerRevealed) {
                                tapWordWrongGuesses++
                                if (tapWordWrongGuesses >= 3) {
                                    tapAnswerRevealed = true
                                    revealTapAnswerHighlights(correct)
                                }
                            }
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
        clearPageTextHighlightSpans()
        currentWordSpans.forEach { w ->
            if (tapMatchesCorrectWord(w.normalizedToken, correctNormalizedToken)) {
                spannable.setSpan(
                    BackgroundColorSpan(ttsHighlightColor),
                    w.start,
                    w.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        pageText.text = spannable
    }

    private fun loadPageImage(page: TappableTextPage) {
        val imageId = page.image?.imageId?.trim()?.takeIf { it.isNotEmpty() }
        if (imageId == null) {
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            return
        }
        imageView.visibility = View.VISIBLE
        // Default: books/images/<id>.webp|.png — Boukili captures: full path e.g. boukili/singe/p1
        val tryPaths = if (imageId.contains('/')) {
            listOf("$imageId.webp", "$imageId.png")
        } else {
            listOf("books/images/$imageId.webp", "books/images/$imageId.png")
        }
        var loaded = false
        for (path in tryPaths) {
            try {
                assets.open(path).use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    imageView.setImageBitmap(bitmap)
                    loaded = true
                }
            } catch (_: Exception) {
                continue
            }
            if (loaded) break
        }
        if (!loaded) {
            Log.w(TAG, "Could not load image for image_id=$imageId (tried $tryPaths)")
            imageView.setImageDrawable(null)
        }
    }
}

