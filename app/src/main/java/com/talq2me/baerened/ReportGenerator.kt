package com.talq2me.baerened

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Generates progress reports for parents in multiple formats
 */
class ReportGenerator(private val context: Context) {

    /**
     * Report formats supported
     */
    enum class ReportFormat {
        TEXT, HTML, CSV, EMAIL
    }

    /**
     * Task details with time and answer counts
     */
    data class TaskDetails(
        val taskId: String,
        val taskName: String,
        val isRequired: Boolean,
        val isCompleted: Boolean,
        val timeSpentSeconds: Long,
        val correctAnswers: Int,
        val incorrectAnswers: Int,
        val isVideoTask: Boolean = false,
        val isChromePageTask: Boolean = false
    ) {
        val timeSpentFormatted: String
            get() {
                val minutes = (timeSpentSeconds / 60).toInt()
                val seconds = (timeSpentSeconds % 60).toInt()
                return if (minutes > 0) "${minutes}m${seconds}s" else "${seconds}s"
            }
        
        val answerInfo: String
            get() {
                // Don't show answer counts for videos or chrome pages
                if (isVideoTask || isChromePageTask) {
                    return ""
                }
                // Use shorter format: # with green check and # with red X
                return "   $correctAnswers ✅   $incorrectAnswers ❌"
            }
    }

    /**
     * Separates completed game sessions into required and optional sections
     */
    private fun getSeparatedCompletedGames(
        report: DailyProgressManager.ComprehensiveProgressReport
    ): Pair<List<TimeTracker.ActivitySession>, List<TimeTracker.ActivitySession>> {
        val progressManager = DailyProgressManager(context)
        val visibleConfig = report.config?.let { progressManager.filterVisibleContent(it) } ?: return Pair(emptyList(), emptyList())
        
        val completedRequiredGameSessions = mutableListOf<TimeTracker.ActivitySession>()
        val completedOptionalGameSessions = mutableListOf<TimeTracker.ActivitySession>()
        
        visibleConfig.sections?.forEach { section ->
            val isRequired = section.id == "required"
            section.tasks?.forEach { task ->
                val taskId = task.launch ?: return@forEach
                val taskName = task.title ?: taskId
                val uniqueTaskId = progressManager.getUniqueTaskId(taskId, taskName, section.id ?: "unknown")
                
                // Find matching completed game sessions
                report.completedGameSessions.forEach { session ->
                    if (session.activityId == uniqueTaskId && session.activityType == "game") {
                        val sessionKey = "${session.activityId}_${session.startTime}"
                        if (isRequired) {
                            if (!completedRequiredGameSessions.any { "${it.activityId}_${it.startTime}" == sessionKey }) {
                                completedRequiredGameSessions.add(session)
                            }
                        } else {
                            if (!completedOptionalGameSessions.any { "${it.activityId}_${it.startTime}" == sessionKey }) {
                                completedOptionalGameSessions.add(session)
                            }
                        }
                    }
                }
            }
        }
        
        return Pair(completedRequiredGameSessions, completedOptionalGameSessions)
    }

    /**
     * Matches tasks from config with sessions and calculates details
     * Only includes tasks that are visible today
     */
    private fun getTaskDetails(report: DailyProgressManager.ComprehensiveProgressReport): List<TaskDetails> {
        val config = report.config ?: return emptyList()
        
        // Filter config to only include tasks visible today
        val progressManager = DailyProgressManager(context)
        val visibleConfig = progressManager.filterVisibleContent(config)
        
        val allSessions = (report.gameSessions + report.webGameSessions + report.videoSessions +
                report.chromePageSessions).distinctBy { "${it.activityType}_${it.activityId}_${it.startTime}" }
        
        val taskDetails = mutableListOf<TaskDetails>()
        
        visibleConfig.sections?.forEach { section ->
            val isRequired = section.id == "required"
            section.tasks?.forEach { task ->
                val taskId = task.launch ?: return@forEach
                val taskName = task.title ?: taskId
                
                // Use unique task ID to match sessions (includes section info for optional tasks)
                val uniqueTaskId = progressManager.getUniqueTaskId(taskId, taskName, section.id ?: "unknown")
                
                // Check completion status using the appropriate task ID
                val isCompleted = if (isRequired) {
                    report.completedTasks.contains(taskId)
                } else {
                    report.completedTasks.contains(uniqueTaskId)
                }
                
                // Match sessions by unique activityId (which includes section info for optional tasks)
                val matchingSessions = allSessions.filter { session ->
                    session.activityId == uniqueTaskId
                }
                
                val totalTimeSeconds = matchingSessions.sumOf { it.durationSeconds }
                val totalCorrect = matchingSessions.sumOf { it.correctAnswers }
                val totalIncorrect = matchingSessions.sumOf { it.incorrectAnswers }
                
                taskDetails.add(
                    TaskDetails(
                        taskId = taskId,
                        taskName = taskName,
                        isRequired = isRequired,
                        isCompleted = isCompleted,
                        timeSpentSeconds = totalTimeSeconds,
                        correctAnswers = totalCorrect,
                        incorrectAnswers = totalIncorrect,
                        isVideoTask = task.videoSequence != null || task.video != null,
                        isChromePageTask = task.chromePage == true
                    )
                )
            }
        }
        
        return taskDetails
    }

