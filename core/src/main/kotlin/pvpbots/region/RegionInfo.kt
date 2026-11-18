package pvpbots.region

import pvpbots.math.Bounds
import pvpbots.profile.Difficulty
import pvpbots.session.Role

/** A WorldGuard region reduced to what the plugin needs. Null [role]/[difficulty]/[count]/[arena]/[kit] mean the flag is unset. */
data class RegionInfo(
    val world: String,
    val id: String,
    val priority: Int,
    val role: Role?,
    val difficulty: Difficulty?,
    val count: Int?,
    val bounds: Bounds,
    val arena: String? = null,
    val kit: String? = null,
)
