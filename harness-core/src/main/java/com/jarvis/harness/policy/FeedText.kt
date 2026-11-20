package com.jarvis.harness.policy

/**
 * Builds the one-line Agent Live feed text.
 *
 * Two bugs this fixes (both were visible on the card):
 *  * duplicate text - a title like "Wrote index.html" plus its detail "index.html" produced
 *    "Wrote index.htmlindex.html";
 *  * mid-token truncation - a raw `take(n)` cut "...section class=..." in half.
 *
 * Pure and engine-agnostic so both the supervisor and the UI can use it.
 */
object FeedText {

    /** First line, whitespace-collapsed, cut on a word boundary with an ellipsis. */
    fun truncate(text: String, max: Int = AgentPolicyConfig.FEED_DETAIL_MAX): String {
        val firstLine = text.lineSequence().firstOrNull().orEmpty().ifBlank { text }
        val flat = firstLine.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= max) return flat
        val cut = flat.take(max)
        val boundary = cut.lastIndexOf(' ')
        val head = if (boundary >= AgentPolicyConfig.FEED_BOUNDARY_MIN) cut.substring(0, boundary) else cut
        return head.trimEnd() + "..."
    }

    /**
     * [title] with [detail] appended as a suffix, unless the title already carries it.
     * Never repeats the detail and never cuts mid-word.
     */
    fun compose(
        title: String,
        detail: String = "",
        max: Int = AgentPolicyConfig.FEED_LINE_MAX,
    ): String {
        val cleanTitle = title.trim().ifBlank { detail.trim() }
        val suffix = when {
            detail.isBlank() -> ""
            alreadyPresent(cleanTitle, detail) -> ""
            else -> "  " + truncate(detail)
        }
        return truncate(cleanTitle + suffix, max)
    }

    private fun alreadyPresent(title: String, detail: String): Boolean {
        val head = detail.take(AgentPolicyConfig.FEED_DEDUPE_PREFIX)
        if (head.isNotBlank() && title.contains(head)) return true
        val fileName = detail.substringAfterLast('/').substringBefore(' ').takeIf { it.isNotBlank() }
        if (fileName != null && title.contains(fileName)) return true
        val firstToken = detail.substringBefore(' ').takeIf { it.length > 3 }
        return firstToken != null && title.contains(firstToken)
    }
}
