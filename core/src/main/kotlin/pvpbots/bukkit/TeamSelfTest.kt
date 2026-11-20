package pvpbots.bukkit

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import pvpbots.adapter.BotHandle
import pvpbots.arena.ArenaLayout
import pvpbots.arena.ArenaRecord
import pvpbots.arena.Facing
import pvpbots.arena.Preset
import pvpbots.combat.KitSlots
import pvpbots.math.BlockPos
import pvpbots.session.AreaRef
import pvpbots.session.Role
import pvpbots.session.SessionKey
import pvpbots.skill.Outcome
import pvpbots.skill.RatingUpdate
import pvpbots.team.Side
import kotlin.math.abs

/**
 * Team-round self-test checks (T8-T10), run by [SelfTest] in [SelfTest.Mode.All] in the temporary flat world. The players
 * are dummies (real server-side `Player`s outside the player list); the region callback is called directly, the way
 * RegionWatcher calls it. Arenas are really built with the plugin's [ArenaService].
 */
internal class TeamSelfTest(private val t: SelfTest, private val plugin: PvpBotsPlugin, private val world: World) : Listener {
    private val manager get() = plugin.botManager
    private val director get() = plugin.director
    private val service get() = plugin.arenaService
    private val kits get() = plugin.kitArenas
    private val pdcKey = NamespacedKey("pvpbots", "skill")

    /** Damage events between two members of the same round, seen at MONITOR (cancelled or not), and enemy hits that went through. */
    private var friendlyLive = 0
    private var friendlyBlocked = 0
    private var enemyLive = 0

