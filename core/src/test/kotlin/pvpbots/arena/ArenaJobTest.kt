package pvpbots.arena

import org.junit.jupiter.api.Test
import pvpbots.math.BlockPos
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArenaJobTest {
    class FakeWriter(val world: MutableMap<BlockPos, String> = HashMap()) : BlockWriter {
        val log = ArrayList<Pair<BlockPos, String>>()
        override fun read(p: BlockPos) = world[p] ?: "minecraft:air"
        override fun write(p: BlockPos, blockData: String) { world[p] = blockData; log += p to blockData }
    }

    private fun targets(n: Int, data: String = "minecraft:stone") = (0 until n).map { BlockPos(it % 100, it / 100, 0) to data }

    @Test
    fun job_writesInBudgetedSteps() {
        val t = targets(10_000)
        val w = FakeWriter()
        val job = ArenaJob(t)
        assertFalse(job.step(w, 4000))
        assertFalse(job.step(w, 4000))
        assertTrue(job.step(w, 4000))
        assertEquals(t, w.log)
        assertEquals(10_000, job.done)
    }

    @Test
    fun job_stepAfterFinishedIsNoop() {
        val w = FakeWriter()
        val job = ArenaJob(targets(3))
        assertTrue(job.step(w, 10))
        assertTrue(job.step(w, 10))
        assertEquals(3, w.log.size)
    }

    @Test
    fun job_resumedRemoving_restoresAllBlocks() {
        val original = HashMap<BlockPos, String>()
        for (i in 0 until 10_000) original[BlockPos(i % 100, i / 100, 0)] = if (i % 3 == 0) "minecraft:dirt" else "minecraft:grass_block"
        val w = FakeWriter(HashMap(original))
        val snapshot = BlockSnapshot.decode(BlockSnapshot.encode(original.entries.sortedBy { it.key.y * 100 + it.key.x }.map { it.key to it.value }))

        val layout = ArenaJob(snapshot.map { it.first to "minecraft:glass" })
        assertFalse(layout.step(w, 5000)) // crash halfway through the build
        assertEquals(5000, layout.done)

        val restore = ArenaJob(snapshot)
        while (!restore.step(w, 1500)) { }
        assertEquals(original, w.world)
    }

    @Test
    fun job_resumedBuilding_completesLayout() {
        val t = targets(10_000)
        val w = FakeWriter()
        val job = ArenaJob(t, done = 3000)
        while (!job.step(w, 2500)) { }
        assertEquals(t.drop(3000), w.log)
    }
}
