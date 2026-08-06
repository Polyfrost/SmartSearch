package org.polyfrost.smartsearch.config

/**
 * Class keeping track of stale config entries, as 2 arrays since OneConfig doesn't serialize maps properly
 */
class StaleEntries {
    var ids: Array<String> = emptyArray()
    var launchesLeft: IntArray = IntArray(0)

    val size: Int
        @Synchronized get() {
            trim()
            return ids.size
        }

    @Synchronized
    fun isEmpty(): Boolean {
        trim()
        return ids.isEmpty()
    }

    fun isNotEmpty(): Boolean = !isEmpty()

    @Synchronized
    fun clear() {
        ids = emptyArray()
        launchesLeft = IntArray(0)
    }

    /**
     * Tracks exactly the stale set, forgetting everything else (assuming it is now seen)
     *
     * @return Whether something was updated
     */
    @Synchronized
    fun track(stale: Set<String>, launches: Int): Boolean {
        trim()
        val newIds = stale.toTypedArray()
        val newLaunchesLeft = IntArray(newIds.size) { i ->
            val old = ids.indexOf(newIds[i])
            if (old == -1) launches else launchesLeft[old]
        }
        if (newIds.contentEquals(ids) && newLaunchesLeft.contentEquals(launchesLeft)) return false
        ids = newIds
        launchesLeft = newLaunchesLeft
        return true
    }

    /** Count down one launch for every tracked entry, returning and forgetting the ones that ran out. */
    @Synchronized
    fun countDown(): Set<String> {
        trim()
        val expired = LinkedHashSet<String>()
        val keptIds = ArrayList<String>(ids.size)
        val keptLaunchesLeft = ArrayList<Int>(ids.size)
        for (i in ids.indices) {
            val left = launchesLeft[i] - 1
            if (left <= 0) {
                expired.add(ids[i])
            } else {
                keptIds.add(ids[i])
                keptLaunchesLeft.add(left)
            }
        }
        ids = keptIds.toTypedArray()
        launchesLeft = keptLaunchesLeft.toIntArray()
        return expired
    }

    private fun trim() {
        if (ids.size == launchesLeft.size) return
        val size = minOf(ids.size, launchesLeft.size)
        ids = ids.copyOfRange(0, size)
        launchesLeft = launchesLeft.copyOfRange(0, size)
    }
}
