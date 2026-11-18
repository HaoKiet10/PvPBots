package pvpbots.arena

import pvpbots.math.BlockPos

/** Reads and writes world blocks as block-data strings (e.g. `minecraft:oak_stairs[facing=east]`). */
interface BlockWriter {
    fun read(p: BlockPos): String
    fun write(p: BlockPos, blockData: String)
}
