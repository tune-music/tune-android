package music.tune.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConcertTest {
    @Test
    fun showsEventTimeWithoutConvertingTheVenueTimeZone() {
        assertEquals("5 декабря 2026 · 19:00", formatConcertDate("2026-12-05T19:00:00+03:00"))
        assertEquals("5 декабря 2026", formatConcertDate("2026-12-05"))
        assertNull(formatConcertDate("2026-02-30"))
    }

    @Test
    fun cachesThePlaceDateAndOptionalTicketsSeparately() {
        val card = Card(Kind.CONCERT, "1", "Концерт", "Москва · Театр", eventDate = "2026-12-05T19:00:00+03:00")
        assertEquals(card, Card.fromCache(card.cached()))
        val linked = card.copy(link = "https://widget.afisha.yandex.ru/w/sessions/session_01")
        assertEquals(linked, Card.fromCache(linked.cached()))
    }
}
