package com.talq2me.baerened

import com.google.gson.annotations.SerializedName

/**
 * Root model for book JSON in assets/books/ (e.g. .json files).
 */
data class BookJson(
    val id: String? = null,
    val title: String? = null,
    val level: String? = null,
    val theme: String? = null,
    val language: String? = null,
    val pages: List<BookPage> = emptyList(),
    val questions: List<BookQuestion> = emptyList()
)

data class BookPage(
    @SerializedName("page_number") val pageNumber: Int = 0,
    val text: List<String> = emptyList(),
    val image: BookPageImage? = null
)

data class BookPageImage(
    @SerializedName("image_id") val imageId: String = "",
    val description: String? = null
)

data class BookQuestion(
    val id: String? = null,
    val type: String? = null,
    val question: String = "",
    val options: List<String> = emptyList(),
    @SerializedName("correct_index") val correctIndex: Int = 0
)
