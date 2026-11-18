package pvpbots.arena

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pvpbots.math.BlockPos
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockSnapshotTest {
    private val palette = listOf(
        "minecraft:air", "minecraft:stone", "minecraft:glass", "minecraft:sea_lantern",
        "minecraft:deepslate_bricks", "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
    )

    private fun sample() = (0 until 10_000).map { i ->
        BlockPos(i % 25 - 12, 64 + i / 625, (i / 25) % 25 - 12) to palette[(i * 7 + i / 3) % palette.size]
    }

    @Test
    fun snapshot_roundTrip() {
        val blocks = sample()
        val bytes = BlockSnapshot.encode(blocks)
        assertEquals(blocks, BlockSnapshot.decode(bytes))
        assertTrue(bytes.size < 10_000 * 8, "size ${bytes.size}")
    }

    @Test
    fun snapshot_emptyRoundTrip() {
        assertEquals(emptyList(), BlockSnapshot.decode(BlockSnapshot.encode(emptyList())))
    }

    @Test
    fun snapshot_rejectsGarbage() {
        assertThrows<IllegalArgumentException> { BlockSnapshot.decode(Random(1).nextBytes(200)) }
        assertThrows<IllegalArgumentException> { BlockSnapshot.decode(ByteArray(0)) }
    }

    @Test
    fun snapshot_rejectsTruncated() {
        val bytes = BlockSnapshot.encode(sample())
        assertThrows<IllegalArgumentException> { BlockSnapshot.decode(bytes.copyOf(bytes.size / 2)) }
    }

    private fun gz(f: java.io.DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        java.io.DataOutputStream(GZIPOutputStream(out)).use { it.f() }
        return out.toByteArray()
    }

    @Test
    fun snapshot_rejectsBadMagicVersionIndexAndTrailingJunk() {
        assertThrows<IllegalArgumentException> { BlockSnapshot.decode(gz { writeBytes("XXXX"); writeByte(1) }) }
        assertThrows<IllegalArgumentException> { BlockSnapshot.decode(gz { writeBytes("PVBS"); writeByte(2); writeInt(0); writeInt(0) }) }
        // one palette entry, one block whose index is 5
        assertThrows<IllegalArgumentException> {
            BlockSnapshot.decode(gz {
                writeBytes("PVBS"); writeByte(1); writeInt(1); writeUTF("minecraft:air")
                writeInt(1); writeInt(0); writeInt(0); writeInt(0); writeByte(5)
            })
        }
        assertThrows<IllegalArgumentException> {
            BlockSnapshot.decode(gz { writeBytes("PVBS"); writeByte(1); writeInt(-1) })
        }
        assertThrows<IllegalArgumentException> {
            BlockSnapshot.decode(gz { writeBytes("PVBS"); writeByte(1); writeInt(0); writeInt(0); writeByte(9) })
        }
    }
}
