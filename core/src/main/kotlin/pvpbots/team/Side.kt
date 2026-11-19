package pvpbots.team

import java.util.UUID

enum class Side { A, B }

/** One seat of a team arena: a real player, or `null` for a filler bot. */
data class Seat(val player: UUID?, val side: Side)
