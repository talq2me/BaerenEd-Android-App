package com.talq2me.baerened

import android.content.Context
import com.google.gson.Gson

// GameConfig.kt
data class GameConfig(
    val launch: String,
    val requiredCorrectAnswers: Int
)

fun loadGameConfig(context: Context, fileName: String): GameConfig {
    val json = context.assets.open("config/$fileName").bufferedReader().use { it.readText() }
    return Gson().fromJson(json, GameConfig::class.java)
}
