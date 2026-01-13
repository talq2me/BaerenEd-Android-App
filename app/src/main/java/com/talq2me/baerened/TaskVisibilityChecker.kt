package com.talq2me.baerened

import java.util.Calendar
import java.text.SimpleDateFormat

/**
 * Centralized task visibility checker
 * Handles showdays, hidedays, displayDays, and disable date logic
 */
object TaskVisibilityChecker {
    
    /**
     * Checks if a task is visible based on its visibility properties
     */
    fun isTaskVisible(
        showdays: String?,
        hidedays: String?,
        displayDays: String? = null,
        disable: String? = null,
        currentDate: Calendar = Calendar.getInstance()
    ): Boolean {
        // Check disable date first - if current date is before disable date, hide the task
        if (!disable.isNullOrEmpty()) {
            val disableDate = parseDisableDate(disable)
            if (disableDate != null) {
                val today = Calendar.getInstance().apply {
                    timeInMillis = currentDate.timeInMillis
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                // If today is before the disable date, task is disabled (not visible)
                if (today.before(disableDate)) {
                    return false
                }
            } else {
                // If disable is not a date, treat it as a boolean-like string
                if (disable.equals("true", ignoreCase = true)) {
                    return false
                }
            }
        }

        val today = currentDate.get(Calendar.DAY_OF_WEEK)
        val todayShort = when (today) {
            Calendar.MONDAY -> "mon"
            Calendar.TUESDAY -> "tue"
            Calendar.WEDNESDAY -> "wed"
            Calendar.THURSDAY -> "thu"
            Calendar.FRIDAY -> "fri"
            Calendar.SATURDAY -> "sat"
            Calendar.SUNDAY -> "sun"
            else -> ""
        }

        if (!hidedays.isNullOrEmpty()) {
            if (hidedays.split(",").map { it.trim() }.contains(todayShort)) {
                return false // Hide if today is in hidedays
            }
        }

        // Check displayDays first (if set, only show on those days)
        if (!displayDays.isNullOrEmpty()) {
            return displayDays.split(",").map { it.trim() }.contains(todayShort) // Show only if today is in displayDays
        }

        if (!showdays.isNullOrEmpty()) {
            return showdays.split(",").map { it.trim() }.contains(todayShort) // Show only if today is in showdays
        }

        return true // Visible by default if no restrictions
    }

    /**
     * Checks if a task is visible (using Task object)
     */
    fun isTaskVisible(task: Task, currentDate: Calendar = Calendar.getInstance()): Boolean {
        return isTaskVisible(task.showdays, task.hidedays, task.displayDays, task.disable, currentDate)
    }

    /**
     * Checks if a checklist item is visible
     */
    fun isItemVisible(item: ChecklistItem, currentDate: Calendar = Calendar.getInstance()): Boolean {
        return isTaskVisible(item.showdays, item.hidedays, null, null, currentDate)
    }

    /**
     * Parses a disable date string
     */
    private fun parseDisableDate(dateString: String?): Calendar? {
        if (dateString.isNullOrEmpty()) return null
        
        return try {
            // Try parsing format like "Jan 15, 2027" or "Nov 24, 2025"
            val formatter = SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
            val date = formatter.parse(dateString.trim())
            if (date != null) {
                Calendar.getInstance().apply {
                    time = date
                    // Set time to start of day for accurate comparison
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            } else null
        } catch (e: Exception) {
            android.util.Log.e("TaskVisibilityChecker", "Error parsing disable date: $dateString", e)
            null
        }
    }
}
