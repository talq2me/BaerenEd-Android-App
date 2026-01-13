package com.talq2me.baerened

import kotlinx.coroutines.flow.Flow
import kotlin.Result

/**
 * Interface for cloud storage management
 * This allows for easier testing and dependency injection
 */
interface ICloudStorageManager {
    fun isConfigured(): Boolean
    fun isCloudStorageEnabled(): Boolean
    fun setCloudStorageEnabled(enabled: Boolean)
    fun getCurrentProfile(): String?
    fun setCurrentProfile(profile: String)
    
    suspend fun uploadToCloud(profile: String): Result<Unit>
    suspend fun downloadFromCloud(profile: String): Result<Unit>
    suspend fun syncIfEnabled(profile: String): Result<Unit>
    suspend fun saveIfEnabled(profile: String): Result<Unit>
    suspend fun resetProgressInCloud(profile: String): Result<Unit>
    
    fun getLastSyncTimestamp(): Long
}
