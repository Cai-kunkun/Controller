package dev.arrbrants.controller

data class ChatMessage(
    val role: String,
    val content: String,
    /** Optional screenshot stored under filesDir (e.g. "shots/1_1699999999.jpg"). */
    val imagePath: String? = null,
    /** Files the user attached to this message. */
    val attachments: List<Attachment> = emptyList(),
    /**
     * Raw provider reply (for actions: the DSML block). Kept out of the UI but
     * fed back to the model so it sees its own native tool-call format.
     */
    val rawContent: String? = null
)
