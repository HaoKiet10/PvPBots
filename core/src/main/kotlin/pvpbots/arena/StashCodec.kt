package pvpbots.arena

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Gzipped list of per-slot item bytes (null = empty slot). Corrupt input always throws [IllegalArgumentException]. */
object StashCodec {
    private const val MAGIC = "PVST"
    private const val VERSION = 1

    fun encode(slots: List<ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(out)).use { d ->
            d.writeBytes(MAGIC)
            d.writeByte(VERSION)
            d.writeInt(slots.size)
            for (s in slots) {
                if (s == null) d.writeInt(-1) else { d.writeInt(s.size); d.write(s) }
            }
        }
        return out.toByteArray()
    }

    fun decode(b: ByteArray): List<ByteArray?> {
        try {
            DataInputStream(GZIPInputStream(ByteArrayInputStream(b))).use { d ->
                val magic = ByteArray(MAGIC.length)
                d.readFully(magic)
                require(String(magic, Charsets.US_ASCII) == MAGIC) { "Not an inventory stash (bad magic)" }
                val version = d.readUnsignedByte()
                require(version == VERSION) { "Unsupported inventory stash version $version" }
                val count = d.readInt()
                require(count >= 0) { "Corrupt inventory stash: negative slot count" }
                val result = ArrayList<ByteArray?>()
                repeat(count) {
                    val len = d.readInt()
                    require(len >= -1) { "Corrupt inventory stash: bad slot length $len" }
                    if (len == -1) result += null else {
                        val bytes = d.readNBytes(len) // grows incrementally, so a bogus length cannot force a huge allocation
                        require(bytes.size == len) { "Truncated inventory stash" }
                        result += bytes
                    }
                }
                require(d.read() == -1) { "Corrupt inventory stash: trailing data" }
                return result
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("Corrupt or truncated inventory stash: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}
