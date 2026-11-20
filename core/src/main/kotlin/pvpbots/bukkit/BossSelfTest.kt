package pvpbots.bukkit

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.type.Leaves
import pvpbots.arena.ArenaLayout
import pvpbots.arena.ArenaRecord
import pvpbots.arena.Facing
import pvpbots.arena.Part
import pvpbots.arena.Preset
import pvpbots.math.BlockPos
import pvpbots.session.Role

/** Boss arena self-test check (B1), run by [SelfTest] in [SelfTest.Mode.All] in the temporary flat world. */
internal class BossSelfTest(private val t: SelfTest, private val plugin: PvpBotsPlugin, private val world: World) {
    private val service get() = plugin.arenaService
    private val registry get() = plugin.arenaService.registry

    suspend fun SequenceScope<Int>.run() {
        bossArena()
    }

    /** B1: unknown type refused; a boss arena builds over varied terrain with the right region, then restores every block. */
    private suspend fun SequenceScope<Int>.bossArena() {
        val name = "selftest_b1"
        val origin = BlockPos(2500, world.getHighestBlockYAt(2500, 0), 0)
        val facing = Facing.WEST
        val layout = ArenaLayout.generate(Preset.BOSS, origin, facing)
        val types = plugin.botManager.config.bosses.keys

        // Unknown type: refused with the valid list, nothing changed.
        val before0 = layout.blocks.keys.associateWith { block(it).blockData.asString }
        val refused = service.createAt(world, origin, facing, name, Preset.BOSS, null, null, "nosuchboss")
        yield(3)
        val listed = types.isNotEmpty() && types.all { id -> refused.any { it.contains(id) } } && refused.any { it.startsWith("Cannot build") }
        val untouched = before0.count { (p, d) -> block(p).blockData.asString != d } == 0 && registry.get(name) == null && !service.snapshotFile(name).exists()

        placeTerrain(origin)
        yield(2)
        val before = layout.blocks.keys.associateWith { block(it).blockData.asString }
        val terrainKinds = before.values.toSet().size
        val type = "ashblade"
        var released = 0
        service.onBossArenaRelease = { released++ }
        val created = service.createAt(world, origin, facing, name, Preset.BOSS, null, null, type)
        val snapshotFirst = service.snapshotFile(name).exists()
        val unchangedAtCreate = before.count { (p, d) -> block(p).blockData.asString == d }
        val readyAfter = waitUntil(600) { registry.get(name)?.status == ArenaRecord.Status.READY }

        val expected = layout.blocks.mapValues { (_, part) -> service.blockDataFor(part) }
        val wrong = expected.filter { (p, d) -> block(p).blockData.asString != d }
        val rec = registry.get(name)
        val region = WorldGuard.getInstance().platform.regionContainer.get(BukkitAdapter.adapt(world))?.getRegion("pvpbots_$name")
        val regionOk = region != null && region.priority == 10 &&
            region.getFlag(RegionWatcher.ROLE) == Role.BOSS &&
            region.getFlag(RegionWatcher.BOSS_TYPE) == type &&
            region.getFlag(Flags.BUILD) == StateFlag.State.DENY &&
            region.getFlag(Flags.PVP) == StateFlag.State.ALLOW
        val recordOk = rec != null && rec.preset == Preset.BOSS && rec.bossType == type && rec.padsA == layout.padsA && rec.padsB == listOf(origin)
        val padsOk = block(layout.padsA.single()).blockData.asString == service.blockDataFor(Part.PAD_A) &&
            block(origin).blockData.asString == service.blockDataFor(Part.PAD_B)
        val persisted = ArenaRegistry(java.io.File(plugin.dataFolder, "arenas.yml")).get(name)?.bossType == type
        val listLine = service.list(bossOnly = true).firstOrNull { it.startsWith("$name:") }.orEmpty()
        val listOk = listLine.contains("type $type") && listLine.contains("encounter idle")
        val arenaListOk = service.list().any { it.startsWith("$name:") }

        val removed = service.remove(name)
        val goneAfter = waitUntil(600) { registry.get(name) == null }
        val notRestored = before.filter { (p, d) -> block(p).blockData.asString != d }
        val snapshotLeft = service.snapshotFile(name).exists()
        val regionLeft = WorldGuard.getInstance().platform.regionContainer.get(BukkitAdapter.adapt(world))?.hasRegion("pvpbots_$name") == true
        service.onBossArenaRelease = {}
        t.check(
            "B1 boss arena build/remove",
            listed && untouched && snapshotFirst && unchangedAtCreate == before.size && readyAfter >= 0 && wrong.isEmpty() &&
                regionOk && recordOk && padsOk && persisted && listOk && arenaListOk && released == 1 &&
                goneAfter >= 0 && notRestored.isEmpty() && !snapshotLeft && !regionLeft && terrainKinds >= 5,
            "unknown type refused with the type list $types: $listed, nothing changed: $untouched; create: ${created.lastOrNull()}; " +
                "snapshot written before the first block change: $snapshotFirst (blocks unchanged at create $unchangedAtCreate/${before.size}); " +
                "READY after $readyAfter ticks; ${layout.blocks.size} positions, wrong after build ${wrong.size}${sample(wrong)}; " +
                "region ${region?.id}: priority ${region?.priority}, role ${region?.getFlag(RegionWatcher.ROLE)}, boss ${region?.getFlag(RegionWatcher.BOSS_TYPE)}, " +
                "build ${region?.getFlag(Flags.BUILD)}, pvp ${region?.getFlag(Flags.PVP)}; record ok: $recordOk, pads ok: $padsOk, " +
                "bossType persisted in arenas.yml: $persisted; boss list '$listLine' ok: $listOk, arena list shows it: $arenaListOk; " +
                "remove: ${removed.lastOrNull()}; boss release hook calls: $released; record gone after $goneAfter ticks; " +
                "positions differing from before the build ${notRestored.size}${sample(notRestored, before)}; snapshot file left: $snapshotLeft; " +
                "region left: $regionLeft; distinct block states in the original terrain: $terrainKinds",
        )
        if (goneAfter < 0) service.forget(name)
    }

