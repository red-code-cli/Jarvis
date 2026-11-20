package com.jarvis.harness.gateway

/**
 * Pure helpers for the user-editable provider priority / fallback order.
 *
 * Stored as a comma-separated list of provider ids. These functions are side-effect free and
 * unit-tested so reordering, de-duplication and "append newly available providers" can never
 * silently corrupt the saved order.
 */
object GatewayPriorityOrder {

    const val SEPARATOR = ","

    fun encode(order: List<String>): String = normalize(order, emptySet()).joinToString(SEPARATOR)

    fun decode(raw: String?): List<String> = normalize(
        raw.orEmpty().split(SEPARATOR).map(String::trim).filter(String::isNotBlank),
        emptySet(),
    )

    /** Keeps first-seen order, drops blanks and duplicates. */
    fun normalize(order: List<String>, known: Set<String>): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        order.forEach { raw ->
            val id = raw.trim()
            if (id.isBlank()) return@forEach
            if (known.isNotEmpty() && id !in known) return@forEach
            if (seen.add(id)) result += id
        }
        return result
    }

    /** Moves the item at [from] to [to], clamped to valid indices. Returns the reordered list. */
    fun move(order: List<String>, from: Int, to: Int): List<String> {
        if (from !in order.indices) return order
        val target = to.coerceIn(0, order.lastIndex)
        if (target == from) return order
        val mutable = order.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(target, item)
        return mutable
    }

    /** Existing order first (only known ids), then any known ids not yet present, in [available] order. */
    fun reconcile(saved: List<String>, available: List<String>): List<String> {
        val availableSet = available.toSet()
        val result = mutableListOf<String>()
        saved.forEach { id -> if (id in availableSet && id !in result) result += id }
        available.forEach { id -> if (id !in result) result += id }
        return result
    }
}
