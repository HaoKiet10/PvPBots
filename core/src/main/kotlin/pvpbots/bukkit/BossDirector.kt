package pvpbots.bukkit

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import pvpbots.arena.ArenaLayout
import pvpbots.arena.ArenaRecord
import pvpbots.arena.Preset
import pvpbots.boss.BossEncounter
import pvpbots.boss.BossEncounter.Effect
import pvpbots.boss.BossHealth
import pvpbots.boss.BossType
import pvpbots.math.Aim
import pvpbots.math.Vec3
import pvpbots.session.AreaRef
import pvpbots.session.Role
import pvpbots.session.SessionKey
import java.util.UUID
import java.util.logging.Level

/**
 * Runs the boss encounters (spec 5.3, 5.6, 5.7): one [BossEncounter] + [BossHealth] per boss arena, fed from the
 * region watcher and checked every tick. A player is inside a boss arena only while inside its region AND within the
 * round wall (horizontal distance from the centre <= [RADIUS]); participants are the inside players who are alive and
 * in survival/adventure. The boss takes one [pvpbots.slots.BotSlots] slot, only when one is free with nothing queued
 * (INTRO waits otherwise), and gives it back when it is removed. Bars and action bars this class shows are hidden
 * again on [shutdown]. Nothing survives a restart or reload: every arena starts IDLE. Main thread only.
 */
class BossDirector(private val plugin: PvpBotsPlugin) {
    private val manager get() = plugin.botManager
    val rewards = BossRewards(plugin.logger)

    /** Self-test hook: told "title <text>", "actionbar <text>" and "reward <player>" as they happen. */
    var trace: ((String) -> Unit)? = null

    private inner class Arena(val rec: ArenaRecord, val type: BossType) {
        val enc = BossEncounter(type.cooldownSeconds)
        val health = BossHealth(type.baseHealth, type.healthPerExtraPlayer)
        val key = SessionKey.Boss(rec.name)
        val area: AreaRef = AreaRef(
            rec.world, rec.regionId, Role.BOSS, null, 1, ArenaLayout.generate(rec.preset, rec.origin, rec.facing).region, arena = rec.name,
        )
        val bar: BossBar = BossBar.bossBar(Component.text(type.name), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10)
        var barOn = false
        val viewers = HashSet<UUID>()
        val actionBarred = HashSet<UUID>()
        var inside: List<Player> = emptyList()
        var bossId: UUID? = null
        var controller: BossController? = null
        var hasSlot = false
        var waitingSpawn = false
        var deadPending = false
        var retryAtMs = 0L
        var lastHitTick = Int.MIN_VALUE / 2
        var lastHitAmount = 0.0
    }

    private val arenas = HashMap<String, Arena>()

    /** Real players standing in a boss arena's region (as the region watcher reports it), by arena name. */
    private val inRegion = HashMap<UUID, String>()

    /** Winners who were dead at the kill: their rewards wait until they are alive again (a dead player's inventory is lost on respawn). */
    private val pendingRewards = HashMap<UUID, MutableList<BossType>>()
    private val warned = HashSet<String>()

    // ---- inputs ----

    fun onAreaChange(p: Player, from: AreaRef?, to: AreaRef?) {
        val id = p.uniqueId
        if (manager.isBot(id)) return
        val name = to?.takeIf { it.role == Role.BOSS }?.arena
        if (name != null) inRegion[id] = name else inRegion.remove(id)
    }

    /** A real player died: if they were a participant they leave the fight but stay remembered for the winner rule. */
    fun onPlayerDeath(id: UUID) {
        for (a in arenas.values) if (id in a.enc.participants) guard(a) { run(a, a.enc.playerDied(id)) }
    }

    fun onQuit(id: UUID) {
        inRegion.remove(id)
        pendingRewards.remove(id)
    }

    /** The boss arena [name] is being removed: its boss goes now, bars are hidden, the encounter is forgotten. */
    fun releaseArena(name: String) {
        arenas.remove(name)?.let { close(it) }
        inRegion.values.removeIf { it == name }
    }