    suspend fun SequenceScope<Int>.run() {
        Bukkit.getPluginManager().registerEvents(this@TeamSelfTest, plugin)
        // Dummies must leave no player data behind: their saves are dropped (the kit-arena stash is the real subject here).
        val realSave = kits.savePlayer
        kits.savePlayer = { p -> if (!t.isFake(p.uniqueId)) realSave(p) }
        try {
            teamRound()
            kitTeamRound()
            duelToTeam()
        } finally {
            kits.savePlayer = realSave
            HandlerList.unregisterAll(this@TeamSelfTest)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val damager = (event.damager as? Projectile)?.shooter as? Entity ?: event.damager
        val a = director.teamSideOf(damager.uniqueId) ?: return
        val v = director.teamSideOf(event.entity.uniqueId) ?: return
        if (a.arena != v.arena) return
        if (a.side == v.side) {
            if (event.isCancelled) friendlyBlocked++ else friendlyLive++
        } else if (!event.isCancelled && event.finalDamage > 0.0) {
            enemyLive++
        }
    }

    private class Built(val rec: ArenaRecord, val area: AreaRef)

    private suspend fun SequenceScope<Int>.build(name: String, preset: Preset, kit: String?, x: Int): Built? {
        val origin = BlockPos(x, world.getHighestBlockYAt(x, 0), 0)
        val msgs = service.createAt(world, origin, Facing.NORTH, name, preset, kit, null)
        val ready = waitUntil(400) { registry().get(name)?.status == ArenaRecord.Status.READY }
        val rec = registry().get(name)
        if (ready < 0 || rec == null) {
            t.check("team arena $name", false, "arena never became READY (record ${rec?.status}; create: ${msgs.joinToString(" | ")})")
            cleanupArena(name)
            return null
        }
        val layout = ArenaLayout.generate(rec.preset, rec.origin, rec.facing)
        return Built(rec, AreaRef(world.name, rec.regionId, Role.TRAINER, null, 1, layout.region, arena = rec.name, kit = rec.kit, seats = rec.preset.padsPerSide))
    }

    private fun registry() = service.registry

    private suspend fun SequenceScope<Int>.cleanupArena(name: String) {
        if (registry().get(name) == null) return
        service.remove(name)
        if (waitUntil(400) { registry().get(name) == null } < 0) service.forget(name)
    }

    private fun stored(p: Player): Double? = p.persistentDataContainer.get(pdcKey, PersistentDataType.DOUBLE)

    private fun setRating(p: Player, v: Double) = p.persistentDataContainer.set(pdcKey, PersistentDataType.DOUBLE, v)

    private fun teamBots(arena: String) = manager.records().filter { (it.key as? SessionKey.Team)?.regionId == arena }

    private fun standing(rec: ArenaRecord, side: Side, pad: Int): Triple<Double, Double, Double> {
        val p = (if (side == Side.A) rec.padsA else rec.padsB)[pad]
        return Triple(p.x + 0.5, p.y + 1.0, p.z + 0.5)
    }

    /** The pad index of [side] that [e] stands on (within 0.6 blocks horizontally), or -1. */
    private fun padOf(rec: ArenaRecord, side: Side, e: Entity): Int {
        val l = e.location
        val pads = if (side == Side.A) rec.padsA else rec.padsB
        return pads.indices.firstOrNull { i ->
            val (x, y, z) = standing(rec, side, i)
            abs(l.x - x) <= 0.6 && abs(l.z - z) <= 0.6 && abs(l.y - y) <= 1.0
        } ?: -1
    }

    private fun leave(h: BotHandle, area: AreaRef) {
        director.onAreaChange(h.id, area, null, t.nowMs())
    }

    /** T8: 3 players in a team arena: balanced split, fillers on the right pads, no friendly fire, a result, +4 / -2 / 0. */
    private suspend fun SequenceScope<Int>.teamRound() {
        val name = "selftest_t8"
        val b = build(name, Preset.TEAM, null, 1800) ?: return
        val original = manager.config
        val s = original.settings.skill
        // Short fights so the first (two-player) round ends by itself and the third dummy is seated in the second one.
        manager.config = BotConfig(
            original.settings.copy(timings = original.settings.timings.copy(timeLimitS = 20, rematchDelayS = 2)),
            original.profiles, original.kits, original.skins, original.bosses,
        )
        plugin.newDirector(null)
        friendlyLive = 0
        friendlyBlocked = 0
        enemyLive = 0
        val ratings = listOf(80.0, 50.0, 20.0)
        val ds = ratings.mapIndexed { i, r -> t.spawnDummy("Tm$i", 1850, 4 * i).also { setRating(it.bukkit, r) } }
        yield(5)
        ds.take(2).forEach { director.onAreaChange(it.id, null, b.area, t.nowMs()) }
        director.onAreaChange(ds[2].id, null, b.area, t.nowMs()) // enters mid-round 1: waits for round 2
        // Round 2: all three seated.
        val seated = waitUntil(1200) { ds.all { director.teamSideOf(it.id) != null } }
        val base = ds.map { stored(it.bukkit) }
        val sides = ds.map { director.teamSideOf(it.id)?.side }
        val sumA = ds.indices.filter { sides[it] == Side.A }.sumOf { ratings[it] }
        val sumB = ds.indices.filter { sides[it] == Side.B }.sumOf { ratings[it] }
        val gap = ratings.max() - ratings.min()
        val balanced = seated >= 0 && abs(sumA - sumB) <= gap && sides.all { it != null }
        val fillersReady = waitUntil(400) { teamBots(b.rec.regionId).size == 7 }
        val fillers = teamBots(b.rec.regionId)
        val named = fillers.all { Regex("^Trainer_[0-9a-f]{4}$").matches(it.player.name) }
        val noTracker = fillers.none { manager.tracker(it.id) != null }
        yield(2)
        // Pads: every member of a side stands on its own pad of that side (dummies and fillers cover all five).
        val padReport = Side.entries.map { side ->
            val members = ds.indices.filter { sides[it] == side }.map { padOf(b.rec, side, ds[it].bukkit) } +
                fillers.filter { it.side == side }.map { padOf(b.rec, side, it.player) }
            side to members
        }
        val padsOk = padReport.all { (_, m) -> m.size == 5 && m.none { it < 0 } && m.toSet().size == 5 } &&
            fillers.all { director.teamSideOf(it.id)?.side == it.side }
        // Fight.
        val fighting = fillers.firstOrNull()?.let { waitUntil(200) { director.fighting(it.id) } } ?: -1
        yield(10)
        // A dummy hits a teammate (never allowed) and an enemy filler (allowed).
        var teammateHurt = 0.0
        var enemyHurt = 0.0
        for ((i, d) in ds.withIndex()) {
            val side = sides[i] ?: continue
            val mate: LivingEntity? = ds.indices.firstOrNull { it != i && sides[it] == side }?.let { ds[it].bukkit }
                ?: fillers.firstOrNull { it.side == side }?.player
            val foe: LivingEntity? = fillers.firstOrNull { it.side != side && it.player.isValid && !it.player.isDead }?.player
            if (mate != null) {
                val before = mate.health
                manager.adapter.attack(d, mate)
                teammateHurt += before - mate.health
                yield(12)
            }
            if (foe != null) {
                val before = foe.health
                manager.adapter.attack(d, foe)
                enemyHurt += before - foe.health
                yield(12)
            }
        }
        yield(100) // the fillers fight the dummies for a few more seconds
        val live = friendlyLive
        val blocked = friendlyBlocked
        val enemy = enemyLive
        // Decide the round: the side of the first dummy loses (its dummies and fillers die).
        val loser = sides[0]
        val stillSeated = ds.all { director.teamSideOf(it.id) != null }
        if (loser != null) {
            fillers.filter { it.side == loser }.forEach { it.player.health = 0.0 }
            ds.indices.filter { sides[it] == loser }.forEach { ds[it].bukkit.health = 0.0 }
        }
        val ended = waitUntil(200) { ds.indices.all { stored(ds[it].bukkit) != base[it] } }
        val fillersGone = waitUntil(100) { teamBots(b.rec.regionId).isEmpty() }
        val deltas = ds.indices.map { i ->
            val old = base[i] ?: ratings[i]
            val won = sides[i] != loser
            val expected = RatingUpdate.after(old, old, if (won) Outcome.PLAYER_WON else Outcome.BOT_WON, s)
            val got = stored(ds[i].bukkit)
            Triple(got, expected, got != null && abs(got - expected) < 1e-9 && abs((got - old) - (if (won) s.winBonus else -s.lossPenalty)) < 1e-9)
        }
        t.check(
            "T8 team round",
            balanced && fillersReady >= 0 && fillers.size == 7 && named && noTracker && padsOk && fighting >= 0 && stillSeated &&
                live == 0 && teammateHurt == 0.0 && blocked >= 1 && enemyHurt > 0.0 && enemy > 0 &&
                ended >= 0 && fillersGone >= 0 && deltas.all { it.third },
            "round 2 seated after $seated ticks; sides $sides (A sum $sumA, B sum $sumB, rating gap $gap); ${fillers.size} fillers after $fillersReady ticks " +
                "named Trainer_xxxx: $named, no fight tracker: $noTracker; pad indexes per side $padReport (all five distinct and on the side's pads: $padsOk); " +
                "fighting after $fighting ticks; teammate hits that went through $live, blocked $blocked, teammate health lost $teammateHurt; " +
                "enemy hit by a dummy: $enemyHurt, enemy hits that went through $enemy; round decided (side $loser lost): ratings changed after $ended ticks, " +
                "fillers removed after $fillersGone ticks; stored vs expected (+${s.winBonus} / -${s.lossPenalty}) $deltas",
        )
        ds.forEach { leave(it, b.area) }
        ds.forEach { runCatching { t.removeDummy(it) } }
        manager.config = original
        plugin.newDirector(null)
        cleanupArena(name)
    }

    private fun testItems(p: Player) {
        val inv = p.inventory
        inv.clear()
        inv.setItem(0, ItemStack(Material.DIAMOND_SWORD))
        inv.setItem(5, ItemStack(Material.EMERALD, 5))
        inv.setItem(20, ItemStack(Material.COBBLESTONE, 37))
        inv.helmet = ItemStack(Material.IRON_HELMET)
        inv.setItemInOffHand(ItemStack(Material.TORCH, 32))
    }

    private fun slots(p: Player): List<ByteArray?> = p.inventory.contents.map { it?.takeUnless { s -> s.type.isAir }?.serializeAsBytes() }

    private fun sameSlots(a: List<ByteArray?>, b: List<ByteArray?>): Boolean =
        a.size == b.size && a.indices.all { i ->
            val x = a[i]
            val y = b[i]
            if (x == null || y == null) x === y else x.contentEquals(y) || ItemStack.deserializeBytes(x) == ItemStack.deserializeBytes(y)
        }

    /** Null when the hotbar and armor hold exactly the kit's sword / food and armor (what the arena kit hands out). */
    private fun kitMismatch(p: Player, kitName: String): String? {
        val kit = manager.config.kits.kits[kitName] ?: return "no kit $kitName"
        val inv = p.inventory
        val armor = listOf(inv.helmet, inv.chestplate, inv.leggings, inv.boots).map { it?.type?.takeUnless { m -> m.isAir } }
        if (armor != kit.armor) return "armor $armor, expected ${kit.armor}"
        val sword = inv.getItem(KitSlots.SWORD)?.type
        if (sword != kit.sword) return "sword $sword, expected ${kit.sword}"
        val food = inv.getItem(KitSlots.FOOD)
        if (kit.food != null && (food?.type != kit.food || food.amount != kit.foodAmount)) return "food ${food?.type} x${food?.amount}, expected ${kit.food} x${kit.foodAmount}"
        if (inv.getItem(20) != null) return "test item left in slot 20"
        return null
    }

    /** T9: two players in a kit squad arena: stashed, kitted, placed on pads (kit refilled), restored on leave. */
    private suspend fun SequenceScope<Int>.kitTeamRound() {
        val name = "selftest_t9"
        val b = build(name, Preset.SQUAD, "IRON", 1900) ?: return
        if (b.rec.kit != "iron") {
            t.check("T9 team round in kit arena", false, "arena kit is ${b.rec.kit}, expected iron")
            cleanupArena(name)
            return
        }
        val ds = (0..1).map { t.spawnDummy("Tk$it", 1940, 4 * it) }
        yield(5)
        val originals = ds.map { d -> testItems(d.bukkit); slots(d.bukkit) }
        for (d in ds) kits.onAreaChange(d.bukkit, null, b.area)
        val stashes = ds.map { kits.stashFile(it.id).exists() }
        val stashBytes = ds.map { kits.stashFile(it.id).takeIf { f -> f.exists() }?.readBytes() }
        val kittedAtEnter = ds.map { kitMismatch(it.bukkit, "iron") }
        // Use up food: placing the players hands out a fresh kit (a new round).
        ds.forEach { it.bukkit.inventory.getItem(KitSlots.FOOD)?.amount = 1 }
        for (d in ds) director.onAreaChange(d.id, null, b.area, t.nowMs())
        val fillersReady = waitUntil(400) { teamBots(b.rec.regionId).size == 4 }
        yield(3)
        val sides = ds.map { director.teamSideOf(it.id)?.side }
        val pads = ds.indices.map { i -> sides[i]?.let { padOf(b.rec, it, ds[i].bukkit) } ?: -1 }
        val padsOk = sides.all { it != null } && pads.all { it >= 0 }
        val kitAfterPlace = ds.map { kitMismatch(it.bukkit, "iron") }
        val stashUnchanged = ds.indices.map { i -> stashBytes[i] != null && kits.stashFile(ds[i].id).let { it.exists() && it.readBytes().contentEquals(stashBytes[i]) } }
        val fillerKit = teamBots(b.rec.regionId).all { r -> kitMismatch(r.player, "iron") == null && r.player.inventory.contents.none { KitApplier.isKitItem(it) } }
        val kitFillers = teamBots(b.rec.regionId).size
        // Leave (kit service first, like the plugin does).
        val afterLeave = ds.map { d ->
            kits.onAreaChange(d.bukkit, b.area, null)
            director.onAreaChange(d.id, b.area, null, t.nowMs())
            slots(d.bukkit)
        }
        val identical = ds.indices.map { sameSlots(originals[it], afterLeave[it]) }
        val stashGone = ds.map { !kits.stashFile(it.id).exists() }
        t.check(
            "T9 team round in kit arena",
            stashes.all { it } && kittedAtEnter.all { it == null } && fillersReady >= 0 && kitFillers == 4 && padsOk && kitAfterPlace.all { it == null } &&
                stashUnchanged.all { it } && fillerKit && identical.all { it } && stashGone.all { it },
            "stashed at enter $stashes, kitted $kittedAtEnter; $kitFillers fillers after $fillersReady ticks, in the kit without tags: $fillerKit; sides $sides on pads $pads (on pads: $padsOk); " +
                "after placement (food 1 -> ${manager.config.kits.kits["iron"]?.foodAmount}): kit ${kitAfterPlace.map { it ?: "iron exactly" }}, stash file untouched $stashUnchanged; " +
                "after leaving every slot identical $identical, stash deleted $stashGone",
        )
        ds.forEach { kits.forget(it.id); kits.stashFile(it.id).delete() }
        ds.forEach { runCatching { t.removeDummy(it) } }
        plugin.newDirector(null)
        cleanupArena(name)
    }

    /** T10: a second player entering during a 1v1 ends it without a rating change and a team round starts. */
    private suspend fun SequenceScope<Int>.duelToTeam() {
        val name = "selftest_t10"
        val b = build(name, Preset.SQUAD, null, 2000) ?: return
        val d1 = t.spawnDummy("Tn1", 2040, 0)
        val d2 = t.spawnDummy("Tn2", 2040, 4)
        setRating(d1.bukkit, 60.0)
        setRating(d2.bukkit, 40.0)
        yield(5)
        val key = SessionKey.Trainer(d1.id)
        director.onAreaChange(d1.id, null, b.area, t.nowMs())
        val spawned = waitUntil(80) { t.trainerBot(key) != null }
        val duelBot = t.trainerBot(key)
        val duelFighting = duelBot != null && waitUntil(200) { director.fighting(duelBot.id) } >= 0
        val trackerBefore = manager.tracker(d1.id) != null
        director.onAreaChange(d2.id, null, b.area, t.nowMs())
        val duelGone = duelBot?.let { waitUntil(40) { !manager.isBot(it.id) } } ?: -1
        val teamStarted = waitUntil(20) { director.teamCount() == 1 }
        val fillers = waitUntil(400) { teamBots(b.rec.regionId).size == 4 }
        val trainerLeft = manager.records().any { it.key is SessionKey.Trainer }
        val trackerAfter = manager.tracker(d1.id) != null
        val r1 = stored(d1.bukkit)
        val r2 = stored(d2.bukkit)
        val seated = director.teamSideOf(d1.id) != null && director.teamSideOf(d2.id) != null
        t.check(
            "T10 1v1 to team",
            spawned >= 0 && duelFighting && trackerBefore && duelGone >= 0 && teamStarted >= 0 && fillers >= 0 && !trainerLeft && !trackerAfter &&
                r1 == 60.0 && r2 == 40.0 && seated,
            "duel bot after $spawned ticks, fighting $duelFighting, tracker $trackerBefore; second player in: duel bot gone after $duelGone ticks, team round after $teamStarted ticks, " +
                "4 fillers after $fillers ticks, trainer bots left $trainerLeft, tracker left $trackerAfter; ratings $r1 / $r2 (60 / 40 before); both seated $seated",
        )
        leave(d1, b.area)
        leave(d2, b.area)
        t.removeDummy(d1)
        t.removeDummy(d2)
        plugin.newDirector(null)
        cleanupArena(name)
    }
}
