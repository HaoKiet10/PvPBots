package pvpbots.bukkit

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.managers.RegionManager
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import pvpbots.arena.ArenaChecks
import pvpbots.arena.ArenaJob
import pvpbots.arena.ArenaLayout
import pvpbots.arena.ArenaRecord
import pvpbots.arena.ArenaRecord.Status
import pvpbots.arena.BlockSnapshot
import pvpbots.arena.Facing
import pvpbots.arena.Layout
import pvpbots.arena.Part
import pvpbots.arena.Preset
import pvpbots.math.BlockPos
import pvpbots.math.Bounds
import pvpbots.session.Role
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Level
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Builds and removes plugin-built arenas. Safety order for every block change: the snapshot of the original blocks is
 * on disk (fsynced) and the record says BUILDING/REMOVING before the first block changes; jobs run a few thousand
 * blocks per tick from [tick]; after a crash [resumeAll] restarts every unfinished job from the start (building the
 * layout and restoring the snapshot are both idempotent). The WorldGuard region exists only while the arena is READY
 * (plus the moment between creating it and marking READY, when [RegionWatcher] ignores it). Main thread only.
 * Returned strings are messages for the admin.
 */
class ArenaService(private val plugin: PvpBotsPlugin, val registry: ArenaRegistry, private val dir: File) {
    /** Called with the arena name before an arena's blocks or kit change: restores kit-arena inventories (§5.6). */
    var onArenaRelease: (String) -> Unit = {}

    private class Running(val record: ArenaRecord, val job: ArenaJob, val writer: BukkitBlockWriter, val chunks: List<Chunk>, val admin: UUID?)

    private val jobs = LinkedHashMap<String, Running>()

    /** Unfinished arenas whose job could not start (unreadable snapshot, failed write): not retried until restart. */
    private val failed = HashSet<String>()
    private var ticks = 0L

    private val settings get() = plugin.botManager.config.settings.arenas

    fun busy(name: String): Boolean = name in jobs

    fun snapshotFile(name: String) = File(dir, "$name.snapshot")

    /** Block data the build writes for [part] (configured material; air for [Part.AIR]). */
    fun blockDataFor(part: Part): String {
        if (part == Part.AIR) return Material.AIR.createBlockData().asString
        val name = settings.materials[part] ?: return Material.STONE.createBlockData().asString
        return (Material.matchMaterial(name) ?: Material.STONE).createBlockData().asString
    }

    /** The config's own `kits:` key matching [kit] ignoring case (commands lowercase what they read), else [kit] unchanged. */
    fun kitKey(kit: String?): String? =
        kit?.let { k -> plugin.botManager.config.kits.kits.keys.firstOrNull { it.equals(k, ignoreCase = true) } ?: k }

    fun layoutOf(r: ArenaRecord): Layout = ArenaLayout.generate(r.preset, r.origin, r.facing)

    // ---- create ----

    fun preview(p: Player, name: String, preset: Preset, kit: String?): List<String> =
        previewAt(p.world, originOf(p), Facing.fromYaw(p.location.yaw), name, preset, kit)

    fun create(p: Player, name: String, preset: Preset, kit: String?): List<String> =
        createAt(p.world, originOf(p), Facing.fromYaw(p.location.yaw), name, preset, kit, p.uniqueId)

    /** What [createAt] would build here, its warnings, and why it would be refused (if it would). */
    internal fun previewAt(world: World, origin: BlockPos, facing: Facing, name: String, preset: Preset, kitName: String?): List<String> {
        val kit = kitKey(kitName)
        val layout = ArenaLayout.generate(preset, origin, facing)
        val (refusals, warnings) = evaluate(world, name, kit, layout)
        val lines = ArrayList<String>()
        lines += describe(name, world, origin, facing, preset, kit, layout)
        warnings.forEach { lines += "Warning: $it" }
        refusals.forEach { lines += "Cannot build: $it" }
        return lines
    }