    fun tick() {
        val now = manager.nowMs()
        deliverPending()
        val names = LinkedHashSet<String>(arenas.keys)
        names += inRegion.values
        for (name in names) {
            val a = arenas[name] ?: open(name) ?: continue
            guard(a) { tickArena(a, now) }
        }
    }

    /** Plugin disable / reload / new director: every boss removed (no rewards), every bar hidden, every arena IDLE. */
    fun shutdown() {
        for (a in arenas.values.toList()) close(a)
        arenas.clear()
        inRegion.clear()
    }

    // ---- queries ----

    /** The boss arena [id] (a real player) is inside of: region and round wall; null if none. */
    fun arenaOf(id: UUID): String? {
        val name = inRegion[id] ?: return null
        val rec = arenas[name]?.rec ?: plugin.arenaService.registry.get(name) ?: return null
        val p = player(id) ?: return null
        return name.takeIf { insideWall(rec, p) }
    }

    /** The arena of a boss bot, or null when [botId] is not a boss. */
    fun bossArena(botId: UUID): String? = (manager.record(botId)?.key as? SessionKey.Boss)?.arena

    /** For `arena list` / `boss list`: idle, intro, waiting for a bot slot, fighting, cooldown m:ss. */
    fun state(arena: String): String {
        val a = arenas[arena] ?: return "idle"
        return when (a.enc.state) {
            BossEncounter.State.IDLE -> "idle"
            BossEncounter.State.INTRO -> if (a.waitingSpawn) "waiting for a free bot slot" else "intro"
            BossEncounter.State.FIGHT ->
                "fighting (${a.enc.participants.size} player(s), health ${a.health.current.toInt()}/${a.health.max.toInt()})"
            BossEncounter.State.VICTORY, BossEncounter.State.COOLDOWN -> "cooldown ${clock(a.enc.cooldownSecondsLeft(manager.nowMs()))}"
        }
    }

    fun bossCount(): Int = arenas.values.count { it.bossId != null }

    internal fun encounterState(arena: String): BossEncounter.State? = arenas[arena]?.enc?.state
    internal fun health(arena: String): BossHealth? = arenas[arena]?.health
    internal fun barViewers(arena: String): Set<UUID> = arenas[arena]?.viewers?.toSet().orEmpty()
    internal fun hasSlot(arena: String): Boolean = arenas[arena]?.hasSlot == true

    // ---- damage ----

    /**
     * Any damage to boss bot [botId] was cancelled by the listener; [attacker] (a real player, the shooter for
     * projectiles) dealt [finalDamage]. Vanilla's 10-tick hurt window is kept: within it only the part above the last
     * hit counts.
     */
    fun onBossHit(botId: UUID, attacker: Player, finalDamage: Double) {
        val a = arenas[bossArena(botId) ?: return]?.takeIf { it.bossId == botId } ?: return
        val tick = Bukkit.getCurrentTick()
        var raw = finalDamage
        if (tick - a.lastHitTick < HURT_TICKS) {
            if (finalDamage <= a.lastHitAmount) return
            raw = finalDamage - a.lastHitAmount
            if (damageFrom(a.rec.name, attacker, raw) > 0.0) a.lastHitAmount = finalDamage
        } else if (damageFrom(a.rec.name, attacker, raw) > 0.0) {
            a.lastHitTick = tick
            a.lastHitAmount = finalDamage
        }
    }

