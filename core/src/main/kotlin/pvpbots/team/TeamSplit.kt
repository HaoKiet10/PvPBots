package pvpbots.team

import java.util.UUID

/** Who fights on which side, who waits, and the rating filler bots use on each side. */
data class Split(
    val a: List<UUID>,
    val b: List<UUID>,
    val waiting: List<UUID>,
    val botRating: Map<Side, Double>,
)

/** Rating-balanced split of players into two teams. Pure. */
object TeamSplit {
    /**
     * Seats at most `2 * seatsPerSide` players: [waitingFirst] first (in that order), then the rest in [ratings]
     * iteration order; the others go to `waiting` (order kept). Seated players, highest rating first, each go to
     * the side with the lower rating total (ties: A), but a side with fewer members always takes the next one,
     * so sizes differ by at most one. A side with no players gets the overall average as its bot rating.
     */
    fun split(ratings: Map<UUID, Double>, seatsPerSide: Int, waitingFirst: List<UUID>): Split {
        val capacity = 2 * seatsPerSide
        val priority = waitingFirst.filter { it in ratings }.distinct()
        val ordered = priority + ratings.keys.filter { it !in priority }
        val seated = ordered.take(capacity)
        val waiting = ordered.drop(capacity)

        val a = ArrayList<UUID>()
        val b = ArrayList<UUID>()
        var totalA = 0.0
        var totalB = 0.0
        for (p in seated.sortedByDescending { ratings.getValue(it) }) {
            val toA = when {
                a.size != b.size -> a.size < b.size
                else -> totalA <= totalB
            }
            if (toA) { a += p; totalA += ratings.getValue(p) } else { b += p; totalB += ratings.getValue(p) }
        }
        val overall = if (seated.isEmpty()) 0.0 else seated.sumOf { ratings.getValue(it) } / seated.size
        fun avg(team: List<UUID>, total: Double) = if (team.isEmpty()) overall else total / team.size
        return Split(a, b, waiting, mapOf(Side.A to avg(a, totalA), Side.B to avg(b, totalB)))
    }
}
