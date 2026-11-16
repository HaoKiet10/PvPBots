package pvpbots.session

import java.util.UUID

/** One player's personal 1v1 duel: queue -> countdown -> fight -> rematch wait. Pure; time is passed in. */
class TrainerSession(private val owner: UUID, private val area: AreaRef, private val t: Timings) {
    enum class State { QUEUED, COUNTDOWN, FIGHTING, REMATCH_WAIT, ENDED }

    val key: SessionKey = SessionKey.Trainer(owner)
    private var state = State.QUEUED
    private var bot: UUID? = null
    private var phaseStartMs = 0L

    fun state(): State = state
    fun botId(): UUID? = bot
    fun fighting(): Boolean = state == State.FIGHTING

    fun start(nowMs: Long): List<Effect> {
        if (state == State.ENDED) return emptyList()
        state = State.QUEUED
        return listOf(Effect.RequestSlots(key, 1))
    }

    fun queued(): List<Effect> =
        if (state == State.QUEUED) listOf(Effect.ActionBar(owner, Messages.BUSY)) else emptyList()

    fun slotGranted(nowMs: Long): List<Effect> =
        if (state == State.QUEUED) listOf(Effect.SpawnBot(key, Role.TRAINER, area.difficulty, owner)) else emptyList()

    fun botSpawned(botId: UUID, nowMs: Long): List<Effect> {
        if (state != State.QUEUED) return emptyList()
        bot = botId
        phaseStartMs = nowMs
        state = State.COUNTDOWN
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

    fun ownerDied(nowMs: Long): List<Effect> = endRound(nowMs, Messages.TRAINER_WON)

    fun botDied(nowMs: Long): List<Effect> = endRound(nowMs, Messages.YOU_WON)

    fun tick(nowMs: Long): List<Effect> = when (state) {
        State.COUNTDOWN -> {
            if (nowMs - phaseStartMs >= t.countdownS * 1000L) {
                state = State.FIGHTING
                phaseStartMs = nowMs
            }
            emptyList()
        }
        State.FIGHTING ->
            if (nowMs - phaseStartMs >= t.timeLimitS * 1000L) endRound(nowMs, Messages.DRAW) else emptyList()
        State.REMATCH_WAIT ->
            if (nowMs - phaseStartMs >= t.rematchDelayS * 1000L) {
                state = State.QUEUED
                listOf(Effect.RequestSlots(key, 1))
            } else emptyList()
        else -> emptyList()
    }

    /** Ends the current round; the title is shown only if the fight had begun. */
    private fun endRound(nowMs: Long, title: String): List<Effect> {
        val wasFighting = state == State.FIGHTING
        if (state != State.FIGHTING && state != State.COUNTDOWN) return emptyList()
        val out = mutableListOf<Effect>()
        bot?.let { out += Effect.RemoveBot(it) }
        if (wasFighting) out += Effect.Title(owner, title)
        bot = null
        state = State.REMATCH_WAIT
        phaseStartMs = nowMs
        return out
    }
}
