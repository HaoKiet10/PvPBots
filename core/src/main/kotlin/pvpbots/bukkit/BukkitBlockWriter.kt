package pvpbots.bukkit

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.data.BlockData
import pvpbots.arena.BlockWriter
import pvpbots.math.BlockPos

/** [BlockWriter] over one Bukkit world. Writes skip physics, so neighbours are not updated while an arena is built. */
class BukkitBlockWriter(private val world: World) : BlockWriter {
    private val parsed = HashMap<String, BlockData>()

    override fun read(p: BlockPos): String = world.getBlockAt(p.x, p.y, p.z).blockData.asString

    /** Throws [IllegalArgumentException] for block data this server does not know. */
    override fun write(p: BlockPos, blockData: String) {
        val data = parsed.getOrPut(blockData) { Bukkit.createBlockData(blockData) }
        world.getBlockAt(p.x, p.y, p.z).setBlockData(data, false)
    }
}
