package pvpbots.session

import java.util.UUID

/** A boss area's fixed-size team of ally bots. Pure; time is passed in. */
class AllySquad(private val area: AreaRef, private val t: Timings) {
    enum class State { ACTIVE, GRACE, ENDED }

    val key: SessionKey = SessionKey.Squad(area.world, area.regionId)
    private var state = State.ACTIVE
    private val players = LinkedHashSet<UUID>()
    private val bots = LinkedHashSet<UUID>()
    private val respawnsDue = ArrayList<Long>()
    private var started = false
    private var lastPlayer: UUID? = null
    private var graceStartMs = 0L

    fun state(): State = state
    fun bots(): Set<UUID> = bots.toSet()

    fun playerEntered(player: UUID, nowMs: Long): List<Effect> {
        if (state == State.ENDED) return emptyList()
        players += player
        lastPlayer = player
        if (state == State.GRACE) {
            state = State.ACTIVE
            return emptyList()
        }
        if (!started) {
            started = true
            return listOf(Effect.RequestSlots(key, area.count))
        }
        return emptyList()
    }

    fun playerLeft(player: UUID, nowMs: Long): List<Effect> {
        if (state != State.ACTIVE || !players.remove(player)) return emptyList()
        if (players.isEmpty()) {
            state = State.GRACE
            graceStartMs = nowMs
        }
        return emptyList()
    }

    /** Emits one [Effect.SpawnBot] while ACTIVE/GRACE; empty when ENDED (the director releases the slot). */
    fun slotGranted(nowMs: Long): List<Effect> {
        if (state == State.ENDED) return emptyList()
        val near = players.firstOrNull() ?: lastPlayer ?: return emptyList()
        return listOf(Effect.SpawnBot(key, Role.ALLY, area.difficulty, near))
    }

    /**
     * A bot requested via [slotGranted] now exists. Tracked while ACTIVE/GRACE; once ENDED it is
     * unwanted, so returns `[Effect.RemoveBot(botId)]` as cleanup and changes nothing else.
     */
    fun botSpawned(botId: UUID, nowMs: Long): List<Effect> {
        if (state == State.ENDED) return listOf(Effect.RemoveBot(botId))
        bots += botId
        return emptyList()
    }

    fun allyDied(botId: UUID, nowMs: Long): List<Effect> {
        if (state == State.ENDED) return emptyList()
        if (bots.remove(botId)) respawnsDue += nowMs + t.allyRespawnS * 1000L
        return emptyList()
    }

    fun tick(nowMs: Long): List<Effect> {
        when (state) {
            State.ENDED -> return emptyList()
            State.GRACE -> {
                if (nowMs - graceStartMs < t.leaveGraceS * 1000L) return emptyList()
                val out = mutableListOf<Effect>(Effect.CancelSlotRequests(key))
                bots.forEach { out += Effect.RemoveBot(it) }
                bots.clear()
                respawnsDue.clear()
                state = State.ENDED
                return out
            }
            State.ACTIVE -> {
                val due = respawnsDue.count { it <= nowMs }
                if (due == 0) return emptyList()
                respawnsDue.removeAll { it <= nowMs }
                return List(due) { Effect.RequestSlots(key, 1) }
            }
        }
    }
}
