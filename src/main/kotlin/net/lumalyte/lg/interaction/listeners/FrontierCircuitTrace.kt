package net.lumalyte.lg.interaction.listeners

/** Returns no attribution if the circuit cannot be fully traced within the work limit. */
internal object FrontierCircuitTrace {
    fun <T> find(start: T, limit: Int, next: (T) -> Iterable<T>, target: (T) -> Boolean): Set<T>? {
        val seen = mutableSetOf(start)
        val queue = ArrayDeque<T>(); queue.add(start)
        val result = mutableSetOf<T>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (target(node)) { result.add(node); continue }
            for (neighbor in next(node)) if (seen.add(neighbor)) {
                if (seen.size > limit) return null
                queue.add(neighbor)
            }
        }
        return result
    }
}
