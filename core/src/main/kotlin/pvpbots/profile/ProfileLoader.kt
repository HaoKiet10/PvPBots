package pvpbots.profile

import pvpbots.config.ConfigReader

object ProfileLoader {
    data class Result(val profiles: Map<Difficulty, DifficultyProfile>, val warnings: List<String>)

    private val DEFAULTS: Map<Difficulty, DifficultyProfile> = mapOf(
        Difficulty.EASY to DifficultyProfile(8, 12.0, 15.0, 0.6, 1.0, CritMode.NEVER, false, StrafeMode.NONE, ShieldMode.NONE, 0.0, false, "starter"),
        Difficulty.MEDIUM to DifficultyProfile(5, 7.0, 25.0, 0.85, 0.85, CritMode.SOMETIMES, false, StrafeMode.SIMPLE, ShieldMode.BASIC, 0.30, false, "iron"),
        Difficulty.HARD to DifficultyProfile(3, 3.0, 40.0, 0.95, 0.95, CritMode.OFTEN, true, StrafeMode.FULL, ShieldMode.FULL, 0.40, true, "diamond"),
        Difficulty.PRO to DifficultyProfile(2, 1.0, 60.0, 1.0, 1.0, CritMode.ALWAYS, true, StrafeMode.RANDOMISED, ShieldMode.FULL_AXE, 0.40, true, "netherite"),
    )

    fun defaults(): Map<Difficulty, DifficultyProfile> = DEFAULTS

    /** [difficulties] is the parsed `difficulties:` section; warnings are prefixed `difficulties.<name>.<key>`. */
    fun load(difficulties: Map<String, Any?>): Result {
        val warnings = mutableListOf<String>()
        val root = ConfigReader(difficulties, "difficulties", warnings)
        val out = Difficulty.entries.associateWith { d ->
            val def = DEFAULTS.getValue(d)
            val r = root.section(d.name.lowercase()) ?: return@associateWith def
            var cMin = r.nonNegDouble("cooldown-min", def.cooldownMin)
            var cMax = r.nonNegDouble("cooldown-max", def.cooldownMax)
            if (cMin > cMax) {
                r.warn("cooldown-min", "must be <= cooldown-max, using defaults ${def.cooldownMin}..${def.cooldownMax}")
                cMin = def.cooldownMin
                cMax = def.cooldownMax
            }
            var healAt = def.healAt
            r.double("heal-at")?.let {
                if (it in 0.0..1.0) healAt = it else r.warn("heal-at", "must be between 0 and 1, using default ${def.healAt}")
            }
            DifficultyProfile(
                reactionTicks = r.int("reaction-ticks", def.reactionTicks),
                aimErrorDeg = r.nonNegDouble("aim-error", def.aimErrorDeg),
                turnSpeedDeg = r.nonNegDouble("turn-speed", def.turnSpeedDeg),
                cooldownMin = cMin,
                cooldownMax = cMax,
                crits = r.enum("crits", CritMode.entries.toTypedArray(), def.crits),
                wTap = r.boolean("w-tap", def.wTap),
                strafe = r.enum("strafe", StrafeMode.entries.toTypedArray(), def.strafe),
                shield = r.enum("shield", ShieldMode.entries.toTypedArray(), def.shield),
                healAt = healAt,
                totem = r.boolean("totem", def.totem),
                kit = r.string("kit", def.kit),
            )
        }
        return Result(out, warnings)
    }
}
