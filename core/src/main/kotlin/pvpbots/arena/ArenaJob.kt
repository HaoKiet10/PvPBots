package pvpbots.arena

import pvpbots.math.BlockPos

/** A staged block job. [done] counts targets already written, so a persisted job can resume where it stopped. */
class ArenaJob(val targets: List<Pair<BlockPos, String>>, var done: Int = 0) {
    /** Writes up to [budget] more targets. Returns true once every target has been written. */
    fun step(w: BlockWriter, budget: Int): Boolean {
        val end = minOf(targets.size.toLong(), done.toLong() + maxOf(budget, 0)).toInt()
        while (done < end) {
            val (p, data) = targets[done]
            w.write(p, data)
            done++
        }
        return done >= targets.size
    }
}
