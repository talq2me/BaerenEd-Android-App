package com.talq2me.baerened

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Parses storyRead task.url: `bookId?start=YYYY-MM-DD&days=0,1-3,4-8`.
 * Day 1 is [start] (America/Toronto). [days] entries are chapter end pages (or 0 = none).
 */
object StoryReadSchedule {
    const val GAME_KEY_PREFIX = "storyRead_"
    private val TORONTO: TimeZone = TimeZone.getTimeZone("America/Toronto")

    data class Spec(
        val bookId: String,
        /** yyyy-MM-dd in Toronto, or null if omitted. */
        val startDate: String?,
        /** One end-page per calendar day since start. 0 means no new pages that day. */
        val dayEndPages: List<Int>
    )

    data class Session(
        val review: Boolean,
        val startPage: Int,
        val endPage: Int
    )

    fun gameKey(bookId: String) = "$GAME_KEY_PREFIX$bookId"

    fun todayToronto(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TORONTO
        return fmt.format(Date())
    }

    fun parseUrl(raw: String): Spec {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Spec("", null, emptyList())
        val qIndex = trimmed.indexOf('?')
        var bookPart = if (qIndex >= 0) trimmed.substring(0, qIndex) else trimmed
        if (bookPart.startsWith("file=")) bookPart = bookPart.removePrefix("file=")
        val bookId = bookPart.substringAfterLast('/').removeSuffix(".json").trim()
        val query = if (qIndex >= 0) trimmed.substring(qIndex + 1) else ""
        val params = mutableMapOf<String, String>()
        if (query.isNotEmpty()) {
            for (part in query.split('&')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                params[part.substring(0, eq)] = part.substring(eq + 1)
            }
        }
        val start = params["start"]?.trim()?.takeIf { it.isNotEmpty() && parseYmd(it) != null }
        val days = params["days"]?.split(',')?.map { parseRangeEnd(it.trim()) } ?: emptyList()
        return Spec(bookId, start, days)
    }

    private fun parseRangeEnd(token: String): Int {
        if (token.isEmpty() || token == "0") return 0
        val dash = token.indexOf('-')
        val endToken = if (dash >= 0) token.substring(dash + 1) else token
        return endToken.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun parseYmd(ymd: String): Calendar? {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TORONTO
            fmt.isLenient = false
            val d = fmt.parse(ymd) ?: return null
            Calendar.getInstance(TORONTO).apply {
                time = d
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Whole days from startYmd to todayYmd (0 = same day). Negative if today is before start. */
    fun daysSinceStart(startYmd: String, todayYmd: String): Int {
        val start = parseYmd(startYmd) ?: return 0
        val today = parseYmd(todayYmd) ?: return 0
        val diffMs = today.timeInMillis - start.timeInMillis
        return TimeUnit.MILLISECONDS.toDays(diffMs).toInt()
    }

    /**
     * @param nextUnreadRaw 0 or missing treated as 1
     * @return null when there is nothing to show today (before start, or 0-page day with no arrears)
     */
    fun session(spec: Spec, todayYmd: String, nextUnreadRaw: Int, bookLastPage: Int): Session? {
        if (bookLastPage <= 0) return null
        val next = if (nextUnreadRaw <= 0) 1 else nextUnreadRaw
        if (next > bookLastPage) {
            return Session(review = true, startPage = 1, endPage = bookLastPage)
        }
        if (spec.startDate != null && daysSinceStart(spec.startDate, todayYmd) < 0) return null
        val dueEnd = dueEndPage(spec, todayYmd, bookLastPage)
        if (dueEnd < next) return null
        return Session(
            review = false,
            startPage = next,
            endPage = dueEnd.coerceAtMost(bookLastPage)
        )
    }

    fun dueEndPage(spec: Spec, todayYmd: String, bookLastPage: Int): Int {
        if (spec.dayEndPages.isEmpty()) return bookLastPage
        if (spec.startDate != null && daysSinceStart(spec.startDate, todayYmd) < 0) return 0
        val dayIndex = if (spec.startDate == null) {
            spec.dayEndPages.lastIndex
        } else {
            daysSinceStart(spec.startDate, todayYmd)
        }
        if (dayIndex < 0) return 0
        val through = minOf(dayIndex, spec.dayEndPages.lastIndex)
        var maxEnd = 0
        for (i in 0..through) {
            val end = spec.dayEndPages[i]
            if (end > maxEnd) maxEnd = end
        }
        if (dayIndex > spec.dayEndPages.lastIndex && maxEnd <= 0) {
            maxEnd = bookLastPage
        }
        return maxEnd
    }
}
