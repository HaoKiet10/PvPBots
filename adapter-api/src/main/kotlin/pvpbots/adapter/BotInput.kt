package pvpbots.adapter

/** forward/strafe are in [-1, 1]. */
data class BotInput(
    val forward: Float,
    val strafe: Float,
    val jump: Boolean,
    val sprint: Boolean,
    val sneak: Boolean,
    val yaw: Float,
    val pitch: Float,
)