    /**
     * [raw] damage from [attacker] to the boss of [arena]'s pool (FIGHT only; an attacker outside the arena is
     * ignored). Returns what the pool lost. At 0 the boss dies on the next tick.
     */
    fun damageFrom(arena: String, attacker: Player, raw: Double): Double {
        val a = arenas[arena] ?: return 0.0
        if (a.enc.state != BossEncounter.State.FIGHT || a.bossId == null || a.deadPending) return 0.0
        if (arenaOf(attacker.uniqueId) != arena) return 0.0
        val c = a.controller
        val taken = a.health.hit(raw, recovering = c?.recovering == true, roaring = c?.roaring == true)
        if (taken <= 0.0) return 0.0
        a.enc.bossDamaged(attacker.uniqueId, taken)
        c?.recordDamage(attacker.uniqueId, taken)
        a.bar.progress(a.health.fraction.toFloat())
        if (a.health.current <= 0.0) a.deadPending = true
        return taken
    }

    // ---- per tick ----

    private fun open(name: String): Arena? {
        val rec = plugin.arenaService.registry.get(name) ?: return null
        if (rec.preset != Preset.BOSS || rec.status != ArenaRecord.Status.READY || rec.padsB.isEmpty()) return null
        val type = manager.config.bosses[rec.bossType]
        if (type == null) {
            if (warned.add(name)) plugin.logger.warning("Boss arena $name: boss type '${rec.bossType}' is not in config.yml `bosses:`; no boss spawns there")
            return null
        }
        return Arena(rec, type).also { arenas[name] = it }
    }

    private fun tickArena(a: Arena, now: Long) {
        val inside = insidePlayers(a)
        a.inside = inside
        val participants = inside.filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
        val ids = participants.mapTo(HashSet()) { it.uniqueId }
        for (id in a.enc.participants.toList()) {
            if (id in ids) continue
            run(a, if (player(id)?.isDead == true) a.enc.playerDied(id) else a.enc.playerLeft(id))
        }
        for (p in participants) {
            if (p.uniqueId in a.enc.participants || now < a.retryAtMs) continue
            run(a, a.enc.playerEntered(p.uniqueId))
            if (a.enc.state == BossEncounter.State.INTRO || a.enc.state == BossEncounter.State.FIGHT) a.health.playersNow(a.enc.participants.size)
        }
        if (a.deadPending) {
            a.deadPending = false
            run(a, a.enc.bossDead())
        }
        if (a.waitingSpawn) {
            if (a.enc.participants.isEmpty()) run(a, a.enc.shutdown()) else trySpawn(a, now)
        }
        // While the boss waits for a slot the encounter is not ticked: its 3 s intro starts when the boss is there.
        if (!a.waitingSpawn) run(a, a.enc.tick(now))
        driveBoss(a, participants, now)
        syncBar(a)
    }

    private fun driveBoss(a: Arena, participants: List<Player>, now: Long) {
        val id = a.bossId ?: return
        val r = manager.record(id)
        if (r == null || !r.player.isValid || r.player.isDead) {
            plugin.logger.warning("Boss arena ${a.rec.name}: the boss was lost (removed or dead outside the fight); the encounter is reset")
            a.bossId = null
            a.controller = null
            run(a, a.enc.shutdown())
            removeBoss(a) // gives the slot back even if the encounter had nothing to remove
            a.retryAtMs = now + RETRY_MS
            return
        }
        val c = a.controller ?: return
        val bot = r.player
        // Never outside its arena: back to the centre.
        val c0 = a.rec.padsB.first()
        if (!insideWall(a.rec, bot) || bot.location.y < c0.y - 1.0 || bot.location.y > c0.y + ArenaLayout.BOSS_CLEAR_HEIGHT + 4.0) {
            bot.teleport(spawnPoint(a, bot.world))
            bot.fallDistance = 0f
        }
        bot.fireTicks = 0
        try {
            if (a.enc.state == BossEncounter.State.FIGHT) c.tick(participants) else c.idle()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            plugin.logger.log(Level.SEVERE, "Boss ${a.type.id} in arena ${a.rec.name} failed and was removed", e)
            manager.remove(id) // noticed as lost on the next tick
        }
    }

