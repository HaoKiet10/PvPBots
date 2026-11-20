package pvpbots.team

import pvpbots.session.AreaRef
import pvpbots.session.Effect
import pvpbots.session.Messages
import pvpbots.session.SessionKey
import pvpbots.session.Timings
import pvpbots.skill.Outcome
import java.util.UUID

/**
 * A team arena's shared round: split -> wait for filler-bot slots -> countdown -> fight -> rematch wait -> next
 * split with everyone present. Pure: effects out, time passed in (same style as `TrainerSession`).
 *
 * Input API (the director calls these):
 *  - [start]: the players inside at the first round (>= 1); splits them and requests slots for the filler bots.
 *  - [slotGranted]: one requested slot arrived; emits one [Effect.SpawnTeamBot], or nothing when no filler is
 *    needed (the director must then release the slot itself).
 *  - [botSpawned]: that bot exists. When every filler exists, players are placed and the countdown starts.
 *    A bot nobody wants (wrong state, side already full) comes back as [Effect.RemoveBot].
 *  - [spawnFailed]: the spawn failed; the slot is given back and one new slot is requested.
 *  - [playerLeft], [playerDied], [playerEntered], [botDied]: arena events. A player who died or walked in
 *    mid-round waits for the next round ([Effect.Wait]); players who waited are seated first. A filler lost before
 *    the fight (died, or dropped after a failure) is replaced like a failed spawn; in COUNTDOWN the round goes back
 *    to WAITING_SLOTS, so a fight never starts a bot short.
 *  - [tick]: drives countdown, time limit, slot shrinking and the rematch delay. Every [SHRINK_TIMEOUT_MS] without
 *    all fillers, the round shrinks to the largest even size its granted slots (spawned or still pending) allow;
 *    only queued requests are cancelled ([Effect.CancelQueuedSlots]), pending spawns go on.
 *
 * Results: when a round that reached FIGHTING ends, every seated player who did not leave (dead ones included) gets
 * [Effect.TeamResult]; players still online get a title. Leavers get no result. A round that never fought, or whose players all left,
 * ends with no result. When fewer than two players remain for the next round (or none), the round goes ENDED; the director takes over.
 */
class TeamRound(val key: SessionKey.Team, area: AreaRef, private val t: Timings) {
    enum class State { WAITING_SLOTS, COUNTDOWN, FIGHTING, REMATCH_WAIT, ENDED }

    companion object {
        /** How long to wait for all filler slots before shrinking the round to what fits. */
        const val SHRINK_TIMEOUT_MS = 10_000L
        private const val DEFAULT_SEATS = 3
    }

    var area: AreaRef = area
        private set

    private var state = State.WAITING_SLOTS
    private var started = false
    private var phaseStartMs = 0L

    private val ratings = LinkedHashMap<UUID, Double>()
    private val waiting = ArrayList<UUID>()
    private val team = mutableMapOf(Side.A to listOf<UUID>(), Side.B to listOf())
    private val botRating = mutableMapOf(Side.A to 0.0, Side.B to 0.0)

    /** Seated players still alive and in the arena. */
    private val inArena = LinkedHashSet<UUID>()
    /** Seated players who left the arena (no title for them). */
    private val gone = HashSet<UUID>()

    private val want = mutableMapOf(Side.A to 0, Side.B to 0)
    /**
     * Slots granted per side and still held by this round (live fillers plus spawns still pending); never below the
     * live fillers. Reset to the live fillers when the countdown starts: a spawn still pending then is dropped.
     */
    private val requested = mutableMapOf(Side.A to 0, Side.B to 0)
    private val bots = mutableMapOf(Side.A to ArrayList<UUID>(), Side.B to ArrayList<UUID>())
    private var shrinkDueMs = 0L

    fun state(): State = state
    fun seated(): List<UUID> = team.getValue(Side.A) + team.getValue(Side.B)
    fun waiting(): List<UUID> = waiting.toList()
    fun aliveBots(): List<UUID> = bots.values.flatten()

