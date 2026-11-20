package pvpbots.team

import java.util.UUID

enum class Side { A, B }

/** One seat of a team arena: a real player, or `null` for a filler bot. */
data class Seat(val player: UUID?, val side: Side)

/** Where a player or bot sits in a running team round: the round's [arena] (region id), its [side], and whether the round is [fighting]. */
data class TeamSeat(val arena: String, val side: Side, val fighting: Boolean)
