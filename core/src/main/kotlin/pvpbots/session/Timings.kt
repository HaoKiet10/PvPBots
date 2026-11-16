package pvpbots.session

data class Timings(
    val countdownS: Int,
    val timeLimitS: Int,
    val rematchDelayS: Int,
    val allyRespawnS: Int,
    val leaveGraceS: Int,
)
