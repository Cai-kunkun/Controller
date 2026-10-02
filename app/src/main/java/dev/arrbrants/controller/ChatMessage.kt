package dev.arrbrants.controller

data class ChatMessage(
    val role: String,
    val content: String,
    /** Optional screenshot stored under filesDir (e.g. "shots/1_1699999999.jpg"). */
    val imagePath: String? = null
)
