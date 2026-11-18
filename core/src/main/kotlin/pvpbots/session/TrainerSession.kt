package pvpbots.session

import pvpbots.skill.Outcome
import java.util.UUID

/** One player's personal 1v1 duel: queue -> countdown -> fight -> rematch wait. Pure; time is passed in. */
class TrainerSession(private val owner: UUID, area: AreaRef, private val t: Timings) {
    enum class State { QUEUED, COUNTDOWN, FIGHTING, REMATCH_WAIT, ENDED }

    val key: SessionKey = SessionKey.Trainer(owner)
    private var state = State.QUEUED
    private var bot: UUID? = null
    private var phaseStartMs = 0L
    private var started = false
    private var spawnPending = false

    /** The area as last reported (same region; an admin may change its kit or flags mid-session). */
    var area: AreaRef = area
        private set

    /** Same region, new details: the next round's spawn uses them. */
    fun areaChanged(to: AreaRef) {
        area = to
    }

    fun state(): State = state
    fun botId(): UUID? = bot
    fun fighting(): Boolean = state == State.FIGHTING

    fun start(nowMs: Long): List<Effect> {
        if (started || state == State.ENDED) return emptyList()
        started = true
        return listOf(Effect.RequestSlots(key, 1))
    }

    fun queued(): List<Effect> =
        if (state == State.QUEUED) listOf(Effect.ActionBar(owner, Messages.BUSY)) else emptyList()

    /**
     * A requested slot was granted. Emits a single [Effect.SpawnBot] only when QUEUED with no spawn
     * already pending; otherwise (duplicate grant, wrong state, ENDED) returns empty and the director
     * must release that slot itself.
     */
    fun slotGranted(nowMs: Long): List<Effect> {
        if (state != State.QUEUED || spawnPending) return emptyList()
        spawnPending = true
        return listOf(Effect.SpawnBot(key, Role.TRAINER, area.difficulty, owner))
    }

    /**
     * The bot requested by [slotGranted] now exists. Accepted (-> COUNTDOWN) only when QUEUED with a
     * spawn pending. In any other case (including ENDED) the bot is unwanted: returns
     * `[Effect.RemoveBot(botId)]` as cleanup and changes nothing else.
     */
    fun botSpawned(botId: UUID, nowMs: Long): List<Effect> {
        if (state != State.QUEUED || !spawnPending) return listOf(Effect.RemoveBot(botId))
        spawnPending = false
        bot = botId
        phaseStartMs = nowMs
        state = State.COUNTDOWN
        return emptyList()
    }

    /**
     * The spawn requested by [slotGranted] failed. Clears the pending-spawn flag, stays QUEUED and
     * returns empty; the director releases the slot and later re-requests one.
     */
    fun spawnFailed(nowMs: Long): List<Effect> {
        spawnPending = false
        return emptyList()
    }

    fun ownerLeft(): List<Effect> {
        if (state == State.ENDED) return emptyList()
        val out = mutableListOf<Effect>(Effect.CancelSlotRequests(key))
        bot?.let { out += Effect.RemoveBot(it) }
        bot = null
        state = State.ENDED
        return out
    }

    fun ownerDied(nowMs: Long): List<Effect> = endRound(nowMs, Messages.TRAINER_WON, Outcome.BOT_WON)

    fun botDied(nowMs: Long): List<Effect> = endRound(nowMs, Messages.YOU_WON, Outcome.PLAYER_WON)

    /** The bot was dropped after an internal failure: ends the round like [botDied] but without a title. */
    fun botFailed(nowMs: Long): List<Effect> = endRound(nowMs, null, Outcome.NO_RESULT)

    fun tick(nowMs: Long): List<Effect> = when (state) {
        State.COUNTDOWN -> {
            if (nowMs - phaseStartMs >= t.countdownS * 1000L) {
                state = State.FIGHTING
                phaseStartMs = nowMs
            }
            emptyList()
        }
        State.FIGHTING ->
            if (nowMs - phaseStartMs >= t.timeLimitS * 1000L) endRound(nowMs, Messages.DRAW, Outcome.DRAW) else emptyList()
        State.REMATCH_WAIT ->
            if (nowMs - phaseStartMs >= t.rematchDelayS * 1000L) {
                state = State.QUEUED
                spawnPending = false
                listOf(Effect.RequestSlots(key, 1))
            } else emptyList()
        else -> emptyList()
    }

    /** Ends the current round; the title and [outcome] count only if the fight had begun (else NO_RESULT). */
    private fun endRound(nowMs: Long, title: String?, outcome: Outcome): List<Effect> {
        val wasFighting = state == State.FIGHTING
        if (state != State.FIGHTING && state != State.COUNTDOWN) return emptyList()
        val out = mutableListOf<Effect>()
        bot?.let { out += Effect.RemoveBot(it) }
        if (wasFighting && title != null) out += Effect.Title(owner, title)
        out += Effect.RoundEnded(owner, if (wasFighting) outcome else Outcome.NO_RESULT)
        bot = null
        state = State.REMATCH_WAIT
        phaseStartMs = nowMs
        return out
    }
}
