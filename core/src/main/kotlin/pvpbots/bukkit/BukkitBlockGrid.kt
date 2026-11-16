package pvpbots.bukkit

import org.bukkit.World
import pvpbots.math.BlockPos
import pvpbots.path.BlockGrid

/**
 * [BlockGrid] over a Bukkit world. Never loads chunks: blocks in unloaded chunks and outside the build height
 * read as not solid (so they are never standable floor). Main thread only.
 */
class BukkitBlockGrid(private val world: World) : BlockGrid {
    private val minY = world.minHeight
    private val maxY = world.maxHeight

    override fun solid(x: Int, y: Int, z: Int): Boolean {
        if (y < minY || y >= maxY) return false
        if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
        return world.getType(x, y, z).isSolid
    }

    /** Feet position is standable: solid floor, two free blocks. */
    fun standable(p: BlockPos): Boolean = solid(p.x, p.y - 1, p.z) && !solid(p.x, p.y, p.z) && !solid(p.x, p.y + 1, p.z)
}
