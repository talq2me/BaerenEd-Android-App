package com.talq2me.baerened

import com.google.gson.annotations.SerializedName

/**
 * Root model for tappableText game JSON files in assets/tappableText/.
 */
data class TappableTextRoot(
    val id: String? = null,
    val title: String? = null,
    val language: String? = null,
    val pages: List<TappableTextPage> = emptyList()
)

data class TappableTextPage(
    @SerializedName("page_number") val pageNumber: Int = 0,
    val text: List<String> = emptyList(),
    val image: TappableTextPageImage? = null,
    @SerializedName("tappable_word_questions") val tappableWordQuestions: List<TappableWordQuestion> = emptyList(),
    @SerializedName("comprehension_question") val comprehensionQuestion: TappableTextComprehensionQuestion? = null
)

data class TappableTextPageImage(
    @SerializedName("image_id") val imageId: String = "",
    val description: String? = null
)

data class TappableWordQuestion(
    val id: String? = null,
    val prompt: String = "",
    @SerializedName("correct_word") val correctWord: String = ""
)

data class TappableTextComprehensionQuestion(
    val id: String? = null,
    val prompt: String = "",
    val options: List<String> = emptyList(),
    @SerializedName("correct_index") val correctIndex: Int = 0
)

