package dev.arrbrants.controller

import java.util.Locale

/**
 * Rough prompt-token accounting used to keep the request under the model's
 * context window. It never needs to be exact: real usage is read back from the
 * provider after every reply and used as the authoritative floor.
 */
object TokenBudget {

    /**
     * CJK characters are usually one token each, everything else averages
     * roughly four characters per token.
     */
    fun text(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        for (ch in text) {
            if (ch.code >= 0x2E80) wide++
        }
        val narrow = text.length - wide
        return wide + (narrow + 3) / 4 + 4
    }

    /** Patch-based estimate (28px patches), close to vision-model tokenizers. */
    fun image(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        return ((width + 27) / 28) * ((height + 27) / 28)
    }

    /** Short human-readable token count: 987 / 12.5k / 128k / 1.2M. */
    fun format(tokens: Long): String {
        val abs = tokens.coerceAtLeast(0)
        return when {
            abs >= 1_000_000 -> String.format(Locale.US, "%.1fM", abs / 1_000_000.0)
            abs >= 100_000 -> String.format(Locale.US, "%.0fk", abs / 1_000.0)
            abs >= 1_000 -> String.format(Locale.US, "%.1fk", abs / 1_000.0)
            else -> abs.toString()
        }
    }
}