    /** The side of a seated player still in the arena, or of a live filler bot; null for anyone else. */
    fun sideOf(id: UUID): Side? {
        if (state == State.ENDED) return null
        for (side in Side.entries) {
            if (id in bots.getValue(side) || (id in inArena && id in team.getValue(side))) return side
        }
        return null
    }

    /** Everyone (players in the arena, live bots) on the side opposing [side]. */
    fun foesOf(side: Side): List<UUID> {
        val other = if (side == Side.A) Side.B else Side.A
        return team.getValue(other).filter { it in inArena } + bots.getValue(other)
    }

    /** Players the next round would seat: those still in the arena plus the ones waiting. */
    fun players(): List<UUID> = (inArena + waiting).distinct()

    fun areaChanged(to: AreaRef) {
        area = to
    }

    private val seats get() = area.seats ?: DEFAULT_SEATS

    fun start(players: List<UUID>, ratings: Map<UUID, Double>, nowMs: Long): List<Effect> {
        if (started || state == State.ENDED) return emptyList()
        started = true
        players.forEach { this.ratings[it] = ratings[it] ?: 0.0 }
        return beginRound(players, emptyList(), nowMs)
    }

    fun slotGranted(nowMs: Long): List<Effect> {
        if (state != State.WAITING_SLOTS) return emptyList()
        val open = Side.entries.filter { requested.getValue(it) < want.getValue(it) }
        val side = open.minWithOrNull(compareBy({ team.getValue(it).size + requested.getValue(it) }, { it.ordinal }))
            ?: return emptyList()
        val pad = team.getValue(side).size + requested.getValue(side)
        requested[side] = requested.getValue(side) + 1
        return listOf(Effect.SpawnTeamBot(key, side, botRating.getValue(side), pad))
    }

    fun botSpawned(side: Side, botId: UUID, nowMs: Long): List<Effect> {
        if (state != State.WAITING_SLOTS || bots.getValue(side).size >= want.getValue(side)) {
            // A surplus filler (side already full, e.g. after a shrink): its slot no longer counts for this round.
            if (state == State.WAITING_SLOTS || state == State.COUNTDOWN) dropRequest(side)
            return listOf(Effect.RemoveBot(botId))
        }
        bots.getValue(side) += botId
        if (requested.getValue(side) < bots.getValue(side).size) requested[side] = bots.getValue(side).size
        return if (allSpawned()) startCountdown(nowMs) else emptyList()
    }

    fun spawnFailed(side: Side, nowMs: Long): List<Effect> {
        if (state != State.WAITING_SLOTS) return emptyList()
        dropRequest(side)
        return listOf(Effect.RequestSlots(key, 1))
    }

    private fun dropRequest(side: Side) {
        requested[side] = maxOf(bots.getValue(side).size, requested.getValue(side) - 1)
    }

    fun playerLeft(player: UUID, nowMs: Long): List<Effect> {
        if (state == State.ENDED) return emptyList()
        waiting.remove(player)
        ratings.remove(player)
        val wasIn = inArena.remove(player)
        // Only a player who leaves while alive is a leaver; one who died first (and respawned outside) keeps their result.
        if (wasIn && player in seated()) gone += player
        val active = state == State.WAITING_SLOTS || state == State.COUNTDOWN || state == State.FIGHTING
        return when {
            wasIn && active && inArena.isEmpty() -> abort(nowMs)
            wasIn && state == State.FIGHTING -> checkEnd(nowMs)
            else -> endIfEmptyRematch()
        }
    }

    fun playerDied(player: UUID, nowMs: Long): List<Effect> {
        if (state == State.ENDED) return emptyList()
        waiting.remove(player)
        if (!inArena.remove(player)) return endIfEmptyRematch()
        if ((state == State.WAITING_SLOTS || state == State.COUNTDOWN) && inArena.isEmpty()) return abort(nowMs)
        return if (state == State.FIGHTING) checkEnd(nowMs) else endIfEmptyRematch()
    }

