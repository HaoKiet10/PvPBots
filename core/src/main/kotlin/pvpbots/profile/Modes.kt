package pvpbots.profile

enum class Difficulty { EASY, MEDIUM, HARD, PRO }

enum class CritMode(val chance: Double) { NEVER(0.0), SOMETIMES(0.3), OFTEN(0.7), ALWAYS(1.0) }

enum class StrafeMode { NONE, SIMPLE, FULL, RANDOMISED }

enum class ShieldMode { NONE, BASIC, FULL, FULL_AXE }