    /** Checks, then snapshot, record and job. Refused (nothing changed) when any check fails or the snapshot cannot be written. */
    internal fun createAt(world: World, origin: BlockPos, facing: Facing, name: String, preset: Preset, kitName: String?, admin: UUID?): List<String> {
        val kit = kitKey(kitName)
        val layout = ArenaLayout.generate(preset, origin, facing)
        val (refusals, warnings) = evaluate(world, name, kit, layout)
        if (refusals.isNotEmpty()) return refusals.map { "Cannot build: $it" }

        // A snapshot without a record may be the only copy of some terrain (registry lost or entry skipped): keep it aside.
        val notes = ArrayList<String>()
        val old = snapshotFile(name)
        if (old.exists()) {
            val aside = File(dir, "$name.snapshot.orphan-${System.currentTimeMillis()}")
            try {
                Files.move(old.toPath(), aside.toPath())
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Arena $name: existing snapshot ${old.name} could not be moved aside; nothing was changed", e)
                return listOf("Cannot build: an old snapshot ${old.name} exists and could not be moved aside (${e.message}); nothing was changed.")
            }
            plugin.logger.warning("Arena $name: found a snapshot with no arena record; moved it to ${aside.path} (it may hold original terrain)")
            notes += "Note: an old snapshot with no arena record was kept as ${aside.name} in ${dir.path}; it may hold original terrain."
        }

        val writer = BukkitBlockWriter(world)
        val original = layout.blocks.keys.map { it to writer.read(it) }
        try {
            ArenaRegistry.writeDurably(snapshotFile(name), BlockSnapshot.encode(original))
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Arena $name: snapshot could not be written; nothing was changed", e)
            snapshotFile(name).delete()
            return listOf("Cannot build: the snapshot of the original blocks could not be written (${e.message}); nothing was changed.")
        }
        val record = ArenaRecord(
            name = name, world = world.name, origin = origin, facing = facing, preset = preset,
            regionId = regionIdFor(name), status = Status.BUILDING, kit = kit, padsA = layout.padsA, padsB = layout.padsB,
        )
        try {
            registry.put(record)
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Arena $name: arenas.yml could not be written; nothing was changed", e)
            snapshotFile(name).delete()
            return listOf("Cannot build: arenas.yml could not be written (${e.message}); nothing was changed.")
        }
        start(record, layout, admin)
        plugin.logger.info("Arena $name: building ${layout.blocks.size} blocks in ${world.name} at ${pos(origin)} facing $facing")
        return notes + warnings.map { "Warning: $it" } +
            "Building arena '$name' (${layout.blocks.size} blocks, about ${eta(layout.blocks.size)}); it opens for fights once finished."
    }

    /** Refusals (non-empty = do not build) and warnings for building [layout] as [name]. */
    private fun evaluate(world: World, name: String, kit: String?, layout: Layout): Pair<List<String>, List<String>> {
        val refusals = ArrayList<String>()
        val warnings = ArrayList<String>()
        if (!NAME.matches(name)) refusals += "the name must be 1-32 characters a-z, 0-9, '_' or '-'."
        if (registry.get(name) != null) refusals += "an arena named '$name' already exists."
        if (name in registry.skippedNames()) refusals += "arenas.yml has an unreadable entry named '$name'; fix or remove it by hand first."
        if (kit != null && kit !in plugin.botManager.config.kits.kits) {
            refusals += "no kit named '$kit' (kits: ${plugin.botManager.config.kits.kits.keys.joinToString(", ")})."
        }
        val wg = regionManager(world)
        if (wg == null) {
            refusals += "WorldGuard regions are disabled in world ${world.name}."
        } else if (NAME.matches(name) && wg.hasRegion(regionIdFor(name))) {
            refusals += "a WorldGuard region named ${regionIdFor(name)} already exists."
        }
        val others = registry.all().filter { it.world == world.name && it.name != name }.map { layoutOf(it).outer }
        refusals += ArenaChecks.check(layout, world.minHeight, world.maxHeight - 1, borderOf(world), others, blockEntities(world, layout.outer))
        if (wg != null) {
            val o = layout.outer
            val probe = ProtectedCuboidRegion("pvpbots_probe", true, vec(o.min), vec(o.max))
            val overlapping = wg.getApplicableRegions(probe).regions.filter { it !is GlobalProtectedRegion }.map { it.id }.sorted()
            if (overlapping.isNotEmpty()) warnings += "overlaps WorldGuard region(s) ${overlapping.joinToString(", ")}; their flags still apply."
        }
        return refusals to warnings
    }