    /** [player] walked in (or back in). Not part of this round: waits for the next one. */
    fun playerEntered(player: UUID, rating: Double, nowMs: Long): List<Effect> {
        if (state == State.ENDED || player in inArena || player in waiting) return emptyList()
        ratings[player] = rating
        waiting += player
        return listOf(Effect.Wait(player))
    }

    /** A filler died or was dropped after a failure. Before the fight it is replaced (see the class doc). */
    fun botDied(botId: UUID, nowMs: Long): List<Effect> {
        val side = Side.entries.firstOrNull { botId in bots.getValue(it) } ?: return emptyList()
        bots.getValue(side).remove(botId)
        return when (state) {
            State.FIGHTING -> checkEnd(nowMs)
            State.WAITING_SLOTS, State.COUNTDOWN -> {
                if (state == State.COUNTDOWN) {
                    state = State.WAITING_SLOTS
                    phaseStartMs = nowMs
                    shrinkDueMs = nowMs + SHRINK_TIMEOUT_MS
                }
                dropRequest(side)
                listOf(Effect.RequestSlots(key, 1))
            }
            else -> emptyList()
        }
    }

    fun tick(nowMs: Long): List<Effect> = when (state) {
        State.WAITING_SLOTS -> shrinkIfDue(nowMs)
        State.COUNTDOWN -> {
            if (nowMs - phaseStartMs >= t.countdownS * 1000L) {
                state = State.FIGHTING
                phaseStartMs = nowMs
                checkEnd(nowMs)
            } else emptyList()
        }
        State.FIGHTING ->
            if (nowMs - phaseStartMs >= t.timeLimitS * 1000L) finish(nowMs, Messages.DRAW, Outcome.DRAW, null) else emptyList()
        State.REMATCH_WAIT ->
            if (nowMs - phaseStartMs >= t.rematchDelayS * 1000L) nextRound(nowMs) else emptyList()
        State.ENDED -> emptyList()
    }

    // ---- internals ----

    private fun beginRound(pool: List<UUID>, waitingFirst: List<UUID>, nowMs: Long): List<Effect> {
        val split = TeamSplit.split(pool.associateWith { ratings[it] ?: 0.0 }, seats, waitingFirst)
        team[Side.A] = split.a
        team[Side.B] = split.b
        botRating[Side.A] = split.botRating.getValue(Side.A)
        botRating[Side.B] = split.botRating.getValue(Side.B)
        waiting.clear()
        waiting += split.waiting
        inArena.clear()
        inArena += split.a + split.b
        gone.clear()
        Side.entries.forEach {
            want[it] = seats - team.getValue(it).size
            requested[it] = 0
            bots.getValue(it).clear()
        }
        state = State.WAITING_SLOTS
        phaseStartMs = nowMs
        shrinkDueMs = nowMs + SHRINK_TIMEOUT_MS
        val out = mutableListOf<Effect>()
        split.waiting.forEach { out += Effect.Wait(it) }
        val fillers = want.values.sum()
        if (fillers > 0) out += Effect.RequestSlots(key, fillers) else out += startCountdown(nowMs)
        return out
    }

    private fun allSpawned() = Side.entries.all { bots.getValue(it).size >= want.getValue(it) }

    private fun startCountdown(nowMs: Long): List<Effect> {
        state = State.COUNTDOWN
        phaseStartMs = nowMs
        // Spawns still pending now are surplus: the director drops them (no spawn outside WAITING_SLOTS).
        Side.entries.forEach { requested[it] = bots.getValue(it).size }
        return Side.entries.flatMap { side ->
            team.getValue(side).filter { it in inArena }.mapIndexed { pad, p -> Effect.PlacePlayer(p, side, pad) }
        }
    }

