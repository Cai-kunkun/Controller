package dev.arrbrants.controller

/**
 * Finds a labeled view inside a `uiautomator dump` XML document.
 */
object UiDump {

    private val NODE = Regex("<node\\b[^>]*>")
    private val TEXT = Regex("text=\"([^\"]*)\"")
    private val DESC = Regex("content-desc=\"([^\"]*)\"")
    private val HINT = Regex("hint=\"([^\"]*)\"")
    private val BOUNDS = Regex("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")

    /** Center of the smallest view whose text, hint or content description contains [label]. */
    fun findCenter(xml: String, label: String): Pair<Int, Int>? {
        val needle = label.trim()
        if (needle.isEmpty()) return null
        var bestArea = Long.MAX_VALUE
        var bestCenter: Pair<Int, Int>? = null
        for (match in NODE.findAll(xml)) {
            val node = match.value
            val text = TEXT.find(node)?.groupValues?.get(1).orEmpty()
            val desc = DESC.find(node)?.groupValues?.get(1).orEmpty()
            val hint = HINT.find(node)?.groupValues?.get(1).orEmpty()
            if (!text.contains(needle, true) &&
                !desc.contains(needle, true) &&
                !hint.contains(needle, true)
            ) continue
            val groups = BOUNDS.find(node)?.groupValues ?: continue
            val x1 = groups[1].toIntOrNull() ?: continue
            val y1 = groups[2].toIntOrNull() ?: continue
            val x2 = groups[3].toIntOrNull() ?: continue
            val y2 = groups[4].toIntOrNull() ?: continue
            val area = (x2 - x1).toLong() * (y2 - y1)
            if (area <= 0L) continue
            if (area < bestArea) {
                bestArea = area
                bestCenter = (x1 + x2) / 2 to (y1 + y2) / 2
            }
        }
        return bestCenter
    }
}
