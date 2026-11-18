package pvpbots.arena

import pvpbots.math.BlockPos
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Gzipped palette-compressed list of (position, block data). Corrupt input always throws [IllegalArgumentException]. */
object BlockSnapshot {
    private const val MAGIC = "PVBS"
    private const val VERSION = 1

    fun encode(blocks: List<Pair<BlockPos, String>>): ByteArray {
        val palette = LinkedHashMap<String, Int>()
        for ((_, data) in blocks) palette.getOrPut(data) { palette.size }
        val out = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(out)).use { d ->
            d.writeBytes(MAGIC)
            d.writeByte(VERSION)
            d.writeInt(palette.size)
            for (s in palette.keys) d.writeUTF(s)
            d.writeInt(blocks.size)
            for ((p, data) in blocks) {
                d.writeInt(p.x); d.writeInt(p.y); d.writeInt(p.z)
                writeVarInt(d, palette.getValue(data))
            }
        }
        return out.toByteArray()
    }

    fun decode(b: ByteArray): List<Pair<BlockPos, String>> {
        try {
            DataInputStream(GZIPInputStream(ByteArrayInputStream(b))).use { d ->
                val magic = ByteArray(MAGIC.length)
                d.readFully(magic)
                require(String(magic, Charsets.US_ASCII) == MAGIC) { "Not a block snapshot (bad magic)" }
                val version = d.readUnsignedByte()
                require(version == VERSION) { "Unsupported block snapshot version $version" }
                val paletteSize = d.readInt()
                require(paletteSize >= 0) { "Corrupt block snapshot: negative palette size" }
                val palette = ArrayList<String>()
                repeat(paletteSize) { palette += d.readUTF() }
                val count = d.readInt()
                require(count >= 0) { "Corrupt block snapshot: negative block count" }
                val result = ArrayList<Pair<BlockPos, String>>()
                repeat(count) {
                    val x = d.readInt(); val y = d.readInt(); val z = d.readInt()
                    val idx = readVarInt(d)
                    require(idx in palette.indices) { "Corrupt block snapshot: palette index $idx out of range" }
                    result += BlockPos(x, y, z) to palette[idx]
                }
                require(d.read() == -1) { "Corrupt block snapshot: trailing data" }
                return result
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("Corrupt or truncated block snapshot: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun writeVarInt(d: DataOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            d.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        d.writeByte(v)
    }

    private fun readVarInt(d: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = d.readUnsignedByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 35) { "Corrupt block snapshot: varint too long" }
        }
    }
}
