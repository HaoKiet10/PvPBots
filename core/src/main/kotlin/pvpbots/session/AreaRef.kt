package pvpbots.session

import pvpbots.math.Bounds
import pvpbots.profile.Difficulty

data class AreaRef(
    val world: String,
    val regionId: String,
    val role: Role,
    /** Null = adaptive (trainers; the player's skill rating decides) / medium (allies). */
    val difficulty: Difficulty?,
    val count: Int,
    val bounds: Bounds,
    val arena: String? = null,
    val kit: String? = null,
    /** Seats per side for team arenas (squad = 3, team = 5); null elsewhere. */
    val seats: Int? = null,
)
