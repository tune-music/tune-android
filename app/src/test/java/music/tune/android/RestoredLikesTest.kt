package music.tune.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class RestoredLikesTest {
    @Test
    fun pendingChangesOverlayServerLikesEvenWhenTheServerListIsEmpty() {
        assertEquals(setOf("new"), applyRestoredLikes(emptySet(), mapOf("new" to 42L, "removed" to 0L)))
        assertEquals(setOf("regular", "new"),
            applyRestoredLikes(setOf("regular", "removed"), mapOf("new" to 42L, "removed" to 0L)))
    }

    @Test
    fun onlyChangesConfirmedByTheFullServerListLeaveTheQueue() {
        val server = mutableSetOf("existing", "removed", "regular")
        val writes = mutableListOf<Pair<String, Boolean>>()
        val pending = linkedMapOf("existing" to 10L, "failed" to 20L, "added" to 30L, "removed" to 0L)
        val (confirmed, remaining) = reconcileRestoredLikes(pending, read = { server.toSet() }) { id, liked ->
            writes.add(id to liked)
            if (id == "failed") throw IOException("Отказ сервера")
            if (liked) server.add(id) else server.remove(id)
        }
        assertEquals(listOf("failed" to true, "added" to true, "removed" to false), writes)
        assertEquals(setOf("existing", "added", "regular"), confirmed)
        assertEquals(mapOf("failed" to 20L), remaining)
    }

    @Test
    fun successfulWriteWithoutReadbackStaysPending() {
        val pending = mapOf("changed" to 29L)
        val (confirmed, remaining) = reconcileRestoredLikes(pending, read = { emptySet() }) { _, _ -> }
        assertEquals(emptySet<String>(), confirmed)
        assertEquals(pending, remaining)
    }
}