    private fun describe(name: String, world: World, origin: BlockPos, facing: Facing, preset: Preset, kit: String?, layout: Layout): String {
        val o = layout.outer
        return "Arena '$name': ${preset.name.lowercase()} (${preset.inside}x${preset.inside} inside, ${o.max.x - o.min.x + 1}x${o.max.z - o.min.z + 1} " +
            "with walls, ${preset.padsPerSide} pad(s) per side), floor at ${pos(origin)} in ${world.name}, entrance behind you, facing ${facing.name.lowercase()}; " +
            "${layout.blocks.size} blocks change (about ${eta(layout.blocks.size)}); ${kit?.let { "kit $it" } ?: "own gear"}."
    }

    // ---- remove / kit ----

    fun remove(name: String): List<String> {
        val r = registry.get(name) ?: return listOf("No arena named '$name'.")
        // A BUILDING arena with no running job (its job failed) may be removed: its snapshot is on disk.
        if (busy(name)) {
            return listOf("Arena '$name' is still being ${if (r.status == Status.REMOVING) "removed" else "built"}; try again when it has finished.")
        }
        val world = Bukkit.getWorld(r.world) ?: return listOf("World ${r.world} of arena '$name' is not loaded; nothing was changed.")
        val original = try {
            readSnapshot(name)
        } catch (e: Exception) {
            return listOf("The snapshot of arena '$name' (${snapshotFile(name).name}) cannot be read (${e.message}); nothing was changed.")
        }
        TickIsolation.guard({ onArenaRelease(name) }) { e -> plugin.logger.log(Level.SEVERE, "Arena $name: release hook failed", e) }
        try {
            registry.put(r.copy(status = Status.REMOVING))
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Arena $name: arenas.yml could not be written", e)
            return listOf(
                "Cannot remove: arenas.yml could not be written (${e.message}). Kit-arena inventories in '$name' were given back, " +
                    "but the arena was not removed and its blocks are unchanged.",
            )
        }
        // The record is REMOVING, so the region no longer resolves to an area: the refresh ends every session in it.
        regionManager(world)?.let { wg ->
            wg.removeRegion(r.regionId)
            saveRegions(wg, name)
        }
        plugin.regionWatcher.refreshAll()
        failed.remove(name)
        evacuate(world, r)
        start(r.copy(status = Status.REMOVING), original, null)
        plugin.logger.info("Arena $name: removing (restoring ${original.size} blocks)")
        return listOf("Removing arena '$name': restoring ${original.size} original blocks (about ${eta(original.size)}).")
    }

