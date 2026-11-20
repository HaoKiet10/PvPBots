package pvpbots.arena

import pvpbots.math.BlockPos

data class ArenaRecord(
    val name: String,
    val world: String,
    val origin: BlockPos,
    val facing: Facing,
    val preset: Preset,
    val regionId: String,
    val status: Status,
    val kit: String?,
    val padsA: List<BlockPos>,
    val padsB: List<BlockPos>,
    /** Boss type id (a key of config `bosses:`) for [Preset.BOSS] arenas; null otherwise. */
    val bossType: String? = null,
) {
    enum class Status { BUILDING, READY, REMOVING }
}
