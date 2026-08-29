package com.talq2me.baerened

import com.google.gson.annotations.SerializedName

data class StoryReadRoot(
    @SerializedName("book_id") val bookId: String? = null,
    val segments: List<StoryReadSegment> = emptyList()
)

data class StoryReadSegment(
    val id: String? = null,
    val page: Int = 0,
    val image: String? = null,
    @SerializedName("french_text") val frenchText: String? = null,
    @SerializedName("english_text") val englishText: String? = null,
    val tts: StoryReadTts? = null
)

data class StoryReadTts(
    val voice: String? = null,
    val speed: Float? = null,
    val pitch: Float? = null
)
