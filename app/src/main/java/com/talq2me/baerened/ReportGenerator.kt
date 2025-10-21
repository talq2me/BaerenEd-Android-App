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
     * Generates a comprehensive daily progress report
     */
    fun generateDailyReport(
        progressReport: DailyProgressManager.ComprehensiveProgressReport,
        childName: String = "Child",
        format: ReportFormat = ReportFormat.TEXT
    ): String {
        return when (format) {
            ReportFormat.TEXT -> generateTextReport(progressReport, childName)
            ReportFormat.HTML -> generateHtmlReport(progressReport, childName)
            ReportFormat.CSV -> generateCsvReport(progressReport, childName)
            ReportFormat.EMAIL -> generateEmailReport(progressReport, childName)
        }
    }

    /**
     * Generates a plain text report
     */
    private fun generateTextReport(
        report: DailyProgressManager.ComprehensiveProgressReport,
        childName: String
    ): String {
        val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
        val formattedDate = dateFormat.format(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(report.date)!!)

        return buildString {
            appendLine("📊 DAILY PROGRESS REPORT")
            appendLine("=".repeat(50))
            appendLine("Child: $childName")
            appendLine("Date: $formattedDate")
            appendLine()

            appendLine("🎯 OVERALL PROGRESS")
            appendLine("-".repeat(30))
            appendLine("Stars Earned: ${report.earnedStars}/${report.totalStars} (${"%.1f".format(report.completionRate)}%)")
            appendLine("Coins Earned: ${report.earnedCoins}/${report.totalCoins}")
            appendLine("Total Time: ${"%.1f".format(report.totalTimeMinutes)} minutes")
            appendLine()

            appendLine("🎮 ACTIVITY SUMMARY")
            appendLine("-".repeat(30))
            appendLine("Games Played: ${report.gamesPlayed}")
            appendLine("Videos Watched: ${report.videosWatched}")
            appendLine("Total Sessions: ${report.totalSessions}")
            appendLine()

            if (report.completedGameSessions.isNotEmpty()) {
                appendLine("🎯 COMPLETED GAMES")
                appendLine("-".repeat(30))
                appendLine("Games completed today:")
                report.completedGameSessions.forEach { session ->
                    val status = if (session.completed) "Completed" else "In Progress"
                    appendLine("  ${session.activityName} ${session.formattedDuration} $status")
                }
                appendLine()
            }

            if (report.gamesPlayed > 0) {
                appendLine("🎮 ALL GAME SESSIONS")
                appendLine("-".repeat(30))
                appendLine("Average Game Time: ${"%.1f".format(report.averageGameTimeMinutes)} minutes")
                if (report.mostPlayedGame != null) {
                    appendLine("Most Played Game: ${report.mostPlayedGame}")
                }
                appendLine()

                appendLine("All Game Sessions:")
                report.gameSessions.forEach { session ->
                    val status = if (session.completed) "Completed" else "In Progress"
                    appendLine("  ${session.activityName} ${session.formattedDuration} $status")
                }
                appendLine()
            }

            if (report.videosWatched > 0) {
                appendLine("📺 VIDEO DETAILS")
                appendLine("-".repeat(30))
                appendLine("Average Video Time: ${"%.1f".format(report.averageVideoTimeMinutes)} minutes")
                appendLine()

                appendLine("Video Sessions:")
                report.videoSessions.forEach { session ->
                    appendLine("  • ${session.activityName}: ${"%.1f".format(session.durationMinutes)} min")
                }
                appendLine()
            }

            appendLine("✅ COMPLETED TASKS")
            appendLine("-".repeat(30))
            if (report.completedTaskNames.isNotEmpty()) {
                report.completedTaskNames.forEach { (taskId, taskName) ->
                    appendLine("  ✓ $taskName")
                }
            } else {
                appendLine("  No tasks completed today")
            }
            appendLine()

            appendLine("🏆 ACHIEVEMENTS")
            appendLine("-".repeat(30))
            if (report.earnedStars == report.totalStars && report.totalStars > 0) {
                appendLine("  🌟 All stars earned today!")
            }
            if (report.totalTimeMinutes > 60) {
                appendLine("  ⏰ Over 1 hour of learning time!")
            }
            if (report.longestSessionMinutes > 30) {
                appendLine("  🎯 Longest session: ${"%.1f".format(report.longestSessionMinutes)} minutes")
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
        childName: String
    ): String {
        val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
        val formattedDate = dateFormat.format(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(report.date)!!)

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
                        <div class="metric-value">${"%.1f".format(report.totalTimeMinutes)}</div>
                        <div class="metric-label">Minutes</div>
                    </div>
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
                        <tr><td>Total Time</td><td>${"%.1f".format(report.totalTimeMinutes)} minutes</td></tr>
                    </table>
                </div>

                ${if (report.completedGameSessions.isNotEmpty()) """
                <div class="section">
                    <h3>🎯 Completed Games</h3>
                    <table>
                        <tr><th>Game</th><th>Duration</th><th>Status</th></tr>
                        ${report.completedGameSessions.joinToString("") { session ->
                            "<tr><td>${session.activityName}</td><td>${session.formattedDuration}</td><td>Completed</td></tr>"
                        }}
                    </table>
                </div>
                """ else ""}

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

                <div class="section">
                    <h3>✅ Completed Tasks</h3>
                    <ul class="task-list">
                        ${if (report.completedTaskNames.isNotEmpty())
                            report.completedTaskNames.values.joinToString("") { "<li class=\"task-item\">$it</li>" }
                        else "<li class=\"task-item\">No tasks completed today</li>"}
                    </ul>
                </div>

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
        childName: String
    ): String {
        return buildString {
            appendLine("Daily Progress Report for $childName")
            appendLine("Date: ${report.date}")
            appendLine()

            appendLine("Summary Metrics")
            appendLine("Metric,Value")
            appendLine("Completion Rate,${"%.1f".format(report.completionRate)}%")
            appendLine("Stars Earned,${report.earnedStars}/${report.totalStars}")
            appendLine("Total Time Minutes,${"%.1f".format(report.totalTimeMinutes)}")
            appendLine("Games Played,${report.gamesPlayed}")
            appendLine("Videos Watched,${report.videosWatched}")
            appendLine("Total Sessions,${report.totalSessions}")
            appendLine()

            if (report.completedGameSessions.isNotEmpty()) {
                appendLine("Completed Game Sessions")
                appendLine("Game,Duration,Status")
                report.completedGameSessions.forEach { session ->
                    appendLine("${session.activityName},${session.formattedDuration},Completed")
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

            appendLine("Completed Tasks")
            appendLine("Task")
            report.completedTaskNames.forEach { (taskId, taskName) ->
                appendLine(taskName)
            }
        }
    }

    /**
     * Generates an email-ready report
     */
    private fun generateEmailReport(
        report: DailyProgressManager.ComprehensiveProgressReport,
        childName: String
    ): String {
        return """
            📊 DAILY PROGRESS REPORT - ${report.date}

            Dear Parent/Guardian,

            Here's ${childName}'s learning progress report for today:

            🎯 OVERALL PROGRESS:
            • Completion Rate: ${"%.1f".format(report.completionRate)}%
            • Stars Earned: ${report.earnedStars}/${report.totalStars}
            • Total Learning Time: ${"%.1f".format(report.totalTimeMinutes)} minutes
            • Games Played: ${report.gamesPlayed}
            • Videos Watched: ${report.videosWatched}

            ✅ COMPLETED TASKS:
            ${if (report.completedTaskNames.isNotEmpty())
                report.completedTaskNames.values.joinToString("\n            ") { "• $it" }
            else "No tasks completed today"}

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
