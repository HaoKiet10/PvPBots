package pvpbots.config

import pvpbots.session.Timings

data class PluginSettings(
    val maxBots: Int,
    val timings: Timings,
    val botViewDistance: Int,
    val stuckRetrySeconds: Int,
    val stuckTeleportSeconds: Int,
    /** At most this many bots are spawned per server tick (a spawn costs several ms); the rest wait their turn. */
    val spawnsPerTick: Int,
) {
    companion object {
        fun load(root: Map<String, Any?>): Loaded<PluginSettings> {
            val w = mutableListOf<String>()
            val r = ConfigReader(root, "", w)
            val empty = ConfigReader(emptyMap<String, Any?>(), "", w)
            val trainer = r.section("trainer") ?: empty
            val ally = r.section("ally") ?: empty
            val bot = r.section("bot") ?: empty
            val settings = PluginSettings(
                maxBots = r.int("max-bots", 100, min = 1),
                timings = Timings(
                    countdownS = trainer.int("countdown-seconds", 3),
                    timeLimitS = trainer.int("time-limit-seconds", 300),
                    rematchDelayS = trainer.int("rematch-delay-seconds", 5),
                    allyRespawnS = ally.int("respawn-seconds", 15),
                    leaveGraceS = ally.int("leave-grace-seconds", 10),
                ),
                botViewDistance = bot.int("view-distance", 2),
                stuckRetrySeconds = bot.int("stuck-retry-seconds", 5),
                stuckTeleportSeconds = bot.int("stuck-teleport-seconds", 10),
                spawnsPerTick = bot.int("spawns-per-tick", 2, min = 1),
            )
            return Loaded(settings, w)
        }
    }
}
