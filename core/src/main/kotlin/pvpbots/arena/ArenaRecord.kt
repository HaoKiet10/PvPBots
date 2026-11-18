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
) {
    enum class Status { BUILDING, READY, REMOVING }
}
