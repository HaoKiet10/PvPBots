package pvpbots.session

import pvpbots.slots.BotSlots
import java.util.UUID

/**
 * Pure coordinator between player/area events, the [TrainerSession]/[AllySquad] state machines,
 * the server-wide [BotSlots] cap and the server ([BotPort]). Not thread-safe; main thread only.
 *
 * Invariant: every bot spawned through [BotPort.spawn] holds exactly one slot, released when it is removed.
 * Effects are executed from a work queue (never recursively), so long grant chains cannot overflow the stack
 * and re-entrant calls (made from inside a [BotPort] callback) are deferred until the current item finishes.
 * A failing item (a throwing [BotPort] call included) is logged and skipped; the queue is always drained.
 *
 * Spawning is rate-limited: a granted [Effect.SpawnBot] waits (FIFO, its slot reserved) in a pending queue and
 * at most [spawnsPerTick] of them run per [tick]; events outside [tick] never spawn directly. A pending spawn
 * whose session ends first is dropped and its slot released. A trainer spawn whose owner is dead is held (slot
 * kept) until [onPlayerRespawn].
 *
 * After [MAX_FAILURES] consecutive failures of one session (spawn failed, or a bot dropped by [onBotFailed]),
 * that session stops respawning: a trainer session ends until the player re-enters the region; a squad stops
 * refilling the failing slot.
 */
