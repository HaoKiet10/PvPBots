package pvpbots.bukkit

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import pvpbots.arena.ArenaRecord
import pvpbots.arena.Facing
import pvpbots.arena.Preset
import pvpbots.math.BlockPos
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Every plugin-built arena, persisted in `arenas.yml`. Each change is saved at once (temp file, fsync, atomic move), so
 * the record of an arena whose blocks are being changed always survives a crash. [put]/[delete] throw [IOException]
 * when the file cannot be written; the in-memory state then stays as it was. An entry that cannot be read is skipped
 * (not tracked) but never lost: arenas.yml is copied to `arenas.yml.broken-<millis>` once, and the entry's raw YAML
 * section is written back verbatim on every save (its region and snapshot stay meaningful once an admin fixes it).
 */
class ArenaRegistry(private val file: File) {
    private val records = LinkedHashMap<String, ArenaRecord>()

    /** Raw sections of entries skipped at load (name -> section values), kept and saved back as they were. */
    private val skipped = LinkedHashMap<String, Any?>()

    /** Problems found while loading (the caller logs them as SEVERE). */
    val warnings = ArrayList<String>()

    /** Names of entries that could not be read (kept in the file, not tracked). */
    fun skippedNames(): Set<String> = skipped.keys.toSet()

    init {
        load()
    }

    fun all(): List<ArenaRecord> = records.values.toList()

    fun get(name: String): ArenaRecord? = records[name]

    /** WorldGuard lower-cases region ids, so the match ignores case. */
    fun byRegion(regionId: String): ArenaRecord? = records.values.firstOrNull { it.regionId.equals(regionId, ignoreCase = true) }

    fun put(r: ArenaRecord) {
        val next = LinkedHashMap(records)
        next[r.name] = r
        save(next.values)
        records[r.name] = r
        skipped.remove(r.name)
    }

    fun delete(name: String) {
        if (name !in records) return
        val next = LinkedHashMap(records)
        next.remove(name)
        save(next.values)
        records.remove(name)
    }

    private fun load() {
        if (!file.isFile) return
        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (e: Exception) {
            // Never overwrite an unreadable registry: keep it aside for the admin, start empty.
            val aside = File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}")
            val moved = file.renameTo(aside)
            warnings += "${file.name} could not be read (${e.message}); " +
                (if (moved) "moved to ${aside.name}" else "left in place") + ". Arenas listed in it are not tracked until it is fixed."
            return
        }
        for (name in yaml.getKeys(false)) {
            val s = yaml.getConfigurationSection(name)
            val r = try {
                requireNotNull(s) { "not a section" }
                ArenaRecord(
                    name = name,
                    world = requireNotNull(s.getString("world")) { "world missing" },
                    origin = pos(s.getString("origin")),
                    facing = Facing.valueOf(requireNotNull(s.getString("facing")) { "facing missing" }),
                    preset = Preset.valueOf(requireNotNull(s.getString("preset")) { "preset missing" }),
                    regionId = requireNotNull(s.getString("region")) { "region missing" },
                    status = ArenaRecord.Status.valueOf(requireNotNull(s.getString("status")) { "status missing" }),
                    kit = s.getString("kit"),
                    padsA = s.getStringList("pads-a").map { pos(it) },
                    padsB = s.getStringList("pads-b").map { pos(it) },
                )
            } catch (e: IllegalArgumentException) {
                warnings += "${file.name}: arena '$name' skipped (${e.message}); its entry is kept in the file as it is"
                skipped[name] = plain(yaml.get(name))
                continue
            }
            records[name] = r
        }
        if (skipped.isNotEmpty()) {
            val backup = File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}")
            try {
                Files.copy(file.toPath(), backup.toPath()) // never overwrites (fails if it exists)
                warnings += "${file.name}: ${skipped.size} entr${if (skipped.size == 1) "y" else "ies"} could not be read " +
                    "(${skipped.keys.joinToString(", ")}); the file as loaded is backed up to ${backup.name}. Fix the entr" +
                    "${if (skipped.size == 1) "y" else "ies"} by hand and restart; their regions and snapshots are left alone."
            } catch (e: Exception) {
                warnings += "${file.name}: ${skipped.size} entr${if (skipped.size == 1) "y" else "ies"} could not be read " +
                    "(${skipped.keys.joinToString(", ")}) and the backup ${backup.name} could not be written (${e.message}); " +
                    "the entries are still kept in ${file.name} on every save."
            }
        }
    }

    private fun save(all: Collection<ArenaRecord>) {
        val yaml = YamlConfiguration()
        // Unreadable entries go back as they were loaded (a valid record of the same name replaces one).
        for ((name, raw) in skipped) {
            if (all.any { it.name == name }) continue
            if (raw is Map<*, *>) yaml.createSection(name, raw) else yaml.set(name, raw)
        }
        for (r in all) {
            val s = yaml.createSection(r.name)
            s.set("world", r.world)
            s.set("origin", str(r.origin))
            s.set("facing", r.facing.name)
            s.set("preset", r.preset.name)
            s.set("region", r.regionId)
            s.set("status", r.status.name)
            s.set("kit", r.kit)
            s.set("pads-a", r.padsA.map { str(it) })
            s.set("pads-b", r.padsB.map { str(it) })
        }
        writeDurably(file, yaml.saveToString().toByteArray(Charsets.UTF_8))
    }

    /** A loaded value with every section turned into a plain map (sections belong to the file they were read from). */
    private fun plain(v: Any?): Any? = when (v) {
        is ConfigurationSection -> LinkedHashMap<String, Any?>().also { m -> v.getKeys(false).forEach { k -> m[k] = plain(v.get(k)) } }
        is List<*> -> v.map { plain(it) }
        else -> v
    }

    private fun str(p: BlockPos) = "${p.x},${p.y},${p.z}"

    private fun pos(s: String?): BlockPos {
        val parts = requireNotNull(s) { "position missing" }.split(',').map { it.trim().toIntOrNull() }
        require(parts.size == 3 && parts.all { it != null }) { "bad position '$s'" }
        return BlockPos(parts[0]!!, parts[1]!!, parts[2]!!)
    }

    companion object {
        /** Writes `<name>.tmp`, fsyncs it, then atomically replaces [target]: after a crash [target] is old or new, never torn. */
        fun writeDurably(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            val from: Path = tmp.toPath()
            try {
                Files.move(from, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(from, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
