package pvpbots.path

import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.sqrt

object Pathfinder {
    private const val SQRT2 = 1.4142135623730951
    private const val NEAR = 2.0
    private const val MAX_DROP = 3

    private class Entry(val pos: BlockPos, val f: Double)

    /**
     * A* over standable feet positions inside [bounds]. Result excludes [start] and ends at [goal]; if the
     * goal is unreachable but an explored node lies within 2 blocks of it, the path to the closest such node
     * is returned. Empty if none, or if more than [maxNodes] nodes would need expanding.
     */
    fun find(g: BlockGrid, start: BlockPos, goal: BlockPos, bounds: Bounds, maxNodes: Int = 2000): List<BlockPos> {
        if (start == goal) return emptyList()
        val open = PriorityQueue<Entry>(compareBy { it.f })
        val gCost = HashMap<BlockPos, Double>()
        val parent = HashMap<BlockPos, BlockPos>()
        val closed = HashSet<BlockPos>()
        gCost[start] = 0.0
        open.add(Entry(start, dist(start, goal)))
        var expanded = 0
        var best = start
        var bestDist = dist(start, goal)

        while (open.isNotEmpty()) {
            val cur = open.poll().pos
            if (!closed.add(cur)) continue
            if (cur == goal) return rebuild(parent, start, cur)
            if (expanded >= maxNodes) return emptyList()
            expanded++
            val d = dist(cur, goal)
            if (d < bestDist) { best = cur; bestDist = d }
            val cg = gCost.getValue(cur)
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val diagonal = dx != 0 && dz != 0
                val next = neighbour(g, cur, dx, dz, diagonal) ?: continue
                if (!bounds.contains(next.first) || next.first in closed) continue
                val step = (if (diagonal) SQRT2 else 1.0) + next.second
                val ng = cg + step
                val old = gCost[next.first]
                if (old == null || ng < old) {
                    gCost[next.first] = ng
                    parent[next.first] = cur
                    open.add(Entry(next.first, ng + dist(next.first, goal)))
                }
            }
        }
        if (best != start && bestDist <= NEAR) return rebuild(parent, start, best)
        return emptyList()
    }

    /** Resulting feet position and extra cost (step-up penalty), or null if the move is impossible. */
    private fun neighbour(g: BlockGrid, c: BlockPos, dx: Int, dz: Int, diagonal: Boolean): Pair<BlockPos, Double>? {
        val nx = c.x + dx
        val nz = c.z + dz
        val up = c.y + 1
        val headClear = !g.solid(c.x, c.y + 2, c.z)

        // same level
        if (g.standable(nx, c.y, nz) && sidesClear(g, c, dx, dz, diagonal, c.y)) return BlockPos(nx, c.y, nz) to 0.0
        // step up one
        if (headClear && g.standable(nx, up, nz) && sidesClear(g, c, dx, dz, diagonal, up))
            return BlockPos(nx, up, nz) to 0.5
        // drop 1..3: column from landing up to current head must be clear
        for (drop in 1..MAX_DROP) {
            val y = c.y - drop
            if (!g.standable(nx, y, nz)) continue
            var clear = true
            for (yy in y..c.y + 1) if (g.solid(nx, yy, nz)) { clear = false; break }
            if (clear && sidesClear(g, c, dx, dz, diagonal, c.y)) return BlockPos(nx, y, nz) to 0.0
        }
        return null
    }

    /** For diagonals, both orthogonally adjacent columns must be clear from c.y up through (hi + 1). */
    private fun sidesClear(g: BlockGrid, c: BlockPos, dx: Int, dz: Int, diagonal: Boolean, targetY: Int): Boolean {
        if (!diagonal) return true
        val hi = max(c.y, targetY)
        for (y in c.y..hi + 1) {
            if (g.solid(c.x + dx, y, c.z) || g.solid(c.x, y, c.z + dz)) return false
        }
        return true
    }

    private fun rebuild(parent: Map<BlockPos, BlockPos>, start: BlockPos, end: BlockPos): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        var n = end
        while (n != start) { out.add(n); n = parent.getValue(n) }
        out.reverse()
        return out
    }

    private fun dist(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble(); val dy = (a.y - b.y).toDouble(); val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