class AreaDirector(
    private val slots: BotSlots,
    private val port: BotPort,
    private val t: Timings,
    private val spawnsPerTick: Int,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private class Grant(val key: SessionKey)
    private class DoSpawn(val e: Effect.SpawnBot)
    private class Retry(val dueMs: Long, val key: SessionKey)

    private val trainers = HashMap<UUID, TrainerSession>()
    private val squads = HashMap<SessionKey.Squad, AllySquad>()
    private val areas = HashMap<SessionKey, AreaRef>()
    private val playerArea = HashMap<UUID, AreaRef>()
    private val alive = HashMap<UUID, SessionKey>()
    private val retries = ArrayList<Retry>()
    private val pending = ArrayDeque<Effect.SpawnBot>()
    private val held = LinkedHashMap<UUID, MutableList<Effect.SpawnBot>>()
    private val dead = HashSet<UUID>()
    private val busySentMs = HashMap<UUID, Long>()
    private val streak = FailureStreak(maxOf(60_000L, (maxOf(t.rematchDelayS, t.allyRespawnS) + 30) * 1000L))

    private val work = ArrayDeque<Any>()
    private var draining = false
    private var grantsEnabled = true
    private var shutDown = false
    private var now = 0L

    fun onAreaChange(player: UUID, from: AreaRef?, to: AreaRef?, nowMs: Long) {
        if (shutDown || port.isBot(player)) return
        if (from != null && to != null && from.world == to.world && from.regionId == to.regionId && from.role == to.role) {
            // Same region re-reported, maybe with a new kit or flags (`/pvpbots arena kit` mid-session): the session goes
            // on (no round ends, no rating change) and the next spawn uses the new details.
            if (from != to) updateArea(player, to)
            playerArea[player] = to
            return
        }
        if (from != null) leave(player, from, nowMs)
        if (to != null) enter(player, to, nowMs)
        if (to != null) playerArea[player] = to else playerArea.remove(player)
    }

    fun onPlayerDeath(player: UUID, nowMs: Long) {
        if (shutDown) return
        dead += player
        val s = trainers[player] ?: return
        if (s.fighting()) streak.reset(s.key)
        run(s.ownerDied(nowMs), nowMs)
    }

    /** The player is alive again: a trainer spawn held while they were dead joins the pending queue (at its front). */
    fun onPlayerRespawn(player: UUID, nowMs: Long) {
        if (shutDown) return
        dead -= player
        now = nowMs
        held.remove(player)?.asReversed()?.forEach { pending.addFirst(it) }
    }

    fun onBotDeath(botId: UUID, nowMs: Long) {
        if (shutDown) return
        val key = alive[botId] ?: return
        streak.reset(key)
        val effects = when (key) {
            is SessionKey.Trainer -> trainers[key.owner]?.takeIf { it.botId() == botId }?.botDied(nowMs).orEmpty()
            is SessionKey.Squad -> squads[key]?.allyDied(botId, nowMs).orEmpty()
        }
        run(effects, nowMs)
        // Squad deaths emit no RemoveBot; make sure the dead bot's slot is freed either way.
        if (botId in alive) run(listOf(Effect.RemoveBot(botId)), nowMs)
    }

    /**
     * The bot was dropped after an internal failure (its tick threw). Ends it like a death without a title; after
     * [MAX_FAILURES] consecutive failures of its session, that session stops respawning (see the class doc).
     */
    fun onBotFailed(botId: UUID, nowMs: Long) {
        if (shutDown) return
        val key = alive[botId] ?: return
        val n = streak.record(key, nowMs)
        val effects = when (key) {
            is SessionKey.Trainer -> trainers[key.owner]?.let { s ->
                if (n >= MAX_FAILURES) giveUpTrainer(s, n) else s.takeIf { it.botId() == botId }?.botFailed(nowMs).orEmpty()
            }.orEmpty()
            is SessionKey.Squad -> squads[key]?.let { s ->
                if (n >= MAX_FAILURES) {
                    log("Ally squad ${key.world}/${key.regionId}: stopped respawning a bot after $n consecutive failures", null)
                    s.allyLost(botId)
                } else s.allyDied(botId, nowMs)
            }.orEmpty()
        }
        run(effects, nowMs)
        if (botId in alive) run(listOf(Effect.RemoveBot(botId)), nowMs)
    }

    fun onQuit(player: UUID, nowMs: Long) {
        if (shutDown) return
        val area = playerArea.remove(player)
        if (area != null) leave(player, area, nowMs)
        // Safety net: a trainer without a remembered area.
        trainers.remove(player)?.let { run(it.ownerLeft(), nowMs); areas.remove(it.key) }
        dead -= player
    }

    fun tick(nowMs: Long) {
        if (shutDown) return
        for (s in trainers.values.toList()) run(s.tick(nowMs), nowMs)
        for (s in squads.values.toList()) run(s.tick(nowMs), nowMs)
        val due = retries.filter { it.dueMs <= nowMs }
        retries.removeAll(due.toSet())
        for (r in due) {
            if (sessionActive(r.key)) run(listOf(Effect.RequestSlots(r.key, 1)), nowMs)
        }
        resendBusy(nowMs)
        spawnPending(nowMs)
        trainers.values.removeIf { if (it.state() == TrainerSession.State.ENDED) { forget(it.key); true } else false }
        squads.values.removeIf { if (it.state() == AllySquad.State.ENDED) { forget(it.key); true } else false }
    }

    /** Ends everything (all bots removed), clears the slot and spawn queues, and turns every later call into a no-op. */
    fun shutdown() {
        if (shutDown) return
        grantsEnabled = false // slots freed below must not start new spawns
        val effects = ArrayList<Effect>()
        trainers.values.forEach { effects += it.ownerLeft() }
        squads.values.forEach { s ->
            effects += Effect.CancelSlotRequests(s.key)
            s.bots().forEach { effects += Effect.RemoveBot(it) }
        }
        alive.keys.toList().forEach { effects += Effect.RemoveBot(it) }
        run(effects, now)
        slots.clear()
        trainers.clear(); squads.clear(); areas.clear(); playerArea.clear(); alive.clear(); retries.clear()
        pending.clear(); held.clear(); dead.clear(); busySentMs.clear(); streak.clear()
        shutDown = true
    }

    fun trainerFor(owner: UUID): TrainerSession? = trainers[owner]

    fun squadAt(world: String, regionId: String): AllySquad? = squads[SessionKey.Squad(world, regionId)]

    fun sessionOfBot(botId: UUID): SessionKey? = alive[botId]

    /** Live trainer sessions (any state but ENDED-and-pruned), for status output. */
    fun trainerCount(): Int = trainers.size

    /** Live ally squads, for status output. */
    fun squadCount(): Int = squads.size

    /** Granted spawns waiting for their turn (rate limit) or for a dead owner to respawn, for status output. */
    fun pendingSpawns(): Int = pending.size + held.values.sumOf { it.size }

    fun fighting(botId: UUID): Boolean = when (val key = alive[botId]) {
        is SessionKey.Squad -> true
        is SessionKey.Trainer -> trainers[key.owner]?.fighting() == true
        null -> false
    }

    // ---- area transitions ----

    private fun leave(player: UUID, from: AreaRef, nowMs: Long) {
        when (from.role) {
            Role.TRAINER -> trainers.remove(player)?.let {
                run(it.ownerLeft(), nowMs)
                forget(it.key)
            }
            Role.ALLY -> squads[SessionKey.Squad(from.world, from.regionId)]?.let { run(it.playerLeft(player, nowMs), nowMs) }
        }
    }

    private fun enter(player: UUID, to: AreaRef, nowMs: Long) {
        when (to.role) {
            Role.TRAINER -> {
                if (trainers.containsKey(player)) return
                val s = TrainerSession(player, to, t)
                trainers[player] = s
                areas[s.key] = to
                streak.reset(s.key)
                run(s.start(nowMs), nowMs)
            }
            Role.ALLY -> {
                val key = SessionKey.Squad(to.world, to.regionId)
                val s = squads[key]?.takeIf { it.state() != AllySquad.State.ENDED }
                    ?: AllySquad(to, t).also { squads[key] = it; areas[key] = to; streak.reset(key) }
                run(s.playerEntered(player, nowMs), nowMs)
            }
        }
    }

    private fun updateArea(player: UUID, to: AreaRef) {
        when (to.role) {
            Role.TRAINER -> trainers[player]?.let { s ->
                s.areaChanged(to)
                areas[s.key] = to
            }
            Role.ALLY -> SessionKey.Squad(to.world, to.regionId).let { k -> if (k in squads) areas[k] = to }
        }
    }

    private fun forget(key: SessionKey) {
        areas.remove(key)
        streak.reset(key)
        if (key is SessionKey.Trainer) busySentMs.remove(key.owner)
    }

    // ---- effect execution ----

    private fun sessionActive(key: SessionKey): Boolean = when (key) {
        is SessionKey.Trainer -> trainers[key.owner]?.let { it.state() != TrainerSession.State.ENDED } == true
        is SessionKey.Squad -> squads[key]?.let { it.state() != AllySquad.State.ENDED } == true
    }

    private fun run(effects: List<Any>, nowMs: Long) {
        now = nowMs
        work.addAll(effects)
        if (draining) return
        draining = true
        try {
            while (work.isNotEmpty()) {
                val w = work.removeFirst()
                try {
                    when (w) {
                        is Grant -> grant(w.key)
                        is DoSpawn -> spawn(w.e)
                        is Effect -> execute(w)
                    }
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (e: Throwable) {
                    log("Bot director: executing $w failed; skipped", e)
                }
            }
        } finally {
            work.clear() // empty unless a VirtualMachineError escaped: never leave stale items for the next call
            draining = false
        }
    }

    private fun enqueueGrants(keys: List<SessionKey>) {
        if (grantsEnabled) keys.forEach { work.addLast(Grant(it)) }
    }

    private fun releaseSlot() = enqueueGrants(slots.release(1))

    private fun grant(key: SessionKey) {
        val effects = when (key) {
            is SessionKey.Trainer -> trainers[key.owner]?.slotGranted(now)
            is SessionKey.Squad -> squads[key]?.slotGranted(now)
        }
        if (effects.isNullOrEmpty()) releaseSlot() else work.addAll(effects)
    }

    private fun execute(e: Effect) {
        when (e) {
            is Effect.RequestSlots -> {
                val granted = slots.request(e.key, e.n)
                enqueueGrants(granted)
                val k = e.key
                if (k is SessionKey.Trainer && k !in granted) {
                    trainers[k.owner]?.let { s ->
                        val bar = s.queued()
                        if (bar.isNotEmpty()) busySentMs[k.owner] = now
                        work.addAll(bar)
                    }
                }
            }
            is Effect.CancelSlotRequests -> {
                slots.cancel(e.key)
                dropPending(e.key)
            }
            is Effect.SpawnBot -> pending.addLast(e)
            is Effect.RemoveBot -> {
                guarded("removing bot ${e.botId}") { port.remove(e.botId) }
                if (alive.remove(e.botId) != null) releaseSlot()
            }
            is Effect.RoundEnded -> guarded("round end for ${e.owner}") { port.roundEnded(e.owner, e.outcome) }
            is Effect.Title -> guarded("title for ${e.player}") { port.title(e.player, e.text) }
            is Effect.ActionBar -> guarded("action bar for ${e.player}") { port.actionBar(e.player, e.text) }
        }
    }

    /** Pending (not yet spawned) and held spawns of [key] are dropped; their reserved slots are released. */
    private fun dropPending(key: SessionKey) {
        var n = 0
        val it = pending.iterator()
        while (it.hasNext()) if (it.next().key == key) { it.remove(); n++ }
        if (key is SessionKey.Trainer) held.remove(key.owner)?.let { n += it.size }
        repeat(n) { releaseSlot() }
    }

    private fun spawnPending(nowMs: Long) {
        var budget = spawnsPerTick
        while (budget > 0 && pending.isNotEmpty() && !shutDown) {
            val e = pending.removeFirst()
            val k = e.key
            if (k is SessionKey.Trainer && ownerDead(k.owner)) {
                held.getOrPut(k.owner) { ArrayList() } += e
                continue
            }
            budget--
            run(listOf(DoSpawn(e)), nowMs)
        }
    }

    private fun ownerDead(owner: UUID): Boolean {
        if (owner in dead) return true
        val d = try {
            port.isDead(owner)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            log("Bot director: isDead($owner) failed", e)
            false
        }
        if (d) dead += owner
        return d
    }

    /** Sends the busy action bar again every [BUSY_RESEND_MS] while a trainer waits in the slot queue. */
    private fun resendBusy(nowMs: Long) {
        for (s in trainers.values) {
            val k = s.key as SessionKey.Trainer
            if (s.state() != TrainerSession.State.QUEUED || slots.queuePosition(k) == 0) continue
            val last = busySentMs[k.owner]
            if (last != null && nowMs - last < BUSY_RESEND_MS) continue
            busySentMs[k.owner] = nowMs
            run(s.queued(), nowMs)
        }
    }

    private fun spawn(e: Effect.SpawnBot) {
        val area = areas[e.key]
        if (area == null || !sessionActive(e.key)) { // session vanished; the granted slot is unused
            releaseSlot()
            return
        }
        val id = try {
            port.spawn(e.key, e.role, e.difficulty, area, e.nearPlayer)
        } catch (x: VirtualMachineError) {
            throw x
        } catch (x: Throwable) {
            log("Bot spawn threw for session ${e.key}; treated as a failed spawn", x)
            null
        }
        if (id == null) {
            releaseSlot()
            val n = streak.record(e.key, now)
            when (val k = e.key) {
                is SessionKey.Trainer -> trainers[k.owner]?.let { s ->
                    s.spawnFailed(now)
                    if (n >= MAX_FAILURES) {
                        work.addAll(giveUpTrainer(s, n))
                        return
                    }
                }
                is SessionKey.Squad -> squads[k]?.let { s ->
                    s.spawnFailed(now)
                    if (n >= MAX_FAILURES) {
                        log("Ally squad ${k.world}/${k.regionId}: stopped refilling a slot after $n consecutive spawn failures", null)
                        return
                    }
                }
            }
            retries += Retry(now + RETRY_MS, e.key)
            return
        }
        alive[id] = e.key
        val follow = when (val k = e.key) {
            is SessionKey.Trainer -> trainers[k.owner]?.botSpawned(id, now)
            is SessionKey.Squad -> squads[k]?.botSpawned(id, now)
        }
        work.addAll(follow ?: listOf(Effect.RemoveBot(id)))
    }

    private fun giveUpTrainer(s: TrainerSession, n: Int): List<Effect> {
        val owner = (s.key as SessionKey.Trainer).owner
        log("Trainer session of player $owner ended after $n consecutive bot failures; it starts again when the player re-enters the region", null)
        return s.ownerLeft()
    }

    private inline fun guarded(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            log("Bot director: $what failed", e)
        }
    }

    companion object {
        const val RETRY_MS = 5_000L
        const val BUSY_RESEND_MS = 2_000L
        const val MAX_FAILURES = 3
    }
}
