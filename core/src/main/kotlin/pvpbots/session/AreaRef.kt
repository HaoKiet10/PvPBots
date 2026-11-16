package pvpbots.session

import pvpbots.math.Bounds
import pvpbots.profile.Difficulty

data class AreaRef(
    val world: String,
    val regionId: String,
    val role: Role,
    val difficulty: Difficulty,
    val count: Int,
    val bounds: Bounds,
)