    /**
     * Generates a comprehensive daily progress report
     */
    fun generateDailyReport(
        progressReport: DailyProgressManager.ComprehensiveProgressReport,
        childName: String = "Child",
        format: ReportFormat = ReportFormat.TEXT,
        rewardMinutesUsed: Int = 0
    ): String {
        // Log the reward minutes being included in the report
        android.util.Log.d("ReportGenerator", "Generating $format report with reward minutes: $rewardMinutesUsed")
        
        return when (format) {
            ReportFormat.TEXT -> generateTextReport(progressReport, childName, rewardMinutesUsed)
            ReportFormat.HTML -> generateHtmlReport(progressReport, childName, rewardMinutesUsed)
            ReportFormat.CSV -> generateCsvReport(progressReport, childName, rewardMinutesUsed)
            ReportFormat.EMAIL -> generateEmailReport(progressReport, childName, rewardMinutesUsed)
        }
    }

    /**
     * Generates a plain text report
     */
    private fun generateTextReport(
        report: DailyProgressManager.ComprehensiveProgressReport,
        childName: String,
        rewardMinutesUsed: Int = 0
    ): String {
        val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
        val formattedDate = dateFormat.format(SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", Locale.getDefault()).parse(report.date)!!)

        return buildString {
            appendLine("📊 DAILY PROGRESS REPORT")
            appendLine("=".repeat(50))
            appendLine("Child: $childName")
            appendLine("Date: $formattedDate")
            if (rewardMinutesUsed > 0) {
                appendLine("🎮 Reward Time Earned: $rewardMinutesUsed minutes")
            }
            appendLine()

            appendLine("🎯 OVERALL PROGRESS")
            appendLine("-".repeat(30))
            appendLine("Stars Earned: ${report.earnedStars}/${report.totalStars} (${"%.1f".format(report.completionRate)}%)")
            appendLine("Coins (total): ${report.earnedCoins}")
            appendLine("Total Time: ${report.totalTimeMinutes} minutes")

            if (report.choresCompletedToday.isNotEmpty()) {
                appendLine()
                appendLine("🪙 CHORES 4 $$ (TODAY)")
                appendLine("-".repeat(30))
                report.choresCompletedToday.forEach { appendLine("  ✓ $it") }
                appendLine("Coins earned today (chores): ${report.coinsEarnedToday}")
                appendLine()
            }

            // Count required incomplete tasks
            val taskDetails = getTaskDetails(report)
            val requiredIncompleteCount = taskDetails.count { it.isRequired && !it.isCompleted }
            if (requiredIncompleteCount > 0) {
                appendLine("Required Tasks Incomplete: $requiredIncompleteCount")
            }
            appendLine()

            appendLine("🎮 ACTIVITY SUMMARY")
            appendLine("-".repeat(30))
            appendLine("Games Played: ${report.gamesPlayed}")
            appendLine("Videos Watched: ${report.videosWatched}")
            appendLine("Total Sessions: ${report.totalSessions}")
            appendLine("Questions Answered:   ${report.totalCorrectAnswers}✅   ${report.totalIncorrectAnswers}❌")
            appendLine()

            // Show completed games separately for required and optional sections
            val (completedRequiredGameSessions, completedOptionalGameSessions) = getSeparatedCompletedGames(report)
            
            if (completedRequiredGameSessions.isNotEmpty()) {
                appendLine("🎯 COMPLETED GAMES (REQUIRED)")
                appendLine("-".repeat(30))
                completedRequiredGameSessions.forEach { session ->
                    val answerInfo = if (session.correctAnswers > 0 || session.incorrectAnswers > 0) {
                        " (${session.correctAnswers} correct, ${session.incorrectAnswers} incorrect)"
                    } else {
                        ""
                    }
                    appendLine("  ${session.activityName} ${session.formattedDuration} Completed$answerInfo")
                }
                appendLine()
            }
            
            if (completedOptionalGameSessions.isNotEmpty()) {
                appendLine("🎯 COMPLETED GAMES (EXTRA PRACTICE)")
                appendLine("-".repeat(30))
                completedOptionalGameSessions.forEach { session ->
                    val answerInfo = if (session.correctAnswers > 0 || session.incorrectAnswers > 0) {
                        " (${session.correctAnswers} correct, ${session.incorrectAnswers} incorrect)"
                    } else {
                        ""
                    }
                    appendLine("  ${session.activityName} ${session.formattedDuration} Completed$answerInfo")
                }
                appendLine()
            }

            if (report.gamesPlayed > 0) {
                appendLine("🎮 ALL GAME SESSIONS")
                appendLine("-".repeat(30))
                appendLine("Average Game Time: ${report.averageGameTimeMinutes} minutes")
                if (report.mostPlayedGame != null) {
                    appendLine("Most Played Game: ${report.mostPlayedGame}")
                }
                appendLine()

                appendLine("All Game Sessions:")
                report.gameSessions.forEach { session ->
                    val status = if (session.completed) "Completed" else "In Progress"
                    val answerInfo = if (session.correctAnswers > 0 || session.incorrectAnswers > 0) {
                        " (${session.correctAnswers} correct, ${session.incorrectAnswers} incorrect)"
                    } else {
                        ""
                    }
                    appendLine("  ${session.activityName} ${session.formattedDuration} $status$answerInfo")
                }
                appendLine()
            }

            if (report.videosWatched > 0) {
                appendLine("📺 VIDEO DETAILS")
                appendLine("-".repeat(30))
                appendLine("Average Video Time: ${report.averageVideoTimeMinutes} minutes")
                appendLine()

                appendLine("Video Sessions:")
                report.videoSessions.forEach { session ->
                    appendLine("  • ${session.activityName}: ${"%.1f".format(session.durationMinutes)} min")
                }
                appendLine()
            }

            if (report.webGameSessions.isNotEmpty()) {
                appendLine("🌐 WEB GAMES")
                appendLine("-".repeat(30))
                appendLine("Web games played:")
                report.webGameSessions.forEach { session ->
                    val status = if (session.completed) "Completed" else "In Progress"
                    val answerInfo = if (session.correctAnswers > 0 || session.incorrectAnswers > 0) {
                        " (${session.correctAnswers} correct, ${session.incorrectAnswers} incorrect)"
                    } else {
                        ""
                    }
                    appendLine("  • ${session.activityName}: ${session.formattedDuration} ($status)$answerInfo")
                }
                appendLine()
            }

            if (report.chromePageSessions.isNotEmpty()) {
                appendLine("🌍 WEB PAGES VISITED")
                appendLine("-".repeat(30))
                appendLine("Pages visited:")
                report.chromePageSessions.forEach { session ->
                    val status = if (session.completed) "Visited" else "In Progress"
                    appendLine("  • ${session.activityName}: ${session.formattedDuration} ($status)")
                }
                appendLine()
            }
            
            // Get checklist items from visible config only
            val progressManager = DailyProgressManager(context)
            val visibleConfig = report.config?.let { progressManager.filterVisibleContent(it) }
            val checklistItems = mutableListOf<Triple<String, Boolean, Int>>() // itemName, isCompleted, stars
            visibleConfig?.sections?.forEach { section ->
                section.items?.forEach { item ->
                    val itemId = item.id ?: "checkbox_${item.label}"
                    val isCompleted = report.completedTasks.contains(itemId)
                    val stars = item.stars ?: 0
                    checklistItems.add(Triple(item.label ?: itemId, isCompleted, stars))
                }
            }

            // Completed Required Tasks
            val completedRequiredTasks = taskDetails.filter { it.isRequired && it.isCompleted }
            if (completedRequiredTasks.isNotEmpty()) {
                appendLine("✅ COMPLETED REQUIRED TASKS")
                appendLine("-".repeat(30))
                completedRequiredTasks.forEach { task ->
                    val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                    appendLine("  ${task.taskName}: $timeInfo${task.answerInfo}")
                }
                appendLine()
            }
            
            // Incomplete Required Tasks
            val incompleteRequiredTasks = taskDetails.filter { it.isRequired && !it.isCompleted }
            if (incompleteRequiredTasks.isNotEmpty()) {
                appendLine("⏳ INCOMPLETE REQUIRED TASKS")
                appendLine("-".repeat(30))
                incompleteRequiredTasks.forEach { task ->
                    val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                    appendLine("  ${task.taskName}: $timeInfo${task.answerInfo}")
                }
                appendLine()
            }
            
            // Checklist Items (separate section)
            if (checklistItems.isNotEmpty()) {
                appendLine("📋 CHECKLIST ITEMS")
                appendLine("-".repeat(30))
                checklistItems.forEach { item ->
                    val (label, isCompleted, stars) = item
                    val status = if (isCompleted) "✓" else "○"
                    val starsText = if (stars > 0) " (${stars}⭐)" else ""
                    appendLine("  $status $label$starsText")
                }
                appendLine()
            }
            
            // Separate optional and bonus tasks (using visible config)
            val optionalTasks = taskDetails.filter { !it.isRequired && visibleConfig?.sections?.any { section -> 
                section.id == "optional" && section.tasks?.any { task -> task.launch == it.taskId } == true 
            } == true }
            val bonusTasks = taskDetails.filter { !it.isRequired && visibleConfig?.sections?.any { section -> 
                section.id == "bonus" && section.tasks?.any { task -> task.launch == it.taskId } == true 
            } == true }
            
            // Extra Practice Tasks (optional tasks, both completed and incomplete)
            if (optionalTasks.isNotEmpty()) {
                appendLine("🎯 EXTRA PRACTICE TASKS")
                appendLine("-".repeat(30))
                optionalTasks.forEach { task ->
                    val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                    appendLine("  ${task.taskName}: $timeInfo${task.answerInfo}")
                }
                appendLine()
            }
            
            // Bonus Tasks
            if (bonusTasks.isNotEmpty()) {
                appendLine("🎮 BONUS TASKS")
                appendLine("-".repeat(30))
                bonusTasks.forEach { task ->
                    val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                    appendLine("  ${task.taskName}: $timeInfo${task.answerInfo}")
                }
                appendLine()
            }

            appendLine("🏆 ACHIEVEMENTS")
            appendLine("-".repeat(30))
            if (report.earnedStars == report.totalStars && report.totalStars > 0) {
                appendLine("  🌟 All stars earned today!")
            }
            if (report.totalTimeMinutes > 60) {
                appendLine("  ⏰ Over 1 hour of learning time!")
            }
            if (report.longestSessionMinutes > 30) {
                appendLine("  🎯 Longest session: ${report.longestSessionMinutes} minutes")
            }
            appendLine()

            appendLine("💡 INSIGHTS")
            appendLine("-".repeat(30))
            when {
                report.completionRate >= 80 -> appendLine("  Excellent progress! Keep up the great work!")
                report.completionRate >= 60 -> appendLine("  Good progress! Almost there!")
                report.completionRate >= 40 -> appendLine("  Making progress! Try completing a few more tasks.")
                else -> appendLine("  Every bit of progress counts! Keep trying!")
            }

            if (report.totalTimeMinutes < 30) {
                appendLine("  Consider spending a bit more time on activities for better learning outcomes.")
            } else if (report.totalTimeMinutes > 120) {
                appendLine("  Great dedication to learning! Make sure to take breaks.")
            }
        }
    }

    /**
     * Generates an HTML report
     */
    private fun generateHtmlReport(
        report: DailyProgressManager.ComprehensiveProgressReport,
        childName: String,
        rewardMinutesUsed: Int = 0
    ): String {
        val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
        val formattedDate = dateFormat.format(SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", Locale.getDefault()).parse(report.date)!!)

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Daily Progress Report - $childName</title>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; margin: 20px; background-color: #f5f5f5; }
                    .header { background-color: #4CAF50; color: white; padding: 20px; border-radius: 8px; text-align: center; }
                    .section { background-color: white; margin: 20px 0; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                    .metric { display: inline-block; margin: 10px; padding: 10px; background-color: #e8f5e8; border-radius: 5px; text-align: center; min-width: 100px; }
                    .metric-value { font-size: 24px; font-weight: bold; color: #4CAF50; }
                    .metric-label { font-size: 12px; color: #666; }
                    .task-list { list-style: none; padding: 0; }
                    .task-item { background-color: #f0f8ff; margin: 5px 0; padding: 10px; border-left: 4px solid #4CAF50; }
                    .achievement { background-color: #fff3cd; border: 1px solid #ffc107; border-radius: 5px; padding: 10px; margin: 10px 0; }
                    table { width: 100%; border-collapse: collapse; margin-top: 10px; }
                    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                    th { background-color: #f2f2f2; }
                    .progress-bar { width: 100%; height: 20px; background-color: #e0e0e0; border-radius: 10px; overflow: hidden; }
                    .progress-fill { height: 100%; background-color: #4CAF50; transition: width 0.3s; }
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>📊 Daily Progress Report</h1>
                    <h2>$childName</h2>
                    <p>$formattedDate</p>
                    ${if (rewardMinutesUsed > 0) "<p>🎮 Reward Time Earned: $rewardMinutesUsed minutes</p>" else ""}
                </div>

                <div class="section">
                    <h3>🎯 Overall Progress</h3>
                    <div class="metric">
                        <div class="metric-value">${"%.1f".format(report.completionRate)}%</div>
                        <div class="metric-label">Completion Rate</div>
                    </div>
                    <div class="metric">
                        <div class="metric-value">${report.earnedStars}/${report.totalStars}</div>
                        <div class="metric-label">Stars Earned</div>
                    </div>
                    <div class="metric">
                        <div class="metric-value">${report.totalTimeMinutes}</div>
                        <div class="metric-label">Minutes</div>
                    </div>
                    <div class="metric">
                        <div class="metric-value">${report.earnedCoins}</div>
                        <div class="metric-label">Coins (total)</div>
                    </div>
                    ${if (report.choresCompletedToday.isNotEmpty()) """
                    <div class="metric">
                        <div class="metric-value">${report.coinsEarnedToday}</div>
                        <div class="metric-label">Coins from Chores Today</div>
                    </div>
                    """ else ""}
                    ${run {
                        val taskDetails = getTaskDetails(report)
                        val requiredIncompleteCount = taskDetails.count { it.isRequired && !it.isCompleted }
                        if (requiredIncompleteCount > 0) {
                            """<div class="metric">
                                <div class="metric-value">$requiredIncompleteCount</div>
                                <div class="metric-label">Required Tasks Incomplete</div>
                            </div>"""
                        } else ""
                    }}
                    <div style="margin-top: 20px;">
                        <div class="progress-bar">
                            <div class="progress-fill" style="width: ${report.completionRate}%"></div>
                        </div>
                        <p>Progress towards daily goals</p>
                    </div>
                </div>

                <div class="section">
                    <h3>🎮 Activity Summary</h3>
                    <table>
                        <tr><th>Metric</th><th>Value</th></tr>
                        <tr><td>Games Played</td><td>${report.gamesPlayed}</td></tr>
                        <tr><td>Videos Watched</td><td>${report.videosWatched}</td></tr>
                        <tr><td>Total Sessions</td><td>${report.totalSessions}</td></tr>
                        <tr><td>Total Time</td><td>${report.totalTimeMinutes} minutes</td></tr>
                    </table>
                </div>

                ${if (report.choresCompletedToday.isNotEmpty()) """
                <div class="section">
                    <h3>🪙 Chores 4 $$ (Today)</h3>
                    <ul>
                        ${report.choresCompletedToday.joinToString("") { "<li>$it</li>" }}
                    </ul>
                    <p>Coins earned today (chores): ${report.coinsEarnedToday}</p>
                </div>
                """ else ""}

                ${run {
                    val (completedRequiredGameSessions, completedOptionalGameSessions) = getSeparatedCompletedGames(report)
                    buildString {
                        if (completedRequiredGameSessions.isNotEmpty()) {
                            append("""<div class="section">
                                <h3>🎯 Completed Games (Required)</h3>
                                <table>
                                    <tr><th>Game</th><th>Duration</th><th>Status</th><th>Answers</th></tr>
                                    ${completedRequiredGameSessions.joinToString("") { session ->
                                        val answerInfo = if (session.correctAnswers > 0 || session.incorrectAnswers > 0) {
                                            "${session.correctAnswers}✅ ${session.incorrectAnswers}❌"
                                        } else {
                                            ""
                                        }
                                        "<tr><td>${session.activityName}</td><td>${session.formattedDuration}</td><td>Completed</td><td>$answerInfo</td></tr>"
                                    }}
                                </table>
                            </div>""")
                        }
                        if (completedOptionalGameSessions.isNotEmpty()) {
                            append("""<div class="section">
                                <h3>🎯 Completed Games (Extra Practice)</h3>
                                <table>
                                    <tr><th>Game</th><th>Duration</th><th>Status</th><th>Answers</th></tr>
                                    ${completedOptionalGameSessions.joinToString("") { session ->
                                        val answerInfo = if (session.correctAnswers > 0 || session.incorrectAnswers > 0) {
                                            "${session.correctAnswers}✅ ${session.incorrectAnswers}❌"
                                        } else {
                                            ""
                                        }
                                        "<tr><td>${session.activityName}</td><td>${session.formattedDuration}</td><td>Completed</td><td>$answerInfo</td></tr>"
                                    }}
                                </table>
                            </div>""")
                        }
                    }
                }}

                ${if (report.gameSessions.isNotEmpty()) """
                <div class="section">
                    <h3>🎮 All Game Sessions</h3>
                    <table>
                        <tr><th>Game</th><th>Duration</th><th>Status</th></tr>
                        ${report.gameSessions.joinToString("") { session ->
                            val status = if (session.completed) "Completed" else "In Progress"
                            "<tr><td>${session.activityName}</td><td>${session.formattedDuration}</td><td>$status</td></tr>"
                        }}
                    </table>
                </div>
                """ else ""}

                ${if (report.videoSessions.isNotEmpty()) """
                <div class="section">
                    <h3>📺 Video Sessions</h3>
                    <table>
                        <tr><th>Video</th><th>Duration</th></tr>
                        ${report.videoSessions.joinToString("") { session ->
                            "<tr><td>${session.activityName}</td><td>${"%.1f".format(session.durationMinutes)} min</td></tr>"
                        }}
                    </table>
                </div>
                """ else ""}

                ${run {
                    val taskDetails = getTaskDetails(report)
                    val completedRequiredTasks = taskDetails.filter { it.isRequired && it.isCompleted }
                    val incompleteRequiredTasks = taskDetails.filter { it.isRequired && !it.isCompleted }
                    
                    // Get checklist items from visible config only
                    val progressManager = DailyProgressManager(context)
                    val visibleConfig = report.config?.let { progressManager.filterVisibleContent(it) }
                    val checklistItems = mutableListOf<Triple<String, Boolean, Int>>() // itemName, isCompleted, stars
                    visibleConfig?.sections?.forEach { section ->
                        section.items?.forEach { item ->
                            val itemId = item.id ?: "checkbox_${item.label}"
                            val isCompleted = report.completedTasks.contains(itemId)
                            val stars = item.stars ?: 0
                            checklistItems.add(Triple(item.label ?: itemId, isCompleted, stars))
                        }
                    }
                    
                    // Separate optional and bonus tasks (using visible config)
                    val optionalTasks = taskDetails.filter { !it.isRequired && visibleConfig?.sections?.any { section -> 
                        section.id == "optional" && section.tasks?.any { task -> task.launch == it.taskId } == true 
                    } == true }
                    val bonusTasks = taskDetails.filter { !it.isRequired && visibleConfig?.sections?.any { section -> 
                        section.id == "bonus" && section.tasks?.any { task -> task.launch == it.taskId } == true 
                    } == true }
                    
                    buildString {
                        if (completedRequiredTasks.isNotEmpty()) {
                            append("""<div class="section">
                                <h3>✅ Completed Required Tasks</h3>
                                <ul class="task-list">""")
                            completedRequiredTasks.forEach { task ->
                                append("""<li class="task-item">${task.taskName}: ${task.timeSpentFormatted}${task.answerInfo}</li>""")
                            }
                            append("""</ul>
                            </div>""")
                        }
                        
                        if (incompleteRequiredTasks.isNotEmpty()) {
                            append("""<div class="section">
                                <h3>⏳ Incomplete Required Tasks</h3>
                                <ul class="task-list">""")
                            incompleteRequiredTasks.forEach { task ->
                                val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                                append("""<li class="task-item">${task.taskName}: $timeInfo${task.answerInfo}</li>""")
                            }
                            append("""</ul>
                            </div>""")
                        }
                        
                        // Checklist Items (separate section)
                        if (checklistItems.isNotEmpty()) {
                            append("""<div class="section">
                                <h3>📋 Checklist Items</h3>
                                <ul class="task-list">""")
                            checklistItems.forEach { item ->
                                val (label, isCompleted, stars) = item
                                val status = if (isCompleted) "✓" else "○"
                                val starsText = if (stars > 0) " <span style='color: #ffcc00;'>($stars⭐)</span>" else ""
                                append("""<li class="task-item">$status $label$starsText</li>""")
                            }
                            append("""</ul>
                            </div>""")
                        }
                        
                        if (optionalTasks.isNotEmpty()) {
                            append("""<div class="section">
                                <h3>🎯 Extra Practice Tasks</h3>
                                <ul class="task-list">""")
                            optionalTasks.forEach { task ->
                                val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                                append("""<li class="task-item">${task.taskName}: $timeInfo${task.answerInfo}</li>""")
                            }
                            append("""</ul>
                            </div>""")
                        }
                        
                        if (bonusTasks.isNotEmpty()) {
                            append("""<div class="section">
                                <h3>🎮 Bonus Tasks</h3>
                                <ul class="task-list">""")
                            bonusTasks.forEach { task ->
                                val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                                append("""<li class="task-item">${task.taskName}: $timeInfo${task.answerInfo}</li>""")
                            }
                            append("""</ul>
                            </div>""")
                        }
                    }
                }}

                <div class="section">
                    <h3>🏆 Achievements & Insights</h3>
                    ${when {
                        report.completionRate >= 80 -> "<div class=\"achievement\">🌟 Excellent progress! Keep up the great work!</div>"
                        report.completionRate >= 60 -> "<div class=\"achievement\">👍 Good progress! Almost there!</div>"
                        report.completionRate >= 40 -> "<div class=\"achievement\">📈 Making progress! Try completing a few more tasks.</div>"
                        else -> "<div class=\"achievement\">💪 Every bit of progress counts! Keep trying!</div>"
                    }}
                    ${if (report.totalTimeMinutes < 30) "<div class=\"achievement\">💡 Consider spending a bit more time on activities for better learning outcomes.</div>" else ""}
                    ${if (report.totalTimeMinutes > 120) "<div class=\"achievement\">🎯 Great dedication to learning! Make sure to take breaks.</div>" else ""}
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Generates a CSV report
     */
    private fun generateCsvReport(
        report: DailyProgressManager.ComprehensiveProgressReport,
        childName: String,
        rewardMinutesUsed: Int = 0
    ): String {
        return buildString {
            appendLine("Daily Progress Report for $childName")
            appendLine("Date: ${report.date}")
            if (rewardMinutesUsed > 0) {
                appendLine("Reward Time Earned: $rewardMinutesUsed minutes")
            }
            appendLine()

            appendLine("Summary Metrics")
            appendLine("Metric,Value")
            appendLine("Completion Rate,${"%.1f".format(report.completionRate)}%")
            appendLine("Stars Earned,${report.earnedStars}/${report.totalStars}")
            appendLine("Coins (total),${report.earnedCoins}")
            appendLine("Total Time Minutes,${report.totalTimeMinutes}")
            appendLine("Games Played,${report.gamesPlayed}")
            appendLine("Videos Watched,${report.videosWatched}")
            appendLine("Total Sessions,${report.totalSessions}")
            if (report.choresCompletedToday.isNotEmpty()) {
                appendLine("Chores Completed Today,${report.choresCompletedToday.size}")
                appendLine("Coins from Chores Today,${report.coinsEarnedToday}")
                report.choresCompletedToday.forEach { appendLine("Chore,$it") }
            }
            appendLine()

            val (completedRequiredGameSessions, completedOptionalGameSessions) = getSeparatedCompletedGames(report)
            
            if (completedRequiredGameSessions.isNotEmpty()) {
                appendLine("Completed Game Sessions (Required)")
                appendLine("Game,Duration,Status,Correct,Incorrect")
                completedRequiredGameSessions.forEach { session ->
                    appendLine("${session.activityName},${session.formattedDuration},Completed,${session.correctAnswers},${session.incorrectAnswers}")
                }
                appendLine()
            }
            
            if (completedOptionalGameSessions.isNotEmpty()) {
                appendLine("Completed Game Sessions (Extra Practice)")
                appendLine("Game,Duration,Status,Correct,Incorrect")
                completedOptionalGameSessions.forEach { session ->
                    appendLine("${session.activityName},${session.formattedDuration},Completed,${session.correctAnswers},${session.incorrectAnswers}")
                }
                appendLine()
            }

            if (report.gameSessions.isNotEmpty()) {
                appendLine("All Game Sessions")
                appendLine("Game,Duration,Status")
                report.gameSessions.forEach { session ->
                    val status = if (session.completed) "Completed" else "In Progress"
                    appendLine("${session.activityName},${session.formattedDuration},$status")
                }
                appendLine()
            }

            if (report.videoSessions.isNotEmpty()) {
                appendLine("Video Sessions")
                appendLine("Video,Duration (minutes)")
                report.videoSessions.forEach { session ->
                    appendLine("${session.activityName},${"%.1f".format(session.durationMinutes)}")
                }
                appendLine()
            }

            val taskDetails = getTaskDetails(report)
            val completedRequiredTasks = taskDetails.filter { it.isRequired && it.isCompleted }
            val incompleteRequiredTasks = taskDetails.filter { it.isRequired && !it.isCompleted }
            
            // Get checklist items from visible config only
            val progressManager = DailyProgressManager(context)
            val visibleConfig = report.config?.let { progressManager.filterVisibleContent(it) }
            val checklistItems = mutableListOf<Triple<String, Boolean, Int>>() // itemName, isCompleted, stars
            visibleConfig?.sections?.forEach { section ->
                section.items?.forEach { item ->
                    val itemId = item.id ?: "checkbox_${item.label}"
                    val isCompleted = report.completedTasks.contains(itemId)
                    val stars = item.stars ?: 0
                    checklistItems.add(Triple(item.label ?: itemId, isCompleted, stars))
                }
            }
            
            // Separate optional and bonus tasks (using visible config)
            val optionalTasks = taskDetails.filter { !it.isRequired && visibleConfig?.sections?.any { section -> 
                section.id == "optional" && section.tasks?.any { task -> task.launch == it.taskId } == true 
            } == true }
            val bonusTasks = taskDetails.filter { !it.isRequired && visibleConfig?.sections?.any { section -> 
                section.id == "bonus" && section.tasks?.any { task -> task.launch == it.taskId } == true 
            } == true }
            
            if (completedRequiredTasks.isNotEmpty()) {
                appendLine("Completed Required Tasks")
                appendLine("Task,Duration,Correct,Incorrect")
                completedRequiredTasks.forEach { task ->
                    val correctInfo = if (task.isVideoTask || task.isChromePageTask) "" else ",${task.correctAnswers},${task.incorrectAnswers}"
                    appendLine("${task.taskName},${task.timeSpentFormatted}$correctInfo")
                }
                appendLine()
            }
            
            if (incompleteRequiredTasks.isNotEmpty()) {
                appendLine("Incomplete Required Tasks")
                appendLine("Task,Duration,Correct,Incorrect")
                incompleteRequiredTasks.forEach { task ->
                    val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                    val correctInfo = if (task.isVideoTask || task.isChromePageTask) "" else ",${task.correctAnswers},${task.incorrectAnswers}"
                    appendLine("${task.taskName},$timeInfo$correctInfo")
                }
                appendLine()
            }
            
            // Checklist Items (separate section for CSV)
            if (checklistItems.isNotEmpty()) {
                appendLine("Checklist Items")
                appendLine("Item,Status,Stars")
                checklistItems.forEach { item ->
                    val (label, isCompleted, stars) = item
                    val status = if (isCompleted) "Complete" else "Incomplete"
                    appendLine("$label,$status,$stars")
                }
                appendLine()
            }
            
            if (optionalTasks.isNotEmpty()) {
                appendLine("Extra Practice Tasks")
                appendLine("Task,Status,Duration,Correct,Incorrect")
                optionalTasks.forEach { task ->
                    val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                    val status = if (task.isCompleted) "Completed" else "Incomplete"
                    val correctInfo = if (task.isVideoTask || task.isChromePageTask) "" else ",${task.correctAnswers},${task.incorrectAnswers}"
                    appendLine("${task.taskName},$status,$timeInfo$correctInfo")
                }
                appendLine()
            }
            
            if (bonusTasks.isNotEmpty()) {
                appendLine("Bonus Tasks")
                appendLine("Task,Status,Duration,Correct,Incorrect")
                bonusTasks.forEach { task ->
                    val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                    val status = if (task.isCompleted) "Completed" else "Incomplete"
                    val correctInfo = if (task.isVideoTask || task.isChromePageTask) "" else ",${task.correctAnswers},${task.incorrectAnswers}"
                    appendLine("${task.taskName},$status,$timeInfo$correctInfo")
                }
            }
        }
    }

    /**
     * Generates an email-ready report
     */
    private fun generateEmailReport(
        report: DailyProgressManager.ComprehensiveProgressReport,
        childName: String,
        rewardMinutesUsed: Int = 0
    ): String {
        val taskDetails = getTaskDetails(report)
        val requiredIncompleteCount = taskDetails.count { it.isRequired && !it.isCompleted }
        val completedTasks = taskDetails.filter { it.isCompleted }
        val incompleteTasks = taskDetails.filter { !it.isCompleted }
        
        return """
            📊 DAILY PROGRESS REPORT - ${report.date}

            Dear Parent/Guardian,

            Here's ${childName}'s learning progress report for today:
            ${if (rewardMinutesUsed > 0) "\n            🎮 Reward Time Earned: $rewardMinutesUsed minutes\n" else ""}

            🎯 OVERALL PROGRESS:
            • Completion Rate: ${"%.1f".format(report.completionRate)}%
            • Stars Earned: ${report.earnedStars}/${report.totalStars}
            • Coins (total): ${report.earnedCoins}
            • Total Learning Time: ${report.totalTimeMinutes} minutes
            ${if (report.choresCompletedToday.isNotEmpty()) "\n            • Chores 4 $$ Today: ${report.choresCompletedToday.size} completed, ${report.coinsEarnedToday} coins earned" else ""}
            • Games Played: ${report.gamesPlayed}
            • Videos Watched: ${report.videosWatched}
            • Questions Answered:   ${report.totalCorrectAnswers}✅   ${report.totalIncorrectAnswers}❌
            ${if (report.webGameSessions.isNotEmpty()) "• Web Games Played: ${report.webGameSessions.size}" else ""}${if (report.chromePageSessions.isNotEmpty()) "\n            • Web Pages Visited: ${report.chromePageSessions.size}" else ""}${if (requiredIncompleteCount > 0) "\n            • Required Tasks Incomplete: $requiredIncompleteCount" else ""}

            ${run {
                val completedRequiredTasks = taskDetails.filter { it.isRequired && it.isCompleted }
                val incompleteRequiredTasks = taskDetails.filter { it.isRequired && !it.isCompleted }
                
                // Get checklist items
                val progressManager = DailyProgressManager(context)
                val visibleConfig = report.config?.let { progressManager.filterVisibleContent(it) }
                val checklistItems = mutableListOf<Triple<String, Boolean, Int>>() // itemName, isCompleted, stars
                visibleConfig?.sections?.forEach { section ->
                    section.items?.forEach { item ->
                        val itemId = item.id ?: "checkbox_${item.label}"
                        val isCompleted = report.completedTasks.contains(itemId)
                        val stars = item.stars ?: 0
                        checklistItems.add(Triple(item.label ?: itemId, isCompleted, stars))
                    }
                }
                
                // Separate optional and bonus tasks
                val optionalTasks = taskDetails.filter { !it.isRequired && visibleConfig?.sections?.any { section -> 
                    section.id == "optional" && section.tasks?.any { task -> task.launch == it.taskId } == true 
                } == true }
                val bonusTasks = taskDetails.filter { !it.isRequired && visibleConfig?.sections?.any { section -> 
                    section.id == "bonus" && section.tasks?.any { task -> task.launch == it.taskId } == true 
                } == true }
                
                buildString {
                    if (completedRequiredTasks.isNotEmpty()) {
                        append("✅ COMPLETED REQUIRED TASKS:\n            ")
                        append(completedRequiredTasks.joinToString("\n            ") { 
                            "• ${it.taskName}: ${it.timeSpentFormatted}${it.answerInfo}" 
                        })
                        append("\n\n            ")
                    }
                    
                    if (incompleteRequiredTasks.isNotEmpty()) {
                        append("⏳ INCOMPLETE REQUIRED TASKS:\n            ")
                        append(incompleteRequiredTasks.joinToString("\n            ") { task ->
                            val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                            "• ${task.taskName}: $timeInfo${task.answerInfo}"
                        })
                        append("\n\n            ")
                    }
                    
                    // Checklist Items (separate section for email)
                    if (checklistItems.isNotEmpty()) {
                        append("📋 CHECKLIST ITEMS:\n            ")
                        checklistItems.forEach { item ->
                            val (label, isCompleted, stars) = item
                            val status = if (isCompleted) "✓" else "○"
                            val starsText = if (stars > 0) " (${stars}⭐)" else ""
                            append("\n            • $status $label$starsText")
                        }
                        append("\n\n            ")
                    }
                    
                    if (optionalTasks.isNotEmpty()) {
                        append("🎯 EXTRA PRACTICE TASKS:\n            ")
                        append(optionalTasks.joinToString("\n            ") { task ->
                            val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                            "• ${task.taskName}: $timeInfo${task.answerInfo}"
                        })
                        append("\n\n            ")
                    }
                    
                    if (bonusTasks.isNotEmpty()) {
                        append("🎮 BONUS TASKS:\n            ")
                        append(bonusTasks.joinToString("\n            ") { task ->
                            val timeInfo = if (task.timeSpentSeconds > 0) task.timeSpentFormatted else "0s"
                            "• ${task.taskName}: $timeInfo${task.answerInfo}"
                        })
                        append("\n\n            ")
                    }
                }
            }}

            🏆 ACHIEVEMENTS:
            ${when {
                report.completionRate >= 80 -> "🌟 Excellent progress! Keep up the great work!"
                report.completionRate >= 60 -> "👍 Good progress! Almost there!"
                report.completionRate >= 40 -> "📈 Making progress! Try completing a few more tasks."
                else -> "💪 Every bit of progress counts! Keep trying!"
            }}

            ${if (report.totalTimeMinutes < 30) "💡 Consider encouraging more learning time for better outcomes." else ""}
            ${if (report.totalTimeMinutes > 120) "🎯 Great dedication to learning! Make sure to take breaks." else ""}

            This report was automatically generated by the learning app.

            Best regards,
            Learning Progress Tracker
        """.trimIndent()
    }

    /**
     * Saves a report to a file and returns the file URI for sharing
     */
    fun saveReportToFile(
        report: String,
        format: ReportFormat,
        childName: String = "Child"
    ): Uri? {
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val fileName = "progress_report_${childName.replace(" ", "_")}_$date.${getFileExtension(format)}"

            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            FileWriter(file).use { it.write(report) }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            android.util.Log.e("ReportGenerator", "Error saving report to file", e)
            null
        }
    }

    /**
     * Shares a report via Android's share sheet
     */
    fun shareReport(
        report: String,
        format: ReportFormat,
        childName: String = "Child"
    ) {
        val uri = saveReportToFile(report, format, childName)
        if (uri != null) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = getMimeType(format)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Daily Progress Report - $childName")
                putExtra(Intent.EXTRA_TEXT, generateEmailReport(
                    DailyProgressManager(context).getComprehensiveProgressReport(MainContent(), TimeTracker(context)),
                    childName
                ))
            }

            val chooser = Intent.createChooser(shareIntent, "Share Progress Report")
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
        }
    }

    /**
     * Gets file extension for the format
     */
    private fun getFileExtension(format: ReportFormat): String {
        return when (format) {
            ReportFormat.TEXT -> "txt"
            ReportFormat.HTML -> "html"
            ReportFormat.CSV -> "csv"
            ReportFormat.EMAIL -> "txt"
        }
    }

    /**
     * Gets MIME type for the format
     */
    private fun getMimeType(format: ReportFormat): String {
        return when (format) {
            ReportFormat.TEXT -> "text/plain"
            ReportFormat.HTML -> "text/html"
            ReportFormat.CSV -> "text/csv"
            ReportFormat.EMAIL -> "text/plain"
        }
    }
}
