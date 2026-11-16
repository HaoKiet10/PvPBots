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
 */
class AreaDirector(private val slots: BotSlots, private val port: BotPort, private val t: Timings) {
    private class Grant(val key: SessionKey)
    private class Retry(val dueMs: Long, val key: SessionKey)

    private val trainers = HashMap<UUID, TrainerSession>()
    private val squads = HashMap<SessionKey.Squad, AllySquad>()
    private val areas = HashMap<SessionKey, AreaRef>()
    private val playerArea = HashMap<UUID, AreaRef>()
    private val alive = HashMap<UUID, SessionKey>()
    private val retries = ArrayList<Retry>()

    private val work = ArrayDeque<Any>()
    private var draining = false
    private var grantsEnabled = true
    private var shutDown = false
    private var now = 0L

    fun onAreaChange(player: UUID, from: AreaRef?, to: AreaRef?, nowMs: Long) {
        if (shutDown || port.isBot(player)) return
        if (from != null && to != null && from.world == to.world && from.regionId == to.regionId) return
        if (from != null) leave(player, from, nowMs)
        if (to != null) enter(player, to, nowMs)
        if (to != null) playerArea[player] = to else playerArea.remove(player)
    }

    fun onPlayerDeath(player: UUID, nowMs: Long) {
        if (shutDown) return
        val s = trainers[player] ?: return
        run(s.ownerDied(nowMs), nowMs)
    }

    fun onBotDeath(botId: UUID, nowMs: Long) {
        if (shutDown) return
        val key = alive[botId] ?: return
        val effects = when (key) {
            is SessionKey.Trainer -> trainers[key.owner]?.takeIf { it.botId() == botId }?.botDied(nowMs).orEmpty()
            is SessionKey.Squad -> squads[key]?.allyDied(botId, nowMs).orEmpty()
        }
        run(effects, nowMs)
        // Squad deaths emit no RemoveBot; make sure the dead bot's slot is freed either way.
        if (botId in alive) run(listOf(Effect.RemoveBot(botId)), nowMs)
    }

    fun onQuit(player: UUID, nowMs: Long) {
        if (shutDown) return
        val area = playerArea.remove(player)
        if (area != null) leave(player, area, nowMs)
        // Safety net: a trainer without a remembered area.
        trainers.remove(player)?.let { run(it.ownerLeft(), nowMs); areas.remove(it.key) }
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
        trainers.values.removeIf { if (it.state() == TrainerSession.State.ENDED) { areas.remove(it.key); true } else false }
        squads.values.removeIf { if (it.state() == AllySquad.State.ENDED) { areas.remove(it.key); true } else false }
    }

    /** Ends everything (all bots removed), clears the slot queue, and turns every later call into a no-op. */
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
        shutDown = true
    }

    fun trainerFor(owner: UUID): TrainerSession? = trainers[owner]

    fun squadAt(world: String, regionId: String): AllySquad? = squads[SessionKey.Squad(world, regionId)]

    fun sessionOfBot(botId: UUID): SessionKey? = alive[botId]

    /** Live trainer sessions (any state but ENDED-and-pruned), for status output. */
    fun trainerCount(): Int = trainers.size

    /** Live ally squads, for status output. */
    fun squadCount(): Int = squads.size

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
                areas.remove(it.key)
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
                run(s.start(nowMs), nowMs)
            }
            Role.ALLY -> {
                val key = SessionKey.Squad(to.world, to.regionId)
                val s = squads[key]?.takeIf { it.state() != AllySquad.State.ENDED }
                    ?: AllySquad(to, t).also { squads[key] = it; areas[key] = to }
                run(s.playerEntered(player, nowMs), nowMs)
            }
        }
    }

    // ---- effect execution ----

    private fun sessionActive(key: SessionKey): Boolean = when (key) {
        is SessionKey.Trainer -> trainers[key.owner]?.let { it.state() != TrainerSession.State.ENDED } == true
        is SessionKey.Squad -> squads[key]?.let { it.state() != AllySquad.State.ENDED } == true
    }

    private fun run(effects: List<Effect>, nowMs: Long) {
        now = nowMs
        work.addAll(effects)
        if (draining) return
        draining = true
        try {
            while (work.isNotEmpty()) {
                when (val w = work.removeFirst()) {
                    is Grant -> grant(w.key)
                    is Effect -> execute(w)
                }
            }
        } finally {
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
                    trainers[k.owner]?.let { work.addAll(it.queued()) }
                }
            }
            is Effect.CancelSlotRequests -> slots.cancel(e.key)
            is Effect.SpawnBot -> spawn(e)
            is Effect.RemoveBot -> {
                port.remove(e.botId)
                if (alive.remove(e.botId) != null) releaseSlot()
            }
            is Effect.Title -> port.title(e.player, e.text)
            is Effect.ActionBar -> port.actionBar(e.player, e.text)
        }
    }

    private fun spawn(e: Effect.SpawnBot) {
        val area = areas[e.key]
        if (area == null) { // session vanished; the granted slot is unused
            releaseSlot()
            return
        }
        val id = port.spawn(e.key, e.role, e.difficulty, area, e.nearPlayer)
        if (id == null) {
            releaseSlot()
            when (val k = e.key) {
                is SessionKey.Trainer -> trainers[k.owner]?.spawnFailed(now)
                is SessionKey.Squad -> squads[k]?.spawnFailed(now)
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

    private companion object {
        const val RETRY_MS = 5_000L
    }
}
