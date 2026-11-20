package pvpbots.bukkit

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.block.data.type.Leaves
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Entity
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import pvpbots.adapter.BotHandle
import pvpbots.arena.ArenaLayout
import pvpbots.arena.ArenaRecord
import pvpbots.arena.Facing
import pvpbots.arena.Part
import pvpbots.arena.Preset
import pvpbots.boss.BossEncounter
import pvpbots.math.BlockPos
import pvpbots.session.AreaRef
import pvpbots.session.Role
import pvpbots.session.SessionKey
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Boss self-test checks (B1 arena; B2, B2b, B5, B6, B8 encounters), run by [SelfTest] in [SelfTest.Mode.All] in the
 * temporary flat world.
 */
internal class BossSelfTest(private val t: SelfTest, private val plugin: PvpBotsPlugin, private val world: World) {
    private val service get() = plugin.arenaService
    private val registry get() = plugin.arenaService.registry
    private val manager get() = plugin.botManager

    suspend fun SequenceScope<Int>.run() {
        bossArena()
        Bukkit.getPluginManager().registerEvents(watch, plugin)
        try {
            encounter()
        } finally {
            HandlerList.unregisterAll(watch)
            plugin.bossDirector.rewards.dispatch = BossRewards.CONSOLE
            plugin.bossDirector.trace = null
        }
    }

    /** Boss hits on dummies (cancelled here: the dummies must live through the whole check) and defeat events. */
    private val watch = object : Listener {
        var bossHits = 0
        val defeated = ArrayList<BossDefeatedEvent>()

        @EventHandler(priority = EventPriority.LOWEST)
        fun onHit(event: EntityDamageByEntityEvent) {
            val src = (event.damager as? Projectile)?.shooter as? Entity ?: event.damager
            if (manager.record(src.uniqueId)?.role == Role.BOSS && t.isFake(event.entity.uniqueId)) {
                bossHits++
                event.isCancelled = true
                event.entity.fireTicks = 0
            }
        }

        @EventHandler
        fun onDefeat(event: BossDefeatedEvent) {
            defeated += event
        }
    }

    private fun bossOf(arena: String) = manager.records().firstOrNull { it.role == Role.BOSS && it.key == SessionKey.Boss(arena) }

    private fun bossCount() = manager.records().count { it.role == Role.BOSS }

    private fun heal(ds: List<BotHandle>) = ds.forEach { d ->
        val p = d.bukkit
        if (!p.isDead) {
            p.health = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            p.fireTicks = 0
        }
    }

    private suspend fun SequenceScope<Int>.waitHealing(ds: List<BotHandle>, maxTicks: Int, cond: () -> Boolean): Int =
        waitUntil(maxTicks) { heal(ds); cond() }

