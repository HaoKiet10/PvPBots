package pvpbots.bukkit

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Leaves
import org.bukkit.block.data.type.Slab
import pvpbots.arena.ArenaLayout
import pvpbots.arena.ArenaRecord
import pvpbots.arena.BlockSnapshot
import pvpbots.arena.Facing
import pvpbots.arena.Layout
import pvpbots.arena.Part
import pvpbots.arena.Preset
import pvpbots.math.BlockPos
import pvpbots.profile.Difficulty
import pvpbots.session.AreaRef
import pvpbots.session.Role
import pvpbots.session.SessionKey

/**
 * Arena self-test checks (A3, A4, T7), run by [SelfTest] in [SelfTest.Mode.All] in the temporary flat world. They use
 * the plugin's real [ArenaService] with a fake admin position (no player needed).
 */
internal class ArenaSelfTest(private val t: SelfTest, private val plugin: PvpBotsPlugin, private val world: World) {
    private val service get() = plugin.arenaService
    private val registry get() = plugin.arenaService.registry

    suspend fun SequenceScope<Int>.run() {
        refusesChest()
        buildAndRemove()
        padSpawn()
        resumedRemovalDropsRegion()
        orphanSnapshotKept()
    }

    /** A5: a REMOVING record whose region survived (unsaved delete, then crash) → resume restores and deletes the region. */
    private suspend fun SequenceScope<Int>.resumedRemovalDropsRegion() {
        val name = "selftest_a5"
        val origin = floorAt(800, 0)
        val layout = ArenaLayout.generate(Preset.DUEL, origin, Facing.SOUTH)
        val before = read(layout)
        service.createAt(world, origin, Facing.SOUTH, name, Preset.DUEL, null, null)
        val ready = waitUntil(200) { registry.get(name)?.status == ArenaRecord.Status.READY }
        val rec = registry.get(name)
        // Simulate the state after a crash: record REMOVING, region still there, no job running.
        rec?.let { registry.put(it.copy(status = ArenaRecord.Status.REMOVING)) }
        val regionBefore = regions()?.hasRegion("pvpbots_$name") == true
        service.resumeAll()
        val busy = service.busy(name)
        val gone = waitUntil(200) { registry.get(name) == null }
        val regionAfter = regions()?.hasRegion("pvpbots_$name") == true
        val notRestored = before.count { (p, d) -> block(p).blockData.asString != d }
        t.check(
            "A5 resumed removal drops region",
            ready >= 0 && regionBefore && busy && gone >= 0 && !regionAfter && notRestored == 0 && !service.snapshotFile(name).exists(),
            "READY after $ready ticks; record set REMOVING with region present=$regionBefore; resumeAll started a job: $busy; " +
                "record gone after $gone ticks; region left: $regionAfter; positions not restored $notRestored/${before.size}; " +
                "snapshot left: ${service.snapshotFile(name).exists()}",
        )
        if (gone < 0) service.forget(name)
    }

    /** A6: a snapshot with no record (registry lost) is moved aside byte-for-byte, never overwritten. */
    private suspend fun SequenceScope<Int>.orphanSnapshotKept() {
        val name = "selftest_a6"
        val origin = floorAt(900, 0)
        val file = service.snapshotFile(name)
        val orphanBytes = BlockSnapshot.encode(listOf(BlockPos(origin.x, origin.y, origin.z) to "minecraft:gold_block"))
        ArenaRegistry.writeDurably(file, orphanBytes)
        val dir = file.parentFile
        val asideBefore = dir.listFiles { f -> f.name.startsWith("$name.snapshot.orphan-") }?.toSet().orEmpty()
        val msgs = service.createAt(world, origin, Facing.NORTH, name, Preset.DUEL, null, null)
        val aside = dir.listFiles { f -> f.name.startsWith("$name.snapshot.orphan-") }?.toSet().orEmpty() - asideBefore
        val kept = aside.singleOrNull()?.readBytes()?.contentEquals(orphanBytes) == true
        val told = msgs.any { m -> aside.any { m.contains(it.name) } }
        val newSnapshot = file.exists() && !file.readBytes().contentEquals(orphanBytes)
        val ready = waitUntil(200) { registry.get(name)?.status == ArenaRecord.Status.READY }
        t.check(
            "A6 orphan snapshot kept",
            kept && told && newSnapshot && ready >= 0,
            "moved aside: ${aside.map { it.name }}; bytes identical to the orphan: $kept; admin told: $told; " +
                "new snapshot written: $newSnapshot; READY after $ready ticks; reply: ${msgs.joinToString(" | ")}",
        )
        service.remove(name)
        waitUntil(200) { registry.get(name) == null }
        aside.forEach { it.delete() }
    }

