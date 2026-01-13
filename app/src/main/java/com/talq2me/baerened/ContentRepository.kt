package com.talq2me.baerened

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository interface for content loading
 * Abstracts content fetching from caching
 */
interface ContentRepository {
    suspend fun getMainContent(): Result<MainContent>
    suspend fun getGameContent(gameId: String): Result<String>
    suspend fun getVideoContent(videoFile: String): Result<String>
    
    fun getCachedMainContent(): String?
    fun clearCache()
    fun isContentStale(maxAgeHours: Long = 24): Boolean
}

/**
 * GitHub-based content repository implementation
 * Uses ContentUpdateService under the hood
 */
class GitHubContentRepository(
    private val context: Context,
    private val contentUpdateService: ContentUpdateService = ContentUpdateService()
) : ContentRepository {
    
    override suspend fun getMainContent(): Result<MainContent> = withContext(Dispatchers.IO) {
        try {
            val content = contentUpdateService.fetchMainContent(context)
            if (content != null) {
                Result.success(content)
            } else {
                Result.failure(Exception("Failed to fetch main content"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getGameContent(gameId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val content = contentUpdateService.fetchGameContent(context, gameId)
            if (content != null) {
                Result.success(content)
            } else {
                Result.failure(Exception("Failed to fetch game content for $gameId"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getVideoContent(videoFile: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val content = contentUpdateService.fetchVideoContent(context, videoFile)
            if (content != null) {
                Result.success(content)
            } else {
                Result.failure(Exception("Failed to fetch video content for $videoFile"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override fun getCachedMainContent(): String? {
        return contentUpdateService.getCachedMainContent(context)
    }
    
    override fun clearCache() {
        contentUpdateService.clearCache(context)
    }
    
    override fun isContentStale(maxAgeHours: Long): Boolean {
        return contentUpdateService.isContentStale(context, maxAgeHours)
    }
}
