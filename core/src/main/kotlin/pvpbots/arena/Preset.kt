package pvpbots.arena

/** Arena size presets: pads per team and inside edge length (blocks, odd so the origin is the centre). */
enum class Preset(val padsPerSide: Int, val inside: Int) { DUEL(1, 17), SQUAD(3, 25), TEAM(5, 33) }