    private fun regions() = WorldGuard.getInstance().platform.regionContainer.get(BukkitAdapter.adapt(world))

    /** A4: a chest inside the footprint refuses the build; nothing changes. */
    private suspend fun SequenceScope<Int>.refusesChest() {
        val name = "selftest_a4"
        val origin = floorAt(500, 0)
        val layout = ArenaLayout.generate(Preset.DUEL, origin, Facing.NORTH)
        val chest = BlockPos(origin.x + 2, origin.y + 1, origin.z + 3)
        block(chest).setType(Material.CHEST, false)
        yield(1)
        val before = read(layout)
        val msgs = service.createAt(world, origin, Facing.NORTH, name, Preset.DUEL, null, null)
        yield(5)
        val changed = before.count { (p, d) -> block(p).blockData.asString != d }
        val named = msgs.any { it.contains("(${chest.x}, ${chest.y}, ${chest.z})") }
        val record = registry.get(name)
        val snapshot = service.snapshotFile(name).exists()
        t.check(
            "A4 arena refuses chest",
            named && changed == 0 && record == null && !snapshot,
            "create answered ${msgs.joinToString(" | ")}; reason names the chest position: $named; blocks changed $changed/${before.size}; " +
                "record ${record?.status ?: "none"}; snapshot file: $snapshot",
        )
        block(chest).setType(Material.AIR, false)
    }

    /** A3: build a team arena over varied terrain, check every block and the region, remove it, check every block again. */
    private suspend fun SequenceScope<Int>.buildAndRemove() {
        val name = "selftest_a3"
        val origin = floorAt(600, 0)
        val facing = Facing.EAST
        val layout = ArenaLayout.generate(Preset.TEAM, origin, facing)
        placeTerrain(origin)
        yield(2)
        val before = read(layout)
        val terrainKinds = before.values.toSet().size

        val created = service.createAt(world, origin, facing, name, Preset.TEAM, null, null)
        val snapshotFirst = service.snapshotFile(name).exists()
        val unchangedAtCreate = before.count { (p, d) -> block(p).blockData.asString == d }
        val readyAfter = waitUntil(400) { registry.get(name)?.status == ArenaRecord.Status.READY }

        val expected = layout.blocks.mapValues { (_, part) -> service.blockDataFor(part) }
        val wrong = expected.filter { (p, d) -> block(p).blockData.asString != d }
        val padB = service.blockDataFor(Part.PAD_B)
        val rec = registry.get(name)
        val region = WorldGuard.getInstance().platform.regionContainer.get(BukkitAdapter.adapt(world))?.getRegion("pvpbots_$name")
        val regionOk = region != null && region.priority == 10 &&
            region.getFlag(RegionWatcher.ROLE) == Role.TRAINER &&
            region.getFlag(Flags.BUILD) == StateFlag.State.DENY &&
            region.getFlag(Flags.PVP) == StateFlag.State.ALLOW &&
            region.getFlag(RegionWatcher.DIFFICULTY) == null
        val built = "create: ${created.lastOrNull()}; snapshot written before the first block change: $snapshotFirst " +
            "(blocks unchanged at create $unchangedAtCreate/${before.size}); READY after $readyAfter ticks; " +
            "${layout.blocks.size} positions, wrong after build ${wrong.size}${sample(wrong)}; pads B ${rec?.padsB?.size} (${padB}); " +
            "region ${region?.id}: priority ${region?.priority}, role ${region?.getFlag(RegionWatcher.ROLE)}, " +
            "build ${region?.getFlag(Flags.BUILD)}, pvp ${region?.getFlag(Flags.PVP)}, difficulty ${region?.getFlag(RegionWatcher.DIFFICULTY)}"

        val removed = service.remove(name)
        val goneAfter = waitUntil(400) { registry.get(name) == null }
        val notRestored = before.filter { (p, d) -> block(p).blockData.asString != d }
        val snapshotLeft = service.snapshotFile(name).exists()
        val regionLeft = WorldGuard.getInstance().platform.regionContainer.get(BukkitAdapter.adapt(world))?.hasRegion("pvpbots_$name") == true
        t.check(
            "A3 arena build/remove",
            snapshotFirst && unchangedAtCreate == before.size && readyAfter >= 0 && wrong.isEmpty() && regionOk &&
                goneAfter >= 0 && notRestored.isEmpty() && !snapshotLeft && !regionLeft && terrainKinds >= 6,
            "$built; remove: ${removed.lastOrNull()}; record gone after $goneAfter ticks; positions differing from before the build " +
                "${notRestored.size}${sample(notRestored, before)}; snapshot file left: $snapshotLeft; region left: $regionLeft; " +
                "distinct block states in the original terrain: $terrainKinds",
        )
    }