    private fun run(a: Arena, effects: List<Effect>) {
        for (e in effects) {
            when (e) {
                Effect.SpawnBoss -> {
                    a.health.start(maxOf(1, a.enc.participants.size))
                    a.bar.progress(1f)
                    a.deadPending = false
                    a.waitingSpawn = true
                    trySpawn(a, manager.nowMs())
                }
                Effect.RemoveBoss -> {
                    a.bossId?.let { id -> manager.record(id)?.player?.let { deathPuff(it.location) } }
                    removeBoss(a)
                }
                Effect.ShowBar -> if (a.enc.state == BossEncounter.State.INTRO || a.enc.state == BossEncounter.State.FIGHT) {
                    a.barOn = true
                    syncBar(a)
                }
                Effect.HideBar -> {
                    a.barOn = false
                    hideBar(a)
                }
                is Effect.Title -> {
                    val title = Title.title(Component.text(e.text), Component.text(a.type.name))
                    for (p in a.inside) {
                        p.showTitle(title)
                        p.playSound(p.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
                    }
                    trace?.invoke("title ${e.text} to ${a.inside.map { it.name }}")
                }
                is Effect.CooldownBar -> {
                    val text = "The arena is quiet\u2026 returns in ${clock(e.secondsLeft)}"
                    for (p in a.inside) {
                        p.sendActionBar(Component.text(text))
                        a.actionBarred += p.uniqueId
                    }
                    trace?.invoke("actionbar $text")
                }
                is Effect.Reward -> reward(a, e.winners)
            }
        }
    }

    private fun trySpawn(a: Arena, now: Long) {
        if (!a.waitingSpawn) return
        if (!a.hasSlot) {
            val slots = plugin.slots
            // Only a slot that is free right now with nobody queued: the grant comes back here, never to the director's sessions.
            if (slots.queued() == 0 && slots.used() < slots.capacity()) a.hasSlot = a.key in slots.request(a.key, 1)
        }
        if (!a.hasSlot) {
            a.bar.name(Component.text("${a.type.name} \u2014 waiting for a free bot slot"))
            return
        }
        val world = Bukkit.getWorld(a.rec.world)
        val record = world?.let { manager.spawnBoss(a.type, a.rec.name, a.area, spawnPoint(a, it)) }
        if (record == null) {
            plugin.logger.warning("Boss arena ${a.rec.name}: the boss could not be spawned; trying again in ${RETRY_MS / 1000} s")
            a.waitingSpawn = false
            run(a, a.enc.shutdown())
            removeBoss(a)
            a.retryAtMs = now + RETRY_MS
            return
        }
        a.waitingSpawn = false
        a.bossId = record.id
        a.controller = BossController(manager.adapter, record.handle, a.type, a.rec)
        a.bar.name(Component.text(a.type.name))
        roar(record.player.location)
    }

    /** The single place a boss goes away: entity removed (if any), slot given back (if held). */
    private fun removeBoss(a: Arena) {
        a.waitingSpawn = false
        a.deadPending = false
        a.bossId?.let { manager.remove(it) }
        a.bossId = null
        a.controller = null
        if (a.hasSlot) {
            a.hasSlot = false
            plugin.director.externalSlotFreed(manager.nowMs())
        }
    }

    private fun close(a: Arena) {
        guard(a) { run(a, a.enc.shutdown()) }
        removeBoss(a)
        hideBar(a)
        a.barOn = false
        for (id in a.actionBarred) player(id)?.sendActionBar(Component.empty())
        a.actionBarred.clear()
    }

    private fun reward(a: Arena, winnerIds: Set<UUID>) {
        val winners = winnerIds.mapNotNull { player(it) }
        try {
            Bukkit.getPluginManager().callEvent(BossDefeatedEvent(a.rec.name, a.type.id, winners))
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "BossDefeatedEvent for arena ${a.rec.name} failed", e)
        }
        for (p in winners) {
            if (p.isDead) {
                pendingRewards.getOrPut(p.uniqueId) { ArrayList() } += a.type
                continue
            }
            give(p, a.type)
        }
    }

