package pvpbots.arena

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StashCodecTest {
    private fun slots(): List<ByteArray?> {
        val r = Random(7)
        return (0 until 41).map { if (it % 3 == 0) null else r.nextBytes(1 + r.nextInt(5000)) }
    }

    @Test
    fun stash_roundTrip() {
        val s = slots()
        val d = StashCodec.decode(StashCodec.encode(s))
        assertEquals(s.size, d.size)
        for (i in s.indices) if (s[i] == null) assertNull(d[i]) else assertContentEquals(s[i], d[i])
    }

    @Test
    fun stash_emptyArrayIsNotNull() {
        val d = StashCodec.decode(StashCodec.encode(listOf(ByteArray(0), null)))
        assertContentEquals(ByteArray(0), d[0])
        assertNull(d[1])
    }

    @Test
    fun stash_rejectsTruncated() {
        val b = StashCodec.encode(slots())
        assertThrows<IllegalArgumentException> { StashCodec.decode(b.copyOf(b.size / 2)) }
    }

    @Test
    fun stash_rejectsGarbageAndMalformed() {
        assertThrows<IllegalArgumentException> { StashCodec.decode(Random(2).nextBytes(100)) }
        fun gz(f: DataOutputStream.() -> Unit): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(GZIPOutputStream(out)).use { it.f() }
            return out.toByteArray()
        }
        assertThrows<IllegalArgumentException> { StashCodec.decode(gz { writeBytes("PVBS"); writeByte(1); writeInt(0) }) }
        assertThrows<IllegalArgumentException> { StashCodec.decode(gz { writeBytes("PVST"); writeByte(9); writeInt(0) }) }
        assertThrows<IllegalArgumentException> { StashCodec.decode(gz { writeBytes("PVST"); writeByte(1); writeInt(1); writeInt(-2) }) }
        assertThrows<IllegalArgumentException> { StashCodec.decode(gz { writeBytes("PVST"); writeByte(1); writeInt(1); writeInt(1_000_000) }) }
        assertThrows<IllegalArgumentException> { StashCodec.decode(gz { writeBytes("PVST"); writeByte(1); writeInt(0); writeByte(1) }) }
    }
}
