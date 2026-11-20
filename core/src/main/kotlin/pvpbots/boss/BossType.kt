package pvpbots.boss

/** Signed Mojang texture property (pure twin of the bukkit skin type). */
data class BossSkin(val value: String, val signature: String)

enum class MoveType { COMBO, LEAP_SLAM, SHOCKWAVE, VOLLEY, CHARGED_SHOT, BACKSTEP, ROAR }

/**
 * One entry of a boss moveset. [range] is the target distance (blocks) where it may be picked; [damage] is raw
 * (armour applies); [params] holds the type-specific numbers (hits, interval-ticks, radius, speed, arrows, spread,
 * bursts, invulnerable-ticks).
 */
data class Move(
    val id: String,
    val type: MoveType,
    val weight: Int,
    val range: ClosedFloatingPointRange<Double>,
    val telegraphTicks: Int,
    val damage: Double,
    val cooldownTicks: Int,
    val recoveryTicks: Int,
    val params: Map<String, Double>,
)

/**
 * A phase starting when health fraction is at or below [at]. Moves named in any phase's [addMoves] are inactive until
 * that phase; see [PhaseTracker.activeMoves].
 */
data class Phase(
    val at: Double,
    val roar: Boolean,
    val moveSpeed: Double,
    val telegraphScale: Double,
    val addMoves: List<String>,
    val removeMoves: List<String>,
)

data class LootEntry(val item: String, val amount: Int, val chance: Double)

data class LootTable(val guaranteed: List<LootEntry>, val rolls: Int, val table: List<LootEntry>)

/** [gear] keys: head, chest, legs, feet, main-hand, off-hand (material names); [enchants] is slot -> enchantment -> level. */
data class BossType(
    val id: String,
    val name: String,
    val skin: BossSkin?,
    val baseHealth: Double,
    val healthPerExtraPlayer: Double,
    val gear: Map<String, String>,
    val enchants: Map<String, Map<String, Int>>,
    val moveSpeed: Double,
    val knockbackResistance: Double,
    val moves: List<Move>,
    val phases: List<Phase>,
    val loot: LootTable,
    val xp: Int,
    val commands: List<String>,
    val cooldownSeconds: Int,
)
