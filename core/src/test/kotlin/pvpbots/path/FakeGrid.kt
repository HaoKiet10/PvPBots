package pvpbots.path

import pvpbots.math.BlockPos
import pvpbots.math.Bounds

/** Mutable test grid: only explicitly set blocks are solid. */
class FakeGrid : BlockGrid {
    private val blocks = HashSet<BlockPos>()

    fun set(x: Int, y: Int, z: Int): FakeGrid { blocks.add(BlockPos(x, y, z)); return this }

    fun floor(x: IntRange, z: IntRange, y: Int = 0): FakeGrid {
        for (xx in x) for (zz in z) set(xx, y, zz)
        return this
    }

    fun column(x: Int, z: Int, y: IntRange): FakeGrid {
        for (yy in y) set(x, yy, z)
        return this
    }

    override fun solid(x: Int, y: Int, z: Int) = BlockPos(x, y, z) in blocks
}

fun p(x: Int, y: Int, z: Int) = BlockPos(x, y, z)

val all = Bounds(BlockPos(-100000, -100, -100000), BlockPos(100000, 300, 100000))

/** Solid floor at y=0 for x,z in 0 until n; everything else air. */
fun flat(n: Int) = FakeGrid().floor(0 until n, 0 until n)

/** 10x(-5..9) floor with a 2-high wall at x=3 spanning z -2..2. */
fun wallAtX3(): FakeGrid {
    val g = FakeGrid().floor(0..9, -5..9)
    for (z in -2..2) g.column(3, z, 1..2)
    return g
}

/** Flat 10x10 with solid blocks at y=1 for x >= 3 (a one-block step). */
fun stepAtX3(): FakeGrid {
    val g = flat(10)
    for (x in 3..9) for (z in 0..9) g.set(x, 1, z)
    return g
}

/** Flat 10x10 split by a full-width 2-high wall at x=3. */
fun enclosed(): FakeGrid {
    val g = flat(10)
    for (z in 0..9) g.column(3, z, 1..2)
    return g
}
