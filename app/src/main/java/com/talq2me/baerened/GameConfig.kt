package com.talq2me.baerened

import android.content.Context

// GameConfig.kt
data class GameConfig(
    val launch: String,
    val requiredCorrectAnswers: Int
)

fun loadGameConfig(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") fileName: String): GameConfig {
    throw UnsupportedOperationException("Asset-based config is disabled in DB/GitHub-only mode.")
}