    /**
     * B2 spawn, B2b scaling, B5 wipe, B6 victory + cooldown, B8 reload during a fight, all in one boss arena. The dummies
     * enter and leave through the region callback, the way RegionWatcher calls it.
     */
    private suspend fun SequenceScope<Int>.encounter() {
        val name = "selftest_b2"
        val origin = BlockPos(2600, world.getHighestBlockYAt(2600, 0), 0)
        val layout = ArenaLayout.generate(Preset.BOSS, origin, Facing.NORTH)
        val original = manager.config
        val base = original.bosses["ashblade"]
        if (base == null) {
            t.check("B2 boss spawns", false, "no ashblade boss type in the config")
            return
        }
        // Short cooldown and a recorded command, so B6 can see the cooldown end and the commands run.
        val type = base.copy(commands = listOf("pvpbotstest give %player% %boss%"), cooldownSeconds = 8)
        manager.config = BotConfig(original.settings, original.profiles, original.kits, original.skins, original.bosses + ("ashblade" to type))
        plugin.newDirector(null)
        val boss = plugin.bossDirector
        val commands = ArrayList<String>()
        boss.rewards.dispatch = { commands += it }
        val traced = ArrayList<String>()
        boss.trace = { traced += it }
        service.createAt(world, origin, Facing.NORTH, name, Preset.BOSS, null, null, "ashblade")
        val ready = waitUntil(600) { registry.get(name)?.status == ArenaRecord.Status.READY }
        val rec = registry.get(name)
        if (ready < 0 || rec == null) {
            t.check("B2 boss spawns", false, "boss arena never became READY (${rec?.status})")
            service.forget(name)
            manager.config = original
            return
        }
        val area = AreaRef(world.name, rec.regionId, Role.BOSS, null, 1, layout.region, arena = name)
        val entry = rec.padsA.single()
        val ds = ArrayList<BotHandle>()
        fun dummy(label: String, dx: Int): BotHandle = t.spawnDummy(label, entry.x + dx, entry.z).also { ds += it }
        try {
            // ---- B2: one player enters -> after 3 s a boss with the type's gear, bar shown, max = base.
            val d1 = dummy("Bs1", 0)
            yield(2)
            boss.onAreaChange(d1.bukkit, null, area)
            val spawned = waitHealing(ds, 20) { bossOf(name) != null }
            yield(60)
            heal(ds)
            val bp = bossOf(name)?.player
            val main = bp?.inventory?.itemInMainHand
            val kb = bp?.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue // netherite armour adds its own on top
            val gearOk = bp != null && bp.inventory.helmet?.type == Material.NETHERITE_HELMET &&
                bp.inventory.chestplate?.type == Material.NETHERITE_CHESTPLATE &&
                main?.type == Material.NETHERITE_SWORD && main.getEnchantmentLevel(Enchantment.SHARPNESS) == 5 &&
                kb != null && abs(kb - type.knockbackResistance) < 1e-6
            val inArena = bp != null && hypot(bp.location.x - (origin.x + 0.5), bp.location.z - (origin.z + 0.5)) <= 20.0
            val h = boss.health(name)
            val barOk = boss.barViewers(name) == setOf(d1.id)
            val fight = boss.encounterState(name) == BossEncounter.State.FIGHT
            t.check(
                "B2 boss spawns",
                spawned >= 0 && gearOk && inArena && h != null && h.max == type.baseHealth && h.current == type.baseHealth && barOk && fight &&
                    boss.hasSlot(name) && plugin.slots.used() == 1 && boss.state(name).startsWith("fighting"),
                "boss spawned after $spawned ticks as ${bp?.name}; gear ok $gearOk (helmet ${bp?.inventory?.helmet?.type}, main ${main?.type} " +
                    "sharpness ${main?.getEnchantmentLevel(Enchantment.SHARPNESS)}, base knockback resistance $kb); inside the arena $inArena; " +
                    "health ${h?.current}/${h?.max} (base ${type.baseHealth}); bar viewers ${boss.barViewers(name).size} (ok $barOk); " +
                    "state ${boss.encounterState(name)} '${boss.state(name)}'; slot ${boss.hasSlot(name)}, used ${plugin.slots.used()}",
            )

            // ---- B5: everybody leaves -> after 10 s the boss is removed, bar gone, IDLE, slot released.
            boss.onAreaChange(d1.bukkit, area, null)
            val early = waitHealing(ds, 180) { bossOf(name) == null }
            val late = if (early >= 0) -1 else waitHealing(ds, 60) { bossOf(name) == null }
            yield(2)
            t.check(
                "B5 wipe resets",
                early < 0 && late >= 0 && boss.barViewers(name).isEmpty() && boss.encounterState(name) == BossEncounter.State.IDLE &&
                    !boss.hasSlot(name) && plugin.slots.used() == 0 && bossCount() == 0 && boss.state(name) == "idle",
                "boss removed ${if (early >= 0) "too early, after $early ticks" else "after ${180 + late} ticks"} without participants; " +
                    "bar viewers ${boss.barViewers(name).size}; state ${boss.encounterState(name)} '${boss.state(name)}'; " +
                    "slot ${boss.hasSlot(name)}, used ${plugin.slots.used()}; bosses ${bossCount()}",
            )

            // ---- B2b: three players enter together -> max = base x 2.2.
            val d2 = dummy("Bs2", 2)
            val d3 = dummy("Bs3", -2)
            yield(2)
            listOf(d1, d2, d3).forEach { boss.onAreaChange(it.bukkit, null, area) }
            val spawned3 = waitHealing(ds, 20) { bossOf(name) != null }
            yield(2)
            val h3 = boss.health(name)
            val want = type.baseHealth * (1 + type.healthPerExtraPlayer * 2)
            t.check(
                "B2b scaling",
                spawned3 >= 0 && h3 != null && abs(h3.max - want) < 1e-6 && h3.current == h3.max,
                "3 players: max ${h3?.max} (want $want = ${type.baseHealth} x 2.2), current ${h3?.current}; boss after $spawned3 ticks",
            )

            // ---- B6: victory, rewards to winners only, cooldown.
            val afk = dummy("Afk", 0)
            yield(2)
            boss.onAreaChange(afk.bukkit, null, area)
            waitHealing(ds, 80) { boss.encounterState(name) == BossEncounter.State.FIGHT }
            val h4 = boss.health(name)
            val want4 = type.baseHealth * (1 + type.healthPerExtraPlayer * 3)
            val raised = h4 != null && abs(h4.max - want4) < 1e-6
            val bossP = bossOf(name)?.player
            for (d in listOf(d1, d2, d3)) d.bukkit.inventory.setItemInMainHand(ItemStack(Material.IRON_SWORD))
            val xpBefore = ds.associate { it.id to it.bukkit.totalExperience }
            // A real hit through the damage listener lowers the pool, never the fake player's own health.
            val before = boss.health(name)?.current ?: 0.0
            if (bossP != null) manager.adapter.attack(d1, bossP)
            yield(12)
            val afterHit = boss.health(name)?.current ?: 0.0
            val realHit = before - afterHit
            val bossFull = bossP != null && bossP.health == bossP.getAttribute(Attribute.MAX_HEALTH)?.value
            // Non-entity damage does nothing.
            bossP?.damage(6.0)
            bossP?.fireTicks = 100
            yield(25)
            val afterEnv = boss.health(name)?.current ?: 0.0
            bossP?.fireTicks = 0
            // Player vs player inside a boss arena: blocked.
            heal(ds)
            val d2Before = d2.bukkit.health
            manager.adapter.attack(d1, d2.bukkit)
            yield(2)
            val pvpBlocked = d2.bukkit.health == d2Before
            // d2 and d3 deal damage; d3 then leaves the region before the kill (no reward).
            if (bossP != null) {
                manager.adapter.attack(d2, bossP)
                yield(12)
                manager.adapter.attack(d3, bossP)
                yield(12)
            }
            boss.onAreaChange(d3.bukkit, area, null)
            yield(2)
            // From outside the region: ignored.
            val beforeOut = boss.health(name)?.current ?: 0.0
            if (bossP != null) manager.adapter.attack(d3, bossP)
            yield(12)
            val outIgnored = boss.health(name)?.current == beforeOut
            val bossAttacked = waitHealing(ds, 300) { watch.bossHits > 0 }
            // The kill: the rest of the pool through the same entry the damage listener uses.
            boss.damageFrom(name, d1.bukkit, (boss.health(name)?.current ?: 0.0) + 1.0)
            val dead = waitHealing(ds, 5) { boss.encounterState(name) == BossEncounter.State.VICTORY }
            yield(5)
            val gone = bossOf(name) == null && boss.barViewers(name).isEmpty()
            val events = watch.defeated.toList()
            val winners = events.singleOrNull()?.winners?.map { it.uniqueId }?.toSet()
            fun apples(d: BotHandle) = d.bukkit.inventory.all(Material.GOLDEN_APPLE).values.sumOf { it.amount }
            val lootOk = apples(d1) >= 2 && apples(d2) >= 2 && apples(d3) == 0 && apples(afk) == 0
            val xpGain = ds.associate { it.bukkit.name to it.bukkit.totalExperience - (xpBefore[it.id] ?: 0) }
            val xpOk = xpGain[d1.bukkit.name] == type.xp && xpGain[d2.bukkit.name] == type.xp && xpGain[d3.bukkit.name] == 0 && xpGain[afk.bukkit.name] == 0
            val cmdOk = commands.size == 2 &&
                commands.toSet() == setOf("pvpbotstest give ${d1.bukkit.name} ${type.name}", "pvpbotstest give ${d2.bukkit.name} ${type.name}")
            val titled = traced.any { it.startsWith("title ${BossEncounter.VICTORY_TITLE}") }
            // Cooldown: action bar for the players inside, no boss until it ends (8 s from the kill), then a new intro.
            val cooldownSeen = waitHealing(ds, 140) { boss.encounterState(name) == BossEncounter.State.COOLDOWN }
            yield(2)
            val bar = traced.lastOrNull { it.startsWith("actionbar ") }.orEmpty()
            val barOk2 = bar.contains("The arena is quiet… returns in 0:0")
            val stateLine = boss.state(name)
            var bossDuringCooldown = false
            val back = waitHealing(ds, 200) {
                if (boss.encounterState(name) == BossEncounter.State.COOLDOWN && bossCount() > 0) bossDuringCooldown = true
                bossOf(name) != null
            }
            t.check(
                "B6 victory",
                raised && realHit > 0.0 && bossFull && afterEnv == afterHit && pvpBlocked && outIgnored && bossAttacked >= 0 && dead >= 0 && gone &&
                    events.size == 1 && winners == setOf(d1.id, d2.id) && events.single().bossType == "ashblade" && events.single().arena == name &&
                    lootOk && xpOk && cmdOk && titled && cooldownSeen >= 0 && barOk2 && stateLine.startsWith("cooldown") && !bossDuringCooldown && back >= 0,
                "max raised by a 4th player $raised (${h4?.max}); a real hit took ${"%.2f".format(realHit)} from the pool, boss own health full $bossFull; " +
                    "environment damage changed the pool ${afterEnv != afterHit}; pvp blocked $pvpBlocked; hit from outside ignored $outIgnored; " +
                    "boss attacked a dummy after $bossAttacked ticks; VICTORY after $dead ticks, boss and bar gone $gone; events ${events.size}, " +
                    "winners ${events.singleOrNull()?.winners?.map { it.name }}; apples ${ds.map { apples(it) }}; xp $xpGain; commands $commands; " +
                    "victory title $titled; COOLDOWN after $cooldownSeen ticks, action bar '$bar', state '$stateLine'; boss during cooldown $bossDuringCooldown; " +
                    "new boss after the cooldown after $back more ticks",
            )

            // ---- B8: reload during a fight -> boss gone, no rewards, IDLE.
            waitHealing(ds, 100) { boss.encounterState(name) == BossEncounter.State.FIGHT }
            val fighting = boss.encounterState(name) == BossEncounter.State.FIGHT
            boss.damageFrom(name, afk.bukkit, 5.0)
            val eventsBefore = watch.defeated.size
            val cmdsBefore = commands.size
            plugin.reload()
            val idleNow = (boss.encounterState(name) ?: BossEncounter.State.IDLE) == BossEncounter.State.IDLE
            val noBoss = bossCount() == 0 && boss.barViewers(name).isEmpty() && !boss.hasSlot(name)
            yield(40)
            heal(ds)
            t.check(
                "B8 reload during fight",
                fighting && idleNow && noBoss && bossCount() == 0 && watch.defeated.size == eventsBefore && commands.size == cmdsBefore &&
                    (boss.encounterState(name) ?: BossEncounter.State.IDLE) == BossEncounter.State.IDLE && plugin.slots.used() == 0,
                "fighting before $fighting; right after the reload: IDLE $idleNow, boss, bar and slot gone $noBoss; 2 s later: bosses ${bossCount()}, " +
                    "state ${boss.encounterState(name)}, events ${watch.defeated.size - eventsBefore}, commands ${commands.size - cmdsBefore}, " +
                    "slots used ${plugin.slots.used()}",
            )
        } finally {
            ds.forEach {
                runCatching { plugin.bossDirector.onAreaChange(it.bukkit, area, null) }
                t.removeDummy(it)
            }
            manager.config = original
            service.remove(name)
            if (waitUntil(600) { registry.get(name) == null } < 0) service.forget(name)
        }
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
