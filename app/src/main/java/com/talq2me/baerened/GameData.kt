package com.talq2me.baerened

// GameData.kt
data class GameData(
    val prompt: Prompt?,
    val question: Question?,
    val correctChoices: List<Choice>,
    val extraChoices: List<Choice>
)

data class Prompt(
    val text: String,
    val lang: String,
    val tts: Boolean = true
)

data class Question(
    val text: String?,
    val lang: String?,
    val media: Media? = null
)

data class Choice(
    val text: String,
    val media: MediaItem? = null
)

data class Media(
    val images: List<String>? = null,
    val audioclips: List<String>? = null
)

data class MediaItem(
    val image: String? = null,
    val audioclip: String? = null
)
