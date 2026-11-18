package music.tune.android

/** A positive value is when the like was added locally. Zero records a removal. */
internal fun applyRestoredLikes(server: Set<String>, pending: Map<String, Long>): Set<String> =
    (server - pending.keys) + pending.filterValues { it > 0 }.keys

internal fun reconcileRestoredLikes(
    pending: Map<String, Long>,
    read: () -> Set<String>,
    write: (id: String, liked: Boolean) -> Unit,
): Pair<Set<String>, Map<String, Long>> {
    val before = read()
    val missing = pending.filter { (id, date) -> (id in before) != (date > 0) }
    for ((id, date) in missing) optional { write(id, date > 0) }
    val confirmed = if (missing.isEmpty()) before else read()
    return confirmed to pending.filter { (id, date) -> (id in confirmed) != (date > 0) }
}
