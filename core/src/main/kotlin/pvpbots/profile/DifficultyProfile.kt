package pvpbots.profile

/** healAt: health fraction (0..1) below which the bot eats; 0 = never. */
data class DifficultyProfile(
    val reactionTicks: Int,
    val aimErrorDeg: Double,
    val turnSpeedDeg: Double,
    val cooldownMin: Double,
    val cooldownMax: Double,
    val crits: CritMode,
    val wTap: Boolean,
    val strafe: StrafeMode,
    val shield: ShieldMode,
    val healAt: Double,
    val totem: Boolean,
    val kit: String,
)
