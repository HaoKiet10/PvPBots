package pvpbots.session

import pvpbots.slots.BotSlots
import pvpbots.team.Side
import pvpbots.team.TeamRound
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
 * refilling the failing slot; a team round is dropped with no result (bots removed, slots released) and its players get no
 * session until they re-enter the region.
 *
 * Team arenas (an area with `seats != null`, role TRAINER; TEAM and BOSS regions start nothing): the first player gets a [TrainerSession]; when a second
 * enters, that duel ends with NO_RESULT and a [TeamRound] takes over. A round that ends with one player left hands that
 * player back to a trainer session.
 */
class AreaDirector(
    private val slots: BotSlots,
    private val port: BotPort,
    private val t: Timings,
    private val spawnsPerTick: Int,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private class Grant(val key: SessionKey)
    private class DoSpawn(val e: Effect)
    private class Retry(val dueMs: Long, val key: SessionKey)
    private object FreeSlot

    private val trainers = HashMap<UUID, TrainerSession>()
    private val squads = HashMap<SessionKey.Squad, AllySquad>()
    private val areas = HashMap<SessionKey, AreaRef>()
    private val playerArea = HashMap<UUID, AreaRef>()
    private val alive = HashMap<UUID, SessionKey>()
    private val retries = ArrayList<Retry>()
    private val teams = HashMap<SessionKey.Team, TeamRound>()
    private val pending = ArrayDeque<Effect>()
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
        teamOf(player)?.let { (key, r) ->
            run(r.playerDied(player, nowMs), nowMs)
            afterTeam(key, nowMs)
            return
        }
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
        teamOf(player)?.let { (_, r) -> run(r.playerEntered(player, ratingOf(player), nowMs), nowMs) }
    }

    fun onBotDeath(botId: UUID, nowMs: Long) {
        if (shutDown) return
        val key = alive[botId] ?: return
        streak.reset(key)
        val effects = when (key) {
            is SessionKey.Trainer -> trainers[key.owner]?.takeIf { it.botId() == botId }?.botDied(nowMs).orEmpty()
            is SessionKey.Squad -> squads[key]?.allyDied(botId, nowMs).orEmpty()
            is SessionKey.Team -> teams[key]?.botDied(botId, nowMs).orEmpty()
            is SessionKey.Boss -> emptyList()
        }
        run(effects, nowMs)
        // Squad deaths emit no RemoveBot; make sure the dead bot's slot is freed either way.
        if (botId in alive) run(listOf(Effect.RemoveBot(botId)), nowMs)
        if (key is SessionKey.Team) afterTeam(key, nowMs)
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
            is SessionKey.Team -> teams[key]?.let { r ->
                if (n >= MAX_FAILURES) giveUpTeam(key, n) else r.botDied(botId, nowMs)
            }.orEmpty()
            is SessionKey.Boss -> emptyList()
        }
        run(effects, nowMs)
        if (botId in alive) run(listOf(Effect.RemoveBot(botId)), nowMs)
        if (key is SessionKey.Team) afterTeam(key, nowMs)
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
        for ((k, r) in teams.entries.toList()) {
            run(r.tick(nowMs), nowMs)
            afterTeam(k, nowMs)
        }
        val due = retries.filter { it.dueMs <= nowMs }
        retries.removeAll(due.toSet())
        for (r in due) {
            if (sessionActive(r.key) && (r.key !is SessionKey.Team || teams[r.key]?.state() == TeamRound.State.WAITING_SLOTS)) run(listOf(Effect.RequestSlots(r.key, 1)), nowMs)
        }
        resendBusy(nowMs)
        spawnPending(nowMs)
        trainers.values.removeIf { if (it.state() == TrainerSession.State.ENDED) { forget(it.key); true } else false }
        squads.values.removeIf { if (it.state() == AllySquad.State.ENDED) { forget(it.key); true } else false }
    }

    /**
     * A slot taken outside this director (a boss: [BotSlots.request] while a slot was free and nothing queued) is
     * free again; queued sessions get it as if one of their own bots had been removed.
     */
    fun externalSlotFreed(nowMs: Long) {
        if (shutDown) {
            slots.release(1)
            return
        }
        run(listOf(FreeSlot), nowMs)
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
        teams.keys.forEach { effects += Effect.CancelSlotRequests(it) }
        alive.keys.toList().forEach { effects += Effect.RemoveBot(it) }
        run(effects, now)
        slots.clear()
        trainers.clear(); squads.clear(); teams.clear(); areas.clear(); playerArea.clear(); alive.clear(); retries.clear()
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

    /** Live team rounds, for status output. */
    fun teamCount(): Int = teams.size

    /** Granted spawns waiting for their turn (rate limit) or for a dead owner to respawn, for status output. */
    fun pendingSpawns(): Int = pending.size + held.values.sumOf { it.size }

    /** (arena = region id, side) of a player or bot seated in a running team round; null for anyone else. Read-only. */
    fun teamSideOf(id: UUID): Pair<String, Side>? {
        for ((k, r) in teams) r.sideOf(id)?.let { return k.regionId to it }
        return null
    }

    /** Everyone the team-round bot [botId] may fight: the other side's players in the arena and live bots. */
    fun teamFoes(botId: UUID): List<UUID> {
        val key = alive[botId] as? SessionKey.Team ?: return emptyList()
        val r = teams[key] ?: return emptyList()
        return r.sideOf(botId)?.let { r.foesOf(it) }.orEmpty()
    }

    fun fighting(botId: UUID): Boolean = when (val key = alive[botId]) {
        is SessionKey.Squad -> true
        is SessionKey.Team -> teams[key]?.state() == TeamRound.State.FIGHTING
        is SessionKey.Trainer -> trainers[key.owner]?.fighting() == true
        is SessionKey.Boss, null -> false
    }

    // ---- area transitions ----

    private fun isTeamArena(a: AreaRef) = a.seats != null && a.role == Role.TRAINER

    private fun teamKey(a: AreaRef) = SessionKey.Team(a.world, a.regionId)

    /** The live team round the player is standing in, if any. */
    private fun teamOf(player: UUID): Pair<SessionKey.Team, TeamRound>? {
        val a = playerArea[player]?.takeIf { isTeamArena(it) } ?: return null
        val k = teamKey(a)
        return teams[k]?.let { k to it }
    }

    private fun ratingOf(player: UUID): Double = try {
        port.rating(player)
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Throwable) {
        log("Bot director: rating($player) failed", e)
        50.0
    }

    private fun leave(player: UUID, from: AreaRef, nowMs: Long) {
        if (isTeamArena(from)) {
            val k = teamKey(from)
            val r = teams[k]
            if (r != null) {
                run(r.playerLeft(player, nowMs), nowMs)
                afterTeam(k, nowMs)
                return
            }
        }
        when (from.role) {
            Role.TRAINER -> trainers.remove(player)?.let {
                run(it.ownerLeft(), nowMs)
                forget(it.key)
            }
            Role.ALLY -> squads[SessionKey.Squad(from.world, from.regionId)]?.let { run(it.playerLeft(player, nowMs), nowMs) }
            Role.TEAM, Role.BOSS -> Unit // wired in v3
        }
    }

    private fun startTrainer(player: UUID, to: AreaRef, nowMs: Long) {
        if (trainers.containsKey(player)) return
        val s = TrainerSession(player, to, t)
        trainers[player] = s
        areas[s.key] = to
        streak.reset(s.key)
        run(s.start(nowMs), nowMs)
    }

    private fun enterTeamArena(player: UUID, to: AreaRef, nowMs: Long) {
        val key = teamKey(to)
        val round = teams[key]
        if (round != null) {
            run(round.playerEntered(player, ratingOf(player), nowMs), nowMs)
            return
        }
        val first = trainers.values.firstOrNull {
            it.state() != TrainerSession.State.ENDED && it.area.world == to.world && it.area.regionId == to.regionId &&
                (it.key as SessionKey.Trainer).owner != player
        }
        if (first == null) {
            startTrainer(player, to, nowMs)
            return
        }
        // 1v1 -> team: the duel ends without a result and both players go into a team round.
        val owner = (first.key as SessionKey.Trainer).owner
        trainers.remove(owner)
        run(first.endForTeam(), nowMs)
        forget(first.key)
        val r = TeamRound(key, to, t)
        teams[key] = r
        areas[key] = to
        streak.reset(key)
        val pair = listOf(owner, player)
        run(r.start(pair, pair.associateWith { ratingOf(it) }, nowMs), nowMs)
        pair.filter { it in dead }.forEach { run(r.playerDied(it, nowMs), nowMs) }
        afterTeam(key, nowMs)
    }

    /** Drops a finished round; its one remaining player (if any) goes back to a trainer session. */
    private fun afterTeam(key: SessionKey.Team, nowMs: Long) {
        val r = teams[key] ?: return
        if (r.state() != TeamRound.State.ENDED) return
        teams.remove(key)
        forget(key)
        for (p in r.players()) {
            val a = playerArea[p]
            if (a != null && isTeamArena(a) && teamKey(a) == key) startTrainer(p, a, nowMs)
        }
    }

    /** After [MAX_FAILURES] failures in a row: the round is dropped with no result; bots go, slots are released. */
    private fun giveUpTeam(key: SessionKey.Team, n: Int): List<Effect> {
        val r = teams.remove(key) ?: return emptyList()
        log("Team round at ${key.world}/${key.regionId} dropped after $n consecutive bot failures; it starts again when a player re-enters the region", null)
        forget(key)
        return listOf<Effect>(Effect.CancelSlotRequests(key)) + r.aliveBots().map { Effect.RemoveBot(it) } +
            r.players().map { Effect.ActionBar(it, Messages.TEAM_UNAVAILABLE) }
    }

    private fun enter(player: UUID, to: AreaRef, nowMs: Long) {
        if (isTeamArena(to)) {
            enterTeamArena(player, to, nowMs)
            return
        }
        when (to.role) {
            Role.TRAINER -> startTrainer(player, to, nowMs)
            Role.ALLY -> {
                val key = SessionKey.Squad(to.world, to.regionId)
                val s = squads[key]?.takeIf { it.state() != AllySquad.State.ENDED }
                    ?: AllySquad(to, t).also { squads[key] = it; areas[key] = to; streak.reset(key) }
                run(s.playerEntered(player, nowMs), nowMs)
            }
            Role.TEAM, Role.BOSS -> Unit // wired in v3
        }
    }

    private fun updateArea(player: UUID, to: AreaRef) {
        when (to.role) {
            Role.TRAINER -> {
                trainers[player]?.let { s ->
                    s.areaChanged(to)
                    areas[s.key] = to
                }
                teams[teamKey(to)]?.let { r -> r.areaChanged(to); areas[r.key] = to }
            }
            Role.ALLY -> SessionKey.Squad(to.world, to.regionId).let { k -> if (k in squads) areas[k] = to }
            Role.TEAM, Role.BOSS -> Unit // wired in v3
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
        is SessionKey.Team -> teams[key]?.let { it.state() != TeamRound.State.ENDED } == true
        is SessionKey.Boss -> false
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
                        FreeSlot -> releaseSlot()
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
            is SessionKey.Team -> teams[key]?.slotGranted(now)
            is SessionKey.Boss -> null // never queued here (the boss director only takes a slot that is free right away)
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
            is Effect.SpawnTeamBot -> pending.addLast(e)
            is Effect.PlacePlayer -> {
                val a = playerArea[e.player] ?: teams.values.firstOrNull { e.player in it.seated() }?.area
                if (a != null) guarded("placing ${e.player}") { port.placePlayer(e.player, a, e.side, e.pad) }
            }
            is Effect.TeamResult -> {
                teamOf(e.player)?.let { (k, _) -> streak.reset(k) }
                guarded("team result for ${e.player}") { port.teamResult(e.player, e.outcome) }
            }
            is Effect.Wait -> guarded("wait message for ${e.player}") { port.actionBar(e.player, Messages.WAIT) }
        }
    }

    /** Pending (not yet spawned) and held spawns of [key] are dropped; their reserved slots are released. */
    private fun dropPending(key: SessionKey) {
        var n = 0
        val it = pending.iterator()
        while (it.hasNext()) if (spawnKey(it.next()) == key) { it.remove(); n++ }
        if (key is SessionKey.Trainer) held.remove(key.owner)?.let { n += it.size }
        repeat(n) { releaseSlot() }
    }

    private fun spawnKey(e: Effect): SessionKey = when (e) {
        is Effect.SpawnBot -> e.key
        is Effect.SpawnTeamBot -> e.key
        else -> error("not a spawn effect: $e")
    }

    private fun spawnPending(nowMs: Long) {
        var budget = spawnsPerTick
        while (budget > 0 && pending.isNotEmpty() && !shutDown) {
            val e = pending.removeFirst()
            val k = spawnKey(e)
            if (e is Effect.SpawnBot && k is SessionKey.Trainer && ownerDead(k.owner)) {
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

    private fun spawn(e: Effect) {
        val key = spawnKey(e)
        val area = areas[key]
        // Session vanished (or a team round no longer waiting for fillers); the granted slot is unused.
        if (area == null || !sessionActive(key) || (key is SessionKey.Team && teams[key]?.state() != TeamRound.State.WAITING_SLOTS)) {
            releaseSlot()
            return
        }
        val id = try {
            when (e) {
                is Effect.SpawnBot -> port.spawn(e.key, e.role, e.difficulty, area, e.nearPlayer)
                is Effect.SpawnTeamBot -> port.spawnTeamBot(e.key, e.side, e.rating, area, e.pad)
                else -> null
            }
        } catch (x: VirtualMachineError) {
            throw x
        } catch (x: Throwable) {
            log("Bot spawn threw for session $key; treated as a failed spawn", x)
            null
        }
        if (id == null) {
            releaseSlot()
            val n = streak.record(key, now)
            when (key) {
                is SessionKey.Trainer -> trainers[key.owner]?.let { s ->
                    s.spawnFailed(now)
                    if (n >= MAX_FAILURES) {
                        work.addAll(giveUpTrainer(s, n))
                        return
                    }
                }
                is SessionKey.Squad -> squads[key]?.let { s ->
                    s.spawnFailed(now)
                    if (n >= MAX_FAILURES) {
                        log("Ally squad ${key.world}/${key.regionId}: stopped refilling a slot after $n consecutive spawn failures", null)
                        return
                    }
                }
                is SessionKey.Team -> teams[key]?.let { r ->
                    r.spawnFailed((e as Effect.SpawnTeamBot).side, now) // its re-request is replaced by the delayed retry below
                    if (n >= MAX_FAILURES) {
                        work.addAll(giveUpTeam(key, n))
                        return
                    }
                }
                is SessionKey.Boss -> Unit
            }
            retries += Retry(now + RETRY_MS, key)
            return
        }
        alive[id] = key
        val follow = when (key) {
            is SessionKey.Trainer -> trainers[key.owner]?.botSpawned(id, now)
            is SessionKey.Squad -> squads[key]?.botSpawned(id, now)
            is SessionKey.Team -> teams[key]?.botSpawned((e as Effect.SpawnTeamBot).side, id, now)
            is SessionKey.Boss -> null
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
