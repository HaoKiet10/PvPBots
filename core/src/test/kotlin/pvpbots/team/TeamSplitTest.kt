package pvpbots.team

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamSplitTest {
    private fun p(n: Int) = UUID.fromString("00000000-0000-0000-0000-0000000000%02x".format(n))
    private val p1 = p(1); private val p2 = p(2); private val p3 = p(3); private val p4 = p(4)

    @Test fun balanced_byRatingTotals() {
        val s = TeamSplit.split(linkedMapOf(p1 to 90.0, p2 to 70.0, p3 to 60.0, p4 to 40.0), 5, emptyList())
        assertEquals(listOf(p1, p4), s.a)
        assertEquals(listOf(p2, p3), s.b)
        assertEquals(emptyList(), s.waiting)
    }

    @Test fun sizesDifferByAtMostOne() {
        val s = TeamSplit.split(linkedMapOf(p1 to 100.0, p2 to 10.0, p3 to 10.0), 3, emptyList())
        assertEquals(setOf(2, 1), setOf(s.a.size, s.b.size))
        val z = TeamSplit.split(linkedMapOf(p1 to 100.0, p2 to 1.0, p3 to 1.0, p4 to 1.0), 3, emptyList())
        assertEquals(2, z.a.size)
        assertEquals(2, z.b.size)
    }

    @Test fun tieGoesToA() {
        val s = TeamSplit.split(linkedMapOf(p1 to 50.0, p2 to 50.0), 3, emptyList())
        assertEquals(listOf(p1), s.a)
        assertEquals(listOf(p2), s.b)
    }

    @Test fun botRating_isTeamAverage() {
        val s = TeamSplit.split(linkedMapOf(p1 to 80.0, p2 to 60.0, p3 to 40.0), 3, emptyList())
        assertEquals(listOf(p1), s.a)
        assertEquals(listOf(p2, p3), s.b)
        assertEquals(80.0, s.botRating[Side.A])
        assertEquals(50.0, s.botRating[Side.B])
    }

    @Test fun overflow_waits_waitingFirstSeatedFirst() {
        val ps = (1..8).map { p(it) }
        val ratings = LinkedHashMap<UUID, Double>().also { m -> ps.forEach { m[it] = 50.0 } }
        val first = TeamSplit.split(ratings, 3, emptyList())
        assertEquals(6, first.a.size + first.b.size)
        assertEquals(listOf(ps[6], ps[7]), first.waiting)
        val next = TeamSplit.split(ratings, 3, first.waiting)
        assertTrue(ps[6] in next.a + next.b && ps[7] in next.a + next.b)
        assertEquals(listOf(ps[4], ps[5]), next.waiting)
    }
}
