package pvpbots.path

/** Read-only view of solid blocks. "Standable" at p = solid(below) && !solid(p) && !solid(p.up). */
fun interface BlockGrid {
    fun solid(x: Int, y: Int, z: Int): Boolean
}

internal fun BlockGrid.standable(x: Int, y: Int, z: Int) =
    solid(x, y - 1, z) && !solid(x, y, z) && !solid(x, y + 1, z)