    fun setKit(name: String, kitName: String?): List<String> {
        val kit = kitKey(kitName)
        val r = registry.get(name) ?: return listOf("No arena named '$name'.")
        if (r.status == Status.REMOVING) return listOf("Arena '$name' is being removed.")
        if (kit != null && kit !in plugin.botManager.config.kits.kits) {
            return listOf("No kit named '$kit'. Kits: ${plugin.botManager.config.kits.kits.keys.joinToString(", ")}, or none.")
        }
        TickIsolation.guard({ onArenaRelease(name) }) { e -> plugin.logger.log(Level.SEVERE, "Arena $name: release hook failed", e) }
        try {
            registry.put(r.copy(kit = kit))
        } catch (e: Exception) {
            return listOf("Cannot change the kit: arenas.yml could not be written (${e.message}).")
        }
        plugin.regionWatcher.refreshAll()
        return listOf("Arena '$name' now uses ${kit?.let { "kit $it" } ?: "the players' own gear"}.")
    }

    fun list(): List<String> {
        val all = registry.all()
        if (all.isEmpty()) return listOf("No arenas. Build one with /pvpbots arena create <name> <duel|squad|team> [kit].")
        return all.map { r ->
            val inside = Bukkit.getOnlinePlayers().count { plugin.regionWatcher.areaOf(it.uniqueId)?.arena == r.name }
            val world = Bukkit.getWorld(r.world)
            val regionMissing = r.status == Status.READY && world != null && regionManager(world)?.hasRegion(r.regionId) != true
            val job = jobs[r.name]?.let { " ${it.job.done * 100 / maxOf(1, it.job.targets.size)}%" } ?: ""
            "${r.name}: ${r.world} ${pos(r.origin)}, ${r.preset.name.lowercase()}, ${r.kit?.let { "kit $it" } ?: "own gear"}, " +
                "${r.status.name.lowercase()}$job, $inside player(s) inside" +
                (if (regionMissing) " (region missing)" else "") +
                (if (world == null) " (world not loaded)" else "") +
                (if (r.name in failed) " (job failed, see the server log)" else "")
        }
    }

    // ---- jobs ----

    /** Restarts every unfinished build/removal whose world is loaded (on enable, and every second for late worlds). */
    fun resumeAll() {
        for (r in registry.all()) {
            if (r.status == Status.READY || r.name in jobs || r.name in failed) continue
            val world = Bukkit.getWorld(r.world) ?: continue
            val original = try {
                readSnapshot(r.name)
            } catch (e: Exception) {
                failed += r.name
                plugin.logger.severe(
                    "Arena ${r.name} is ${r.status} but its snapshot ${snapshotFile(r.name)} cannot be read (${e.message}); " +
                        "no blocks are changed. Fix or restore the file, then restart the server.",
                )
                continue
            }
            if (r.status == Status.REMOVING) {
                // The region delete may not have been saved before the crash: delete it again before restoring.
                try {
                    deleteRegion(world, r)
                } catch (e: Exception) {
                    plugin.logger.log(Level.WARNING, "Arena ${r.name}: region ${r.regionId} could not be deleted now; retried when the removal finishes", e)
                }
                plugin.regionWatcher.refreshAll()
                evacuate(world, r)
            }
            val layout = layoutOf(r)
            plugin.logger.info("Arena ${r.name}: resuming ${if (r.status == Status.BUILDING) "build" else "removal"} in ${world.name}")
            if (r.status == Status.BUILDING) start(r, layout, null) else start(r, original, null)
        }
    }

    fun tick() {
        if (++ticks % RESUME_EVERY_TICKS == 0L) resumeAll()
        var budget = settings.blocksPerTick
        for (run in jobs.values.toList()) {
            if (budget <= 0) break
            val before = run.job.done
            val finished = try {
                run.job.step(run.writer, budget)
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Arena ${run.record.name}: block job failed at ${run.job.done}/${run.job.targets.size}; it stays ${run.record.status} and resumes after a restart", e)
                stopJob(run)
                failed += run.record.name
                continue
            }
            budget -= run.job.done - before
            if (finished) finish(run)
        }
    }

    /** Plugin disabling: unfinished jobs stay recorded and resume on the next start. */
    fun stop() {
        jobs.values.toList().forEach { stopJob(it) }
    }

    /** Drops a record and its snapshot without touching blocks (self-test cleanup of a deleted temporary world). */
    internal fun forget(name: String) {
        jobs[name]?.let { stopJob(it) }
        registry.delete(name)
        snapshotFile(name).delete()
        failed.remove(name)
    }

    private fun start(r: ArenaRecord, layout: Layout, admin: UUID?) =
        start(r, layout.blocks.map { (p, part) -> p to blockDataFor(part) }, admin)

    private fun start(r: ArenaRecord, targets: List<Pair<BlockPos, String>>, admin: UUID?) {
        val world = Bukkit.getWorld(r.world) ?: return
        val outer = layoutOf(r).outer
        val chunks = ArrayList<Chunk>()
        for (cx in (outer.min.x shr 4)..(outer.max.x shr 4)) for (cz in (outer.min.z shr 4)..(outer.max.z shr 4)) {
            val c = world.getChunkAt(cx, cz)
            c.addPluginChunkTicket(plugin)
            chunks += c
        }
        jobs[r.name] = Running(r, ArenaJob(targets), BukkitBlockWriter(world), chunks, admin)
    }

    private fun stopJob(run: Running) {
        jobs.remove(run.record.name)
        run.chunks.forEach { runCatching { it.removePluginChunkTicket(plugin) } }
    }

    private fun finish(run: Running) {
        stopJob(run)
        val r = run.record
        val world = Bukkit.getWorld(r.world)
        try {
            if (r.status == Status.BUILDING) {
                if (world != null) {
                    createRegion(world, r)
                    moveOntoFloor(world, layoutOf(r))
                }
                registry.put(r.copy(status = Status.READY))
                plugin.logger.info("Arena ${r.name}: built and open")
                run.admin?.let { Bukkit.getPlayer(it) }?.sendMessage("PvpBots: arena '${r.name}' is built and open.")
            } else {
                val w = world ?: error("world ${r.world} is not loaded")
                // The restored blocks reach disk (flushed) before anything that could restore them again is deleted: a
                // crash before the next autosave would otherwise lose the original terrain. If this throws, the record
                // stays REMOVING and the job is redone after a restart.
                w.save(true)
                // Region gone (and saved) before the record: an orphan region would turn into a plain v1 trainer area.
                deleteRegion(w, r)
                // Record before snapshot: a crash after this leaves only an orphan snapshot file, never a record without one.
                registry.delete(r.name)
                snapshotFile(r.name).delete()
                plugin.logger.info("Arena ${r.name}: removed, original blocks restored")
            }
        } catch (e: Exception) {
            failed += r.name
            plugin.logger.log(Level.SEVERE, "Arena ${r.name}: finishing the ${r.status} job failed; it resumes after a restart", e)
        }
    }

    private fun createRegion(world: World, r: ArenaRecord) {
        val wg = regionManager(world)
        if (wg == null) {
            plugin.logger.warning("Arena ${r.name}: WorldGuard regions are disabled in ${world.name}; no region created")
            return
        }
        val b = layoutOf(r).region
        val region = ProtectedCuboidRegion(r.regionId, vec(b.min), vec(b.max))
        region.priority = REGION_PRIORITY
        region.setFlag(RegionWatcher.ROLE, Role.TRAINER)
        region.setFlag(Flags.BUILD, StateFlag.State.DENY)
        region.setFlag(Flags.PVP, StateFlag.State.ALLOW)
        wg.addRegion(region)
        saveRegions(wg, r.name)
    }

    /** Deletes the arena's region if present and saves; throws when the change cannot be saved. */
    private fun deleteRegion(world: World, r: ArenaRecord) {
        val wg = regionManager(world) ?: return
        if (wg.hasRegion(r.regionId)) wg.removeRegion(r.regionId)
        wg.saveChanges()
    }

    private fun saveRegions(wg: RegionManager, name: String) {
        try {
            wg.saveChanges()
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Arena $name: WorldGuard regions could not be saved now (WorldGuard retries on its own)", e)
        }
    }

    /**
     * Real players (not bots) in the footprint of [r] are put just outside it in front of the entrance, on the highest
     * block there: the restore then cannot bury them.
     */
    private fun evacuate(world: World, r: ArenaRecord) {
        val o = layoutOf(r).outer
        val spot = ArenaLayout.outsideEntrance(r.preset, r.origin, r.facing)
        for (p in world.players) {
            if (plugin.botManager.isBot(p.uniqueId)) continue
            val l = p.location
            if (l.blockX !in o.min.x..o.max.x || l.blockZ !in o.min.z..o.max.z || l.blockY !in o.min.y - 1..o.max.y + 1) continue
            val y = world.getHighestBlockYAt(spot.x, spot.z) + 1
            val ok = p.teleport(Location(world, spot.x + 0.5, y.toDouble(), spot.z + 0.5, l.yaw, l.pitch))
            if (!ok) plugin.logger.warning("Arena ${r.name}: ${p.name} could not be moved out of the footprint before the removal")
        }
    }

    /** Real players standing in the footprint (inside a wall, say) are put on the floor inside the walls. */
    private fun moveOntoFloor(world: World, layout: Layout) {
        val o = layout.outer
        val inner = layout.region
        for (p in world.players) {
            if (plugin.botManager.isBot(p.uniqueId)) continue
            val l = p.location
            if (l.blockX !in o.min.x..o.max.x || l.blockZ !in o.min.z..o.max.z || l.blockY !in o.min.y..o.max.y + 1) continue
            val x = l.blockX.coerceIn(inner.min.x, inner.max.x)
            val z = l.blockZ.coerceIn(inner.min.z, inner.max.z)
            p.teleport(Location(world, x + 0.5, (o.min.y + 1).toDouble(), z + 0.5, l.yaw, l.pitch))
        }
    }

    // ---- helpers ----

    private fun readSnapshot(name: String): List<Pair<BlockPos, String>> = BlockSnapshot.decode(snapshotFile(name).readBytes())

    private fun originOf(p: Player): BlockPos {
        val l = p.location
        return BlockPos(l.blockX, l.blockY - 1, l.blockZ)
    }

    private fun blockEntities(world: World, outer: Bounds): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        for (cx in (outer.min.x shr 4)..(outer.max.x shr 4)) for (cz in (outer.min.z shr 4)..(outer.max.z shr 4)) {
            for (state in world.getChunkAt(cx, cz).getTileEntities()) {
                val p = BlockPos(state.x, state.y, state.z)
                if (outer.contains(p)) out += p
            }
        }
        return out.sortedWith(compareBy({ it.x }, { it.y }, { it.z }))
    }

    private fun borderOf(world: World): Bounds {
        val b = world.worldBorder
        val r = b.size / 2.0
        val c = b.center
        return Bounds(
            BlockPos(ceil(c.x - r).toInt(), world.minHeight, ceil(c.z - r).toInt()),
            BlockPos(floor(c.x + r).toInt() - 1, world.maxHeight - 1, floor(c.z + r).toInt() - 1),
        )
    }

    private fun regionManager(world: World): RegionManager? =
        WorldGuard.getInstance().platform.regionContainer.get(BukkitAdapter.adapt(world))

    private fun vec(p: BlockPos) = BlockVector3.at(p.x, p.y, p.z)

    private fun pos(p: BlockPos) = "(${p.x}, ${p.y}, ${p.z})"

    private fun eta(blocks: Int): String {
        val ticks = (blocks + settings.blocksPerTick - 1) / settings.blocksPerTick
        return if (ticks < 20) "$ticks tick(s)" else "${ticks / 20} s"
    }

    companion object {
        const val REGION_PRIORITY = 10
        private const val RESUME_EVERY_TICKS = 20L
        private val NAME = Regex("^[a-z0-9_-]{1,32}$")

        fun regionIdFor(name: String) = "pvpbots_$name"
    }
}
