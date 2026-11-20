package pvpbots.arena

/**
 * Arena size presets: pads per team and inside edge length (blocks, odd so the origin is the centre). [round] presets
 * (the boss arena) are a disc of radius `inside / 2` inside a square footprint of `inside + 2`.
 */
enum class Preset(val padsPerSide: Int, val inside: Int, val round: Boolean = false) {
    DUEL(1, 17), SQUAD(3, 25), TEAM(5, 33), BOSS(1, 41, round = true);

    companion object {
        /** The presets `/pvpbots arena create` offers (boss arenas have their own command). */
        val TEAM_PRESETS: List<Preset> get() = entries.filter { !it.round }
    }
}