    private fun give(p: Player, type: BossType) {
        try {
            rewards.give(p, type)
            trace?.invoke("reward ${p.name}")
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Boss ${type.id}: rewards for ${p.name} failed", e)
        }
    }

    private fun deliverPending() {
        if (pendingRewards.isEmpty()) return
        val it = pendingRewards.entries.iterator()
        while (it.hasNext()) {
            val (id, types) = it.next()
            val p = player(id)
            if (p == null) {
                it.remove()
                continue
            }
            if (p.isDead || !p.isValid) continue
            it.remove()
            types.forEach { t -> give(p, t) }
        }
    }

    // ---- bar ----

    private fun syncBar(a: Arena) {
        if (!a.barOn) {
            if (a.viewers.isNotEmpty()) hideBar(a)
            return
        }
        a.bar.progress(a.health.fraction.toFloat().coerceIn(0f, 1f))
        val now = a.inside.mapTo(HashSet()) { it.uniqueId }
        for (id in a.viewers.toList()) if (id !in now) {
            player(id)?.hideBossBar(a.bar)
            a.viewers -= id
        }
        for (p in a.inside) if (a.viewers.add(p.uniqueId)) p.showBossBar(a.bar)
    }

    private fun hideBar(a: Arena) {
        for (id in a.viewers) player(id)?.hideBossBar(a.bar)
        a.viewers.clear()
    }

    // ---- helpers ----

    private fun insidePlayers(a: Arena): List<Player> {
        val out = ArrayList<Player>()
        val it = inRegion.entries.iterator()
        while (it.hasNext()) {
            val (id, name) = it.next()
            if (name != a.rec.name) continue
            val p = player(id)
            if (p == null) {
                it.remove()
                continue
            }
            if (insideWall(a.rec, p)) out += p
        }
        return out
    }

    private fun insideWall(rec: ArenaRecord, p: Player): Boolean {
        if (!p.isValid && !p.isDead) return false
        val l = p.location
        if (l.world?.name != rec.world) return false
        val c = rec.padsB.firstOrNull() ?: return false
        return Math.hypot(l.x - (c.x + 0.5), l.z - (c.z + 0.5)) <= RADIUS
    }

    /** Standing on the centre block, facing the entrance. */
    private fun spawnPoint(a: Arena, world: org.bukkit.World): Location {
        val c = a.rec.padsB.first()
        val e = a.rec.padsA.firstOrNull() ?: c
        val look = Aim.lookAt(Vec3(c.x + 0.5, c.y + 2.62, c.z + 0.5), Vec3(e.x + 0.5, c.y + 2.62, e.z + 0.5))
        return Location(world, c.x + 0.5, c.y + 1.0, c.z + 0.5, look.yaw, 0f)
    }

    private fun roar(l: Location) {
        val w = l.world ?: return
        w.playSound(l, Sound.ENTITY_RAVAGER_ROAR, 2f, 0.7f)
        w.spawnParticle(Particle.LARGE_SMOKE, l.clone().add(0.0, 1.0, 0.0), 40, 0.6, 0.8, 0.6, 0.02)
    }

    private fun deathPuff(l: Location) {
        val w = l.world ?: return
        w.spawnParticle(Particle.LARGE_SMOKE, l.clone().add(0.0, 1.0, 0.0), 30, 0.4, 0.8, 0.4, 0.02)
    }

    /** Real players through the player list; self-test dummies (not listed) through the entity lookup. */
    private fun player(id: UUID): Player? = Bukkit.getPlayer(id) ?: Bukkit.getEntity(id) as? Player

    private fun guard(a: Arena, block: () -> Unit) {
        TickIsolation.guard(block) { e -> plugin.logger.log(Level.SEVERE, "Boss arena ${a.rec.name}: encounter step failed", e) }
    }

    companion object {
        /** Inside radius of the boss arena (the round wall). */
        val RADIUS: Double = (Preset.BOSS.inside / 2).toDouble()
        private const val RETRY_MS = 5000L
        private const val HURT_TICKS = 10

        fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