    /** T7: in a built duel arena the trainer spawns standing on the B pad. */
    private suspend fun SequenceScope<Int>.padSpawn() {
        val name = "selftest_t7"
        val origin = floorAt(700, 0)
        service.createAt(world, origin, Facing.NORTH, name, Preset.DUEL, null, null)
        val ready = waitUntil(200) { registry.get(name)?.status == ArenaRecord.Status.READY }
        val rec = registry.get(name)
        if (ready < 0 || rec == null) {
            t.check("T7 arena pad spawn", false, "arena never became READY (record ${rec?.status})")
            service.remove(name)
            waitUntil(200) { registry.get(name) == null }
            return
        }
        val layout = ArenaLayout.generate(rec.preset, rec.origin, rec.facing)
        val area = AreaRef(world.name, rec.regionId, Role.TRAINER, Difficulty.EASY, 1, layout.region, arena = rec.name, kit = rec.kit)
        val owner = t.spawnDummy("Pad", origin.x, origin.z)
        yield(5)
        val key = SessionKey.Trainer(owner.id)
        plugin.director.onAreaChange(owner.id, null, area, t.nowMs())
        var under: String? = null
        var feet: BlockPos? = null
        val spawned = waitUntil(40) {
            val bot = t.trainerBot(key) ?: return@waitUntil false
            val l = bot.player.location
            feet = BlockPos(l.blockX, l.blockY, l.blockZ)
            under = l.block.getRelative(BlockFace.DOWN).blockData.asString
            true
        }
        val padB = service.blockDataFor(Part.PAD_B)
        val onPad = feet?.let { f -> rec.padsB.any { it.x == f.x && it.y + 1 == f.y && it.z == f.z } } == true
        t.check(
            "T7 arena pad spawn",
            spawned >= 0 && under == padB && onPad,
            "trainer spawned after $spawned ticks at $feet; block under its feet $under (pad-b $padB); B pads ${rec.padsB}; on a B pad: $onPad",
        )
        plugin.director.onAreaChange(owner.id, area, null, t.nowMs())
        t.removeDummy(owner)
        service.remove(name)
        waitUntil(200) { registry.get(name) == null }
    }

    /** Stone hill with persistent leaves on top, two water pools sunk into the floor and slabs, all under the footprint. */
    private fun placeTerrain(origin: BlockPos) {
        val (ox, oy, oz) = origin
        for (dx in -4..4) for (dz in -4..4) {
            val h = 3 - maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) / 2
            for (dy in 1..h) block(BlockPos(ox + 6 + dx, oy + dy, oz + 4 + dz)).setType(Material.STONE, false)
            if (h >= 1) {
                val leaves = Material.OAK_LEAVES.createBlockData() as Leaves
                leaves.isPersistent = true
                block(BlockPos(ox + 6 + dx, oy + h + 1, oz + 4 + dz)).setBlockData(leaves, false)
            }
        }
        for ((x, z) in listOf(ox - 6 to oz + 5, ox - 9 to oz - 8)) block(BlockPos(x, oy, z)).setType(Material.WATER, false)
        val bottom = Material.STONE_SLAB.createBlockData() as Slab
        bottom.type = Slab.Type.BOTTOM
        block(BlockPos(ox - 3, oy + 1, oz - 7)).setBlockData(bottom, false)
        val top = Material.OAK_SLAB.createBlockData() as Slab
        top.type = Slab.Type.TOP
        block(BlockPos(ox + 12, oy + 2, oz - 12)).setBlockData(top, false)
    }

    private fun sample(m: Map<BlockPos, String>, before: Map<BlockPos, String>? = null): String {
        val e = m.entries.firstOrNull() ?: return ""
        val p = e.key
        return " (e.g. $p is ${block(p).blockData.asString}, expected ${before?.get(p) ?: e.value})"
    }

    private fun read(layout: Layout): Map<BlockPos, String> = layout.blocks.keys.associateWith { block(it).blockData.asString }

    /** The top block of the flat test world at (x, z): where an admin standing there would put the arena floor. */
    private fun floorAt(x: Int, z: Int) = BlockPos(x, world.getHighestBlockYAt(x, z), z)

    private fun block(p: BlockPos) = world.getBlockAt(p.x, p.y, p.z)

    companion object {
        /** Drops arena records (and snapshots) an earlier, interrupted self-test left for the temporary worlds. */
        fun purgeStale(plugin: PvpBotsPlugin) {
            val svc = plugin.arenaService
            for (r in svc.registry.all().filter { it.world.startsWith(SelfTest.WORLD) }) {
                svc.forget(r.name)
                Bukkit.getLogger().info("[PvpBots] self-test: dropped stale arena record ${r.name} (${r.status}) in ${r.world}")
            }
        }
    }
}