    /** A stone hill with persistent leaves in the middle, one under the wall ring, water pools in the floor and a slab. */
    private fun placeTerrain(origin: BlockPos) {
        fun hill(cx: Int, cz: Int, r: Int) {
            for (dx in -r..r) for (dz in -r..r) {
                val h = 3 - maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) / 2
                for (dy in 1..h) block(BlockPos(cx + dx, origin.y + dy, cz + dz)).setType(Material.STONE, false)
                if (h >= 1) {
                    val leaves = Material.OAK_LEAVES.createBlockData() as Leaves
                    leaves.isPersistent = true
                    block(BlockPos(cx + dx, origin.y + h + 1, cz + dz)).setBlockData(leaves, false)
                }
            }
        }
        hill(origin.x + 5, origin.z + 4, 4)
        hill(origin.x - 18, origin.z + 3, 3) // under the wall ring
        for ((x, z) in listOf(origin.x - 6 to origin.z + 5, origin.x + 9 to origin.z - 8)) block(BlockPos(x, origin.y, z)).setType(Material.WATER, false)
        block(BlockPos(origin.x + 2, origin.y + 1, origin.z - 12)).setType(Material.OAK_SLAB, false)
    }

    private fun sample(m: Map<BlockPos, String>, before: Map<BlockPos, String>? = null): String {
        val e = m.entries.firstOrNull() ?: return ""
        val p = e.key
        return " (e.g. $p is ${block(p).blockData.asString}, expected ${before?.get(p) ?: e.value})"
    }

    private fun block(p: BlockPos) = world.getBlockAt(p.x, p.y, p.z)
}
