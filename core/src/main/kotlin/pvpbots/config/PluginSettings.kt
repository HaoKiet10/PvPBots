package pvpbots.config

import pvpbots.arena.Part
import pvpbots.session.Timings
import pvpbots.skill.SkillSettings

data class PluginSettings(
    val maxBots: Int,
    val timings: Timings,
    val botViewDistance: Int,
    val stuckRetrySeconds: Int,
    val stuckTeleportSeconds: Int,
    /** At most this many bots are spawned per server tick (a spawn costs several ms); the rest wait their turn. */
    val spawnsPerTick: Int,
    val skill: SkillSettings = SkillSettings(),
    val arenas: ArenaSettings = ArenaSettings(),
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
                skill = SkillSettings.load(r.section("skill") ?: empty),
                arenas = ArenaSettings.load(r.section("arenas") ?: empty),
            )
            return Loaded(settings, w)
        }
    }
}

/** `arenas:` — build speed and the material (Bukkit Material name) of each arena part; air is always air. */
data class ArenaSettings(
    val blocksPerTick: Int = 4000,
    val materials: Map<Part, String> = DEFAULT_MATERIALS,
) {
    companion object {
        val DEFAULT_MATERIALS: Map<Part, String> = linkedMapOf(
            Part.FLOOR to "SMOOTH_STONE",
            Part.WALL to "STONE_BRICKS",
            Part.LIGHT to "SEA_LANTERN",
            Part.PAD_A to "BLUE_CONCRETE",
            Part.PAD_B to "RED_CONCRETE",
            Part.COVER to "STONE_BRICKS",
        )

        /** Config key of each part under `arenas.materials`. */
        fun key(part: Part): String = part.name.lowercase().replace('_', '-')

        internal fun load(r: ConfigReader): ArenaSettings {
            val m = r.section("materials")
            return ArenaSettings(
                blocksPerTick = r.int("blocks-per-tick", 4000, min = 1),
                materials = DEFAULT_MATERIALS.mapValues { (part, def) ->
                    m?.string(key(part), def)?.trim()?.uppercase() ?: def
                },
            )
        }
    }
}