    /**
     * Every [SHRINK_TIMEOUT_MS] without all fillers: the largest per-side size the slots each side holds (live fillers
     * plus spawns still pending) allow. Only queued requests are cancelled; a pending spawn the smaller round does not
     * need is removed when it arrives ([botSpawned]). Repeats while fillers are still missing (a filler that fails
     * after a shrink is re-requested; if that slot never comes, the next shrink drops it), down to players only.
     */
    private fun shrinkIfDue(nowMs: Long): List<Effect> {
        if (nowMs < shrinkDueMs) return emptyList()
        shrinkDueMs = nowMs + SHRINK_TIMEOUT_MS
        val p = Side.entries.associateWith { team.getValue(it).size }
        val s = (seats downTo p.values.max()).firstOrNull { s -> Side.entries.all { s - p.getValue(it) <= requested.getValue(it) } }
            ?: return emptyList()
        Side.entries.forEach { want[it] = minOf(want.getValue(it), s - p.getValue(it)) }
        val out = mutableListOf<Effect>(Effect.CancelQueuedSlots(key))
        for (side in Side.entries) {
            val list = bots.getValue(side)
            while (list.size > want.getValue(side)) {
                out += Effect.RemoveBot(list.removeAt(list.lastIndex))
                dropRequest(side)
            }
        }
        if (allSpawned()) out += startCountdown(nowMs)
        return out
    }

    private fun sideAlive(side: Side) =
        team.getValue(side).count { it in inArena } + bots.getValue(side).size

    private fun checkEnd(nowMs: Long): List<Effect> {
        val a = sideAlive(Side.A)
        val b = sideAlive(Side.B)
        return when {
            a > 0 && b > 0 -> emptyList()
            a == 0 && b == 0 -> finish(nowMs, Messages.DRAW, Outcome.DRAW, null)
            b == 0 -> finish(nowMs, null, null, Side.A)
            else -> finish(nowMs, null, null, Side.B)
        }
    }

    /** Ends the fight. [winner] null with a [drawTitle] = draw. */
    private fun finish(nowMs: Long, drawTitle: String?, drawOutcome: Outcome?, winner: Side?): List<Effect> {
        if (state != State.FIGHTING) return emptyList()
        val out = mutableListOf<Effect>()
        aliveBots().forEach { out += Effect.RemoveBot(it) }
        bots.values.forEach { it.clear() }
        val online = seated().filter { it !in gone }
        for (p in online) {
            val text = when {
                winner == null -> drawTitle!!
                p in team.getValue(winner) -> Messages.TEAM_WON
                else -> Messages.TEAM_LOST
            }
            out += Effect.Title(p, text)
        }
        for (p in seated().filter { it !in gone }) {
            val outcome = when {
                winner == null -> drawOutcome!!
                p in team.getValue(winner) -> Outcome.PLAYER_WON
                else -> Outcome.BOT_WON
            }
            out += Effect.TeamResult(p, outcome)
        }
        toRematch(nowMs)
        return out
    }

    /** Every seated player left: the round ends without a result; bots and requests are cleaned up. */
    private fun abort(nowMs: Long): List<Effect> {
        val out = mutableListOf<Effect>(Effect.CancelSlotRequests(key))
        aliveBots().forEach { out += Effect.RemoveBot(it) }
        bots.values.forEach { it.clear() }
        toRematch(nowMs)
        return out
    }

    private fun toRematch(nowMs: Long) {
        state = State.REMATCH_WAIT
        phaseStartMs = nowMs
        endIfEmptyRematch()
    }

    /** Nobody left for the next round: the round is over. */
    private fun endIfEmptyRematch(): List<Effect> {
        if (state == State.REMATCH_WAIT && players().isEmpty()) state = State.ENDED
        return emptyList()
    }

    private fun nextRound(nowMs: Long): List<Effect> {
        val pool = players()
        if (pool.size < 2) {
            state = State.ENDED
            return listOf(Effect.CancelSlotRequests(key))
        }
        return beginRound(pool, waiting.toList(), nowMs)
    }
}
