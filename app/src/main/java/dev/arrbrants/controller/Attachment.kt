package dev.arrbrants.controller

/** A file the user attached to a chat message. */
data class Attachment(
    /** Path relative to filesDir, e.g. "attachments/12_1699_photo.jpg". */
    val path: String,
    val name: String,
    val mime: String,
    val size: Long
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}
