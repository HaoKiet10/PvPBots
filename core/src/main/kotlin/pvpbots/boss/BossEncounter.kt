package pvpbots.boss

import java.util.UUID

/**
 * Pure state machine of one boss arena: IDLE -> INTRO -> FIGHT -> VICTORY -> COOLDOWN -> IDLE, plus the RESET
 * (FIGHT -> IDLE after 10 s with no participant). The caller decides who is a participant (alive, survival or
 * adventure, inside the region) and reports changes through [playerEntered] / [playerLeft] / [playerDied]; every
 * input returns the effects to carry out. Time only comes from [tick].
 *
 * Winners: dealt >= 1 damage during the fight AND (inside at the kill OR died inside during this fight).
 * Health scaling is the caller's: use [participants] with [BossHealth.start] on INTRO and [BossHealth.playersNow] later.
 */
class BossEncounter(private val cooldownS: Int) {
    enum class State { IDLE, INTRO, FIGHT, VICTORY, COOLDOWN }

    sealed interface Effect {
        data object SpawnBoss : Effect
        data object RemoveBoss : Effect
        data object ShowBar : Effect
        data object HideBar : Effect
        data class Title(val text: String) : Effect
        data class CooldownBar(val secondsLeft: Int) : Effect
        data class Reward(val winners: Set<UUID>) : Effect
    }

    companion object {
        const val INTRO_MS = 3000L
        const val RESET_MS = 10_000L
        const val VICTORY_MS = 5000L
        const val VICTORY_TITLE = "BOSS DEFEATED"
    }

    var state: State = State.IDLE
        private set

    private val inside = LinkedHashSet<UUID>()
    private val diedInside = HashSet<UUID>()
    private val dealt = HashMap<UUID, Double>()

    private var lastNow = 0L
    private var introStart: Long? = null
    private var emptySince: Long? = null
    private var victoryEnd = 0L
    private var cooldownEnd = 0L
    private var shownSeconds = -1

    /** Current participants (read-only view). */
    val participants: Set<UUID> get() = inside

    /** Whole seconds of cooldown left, 0 outside COOLDOWN/VICTORY (for `arena list`). */
    fun cooldownSecondsLeft(nowMs: Long): Int =
        if (state == State.COOLDOWN || state == State.VICTORY) secondsLeft(nowMs) else 0

    private fun secondsLeft(nowMs: Long) = maxOf(0L, (cooldownEnd - nowMs + 999) / 1000).toInt()

    private fun startIntro(): List<Effect> {
        state = State.INTRO
        introStart = null
        emptySince = null
        diedInside.clear()
        dealt.clear()
        return listOf(Effect.SpawnBoss, Effect.ShowBar)
    }

    fun playerEntered(id: UUID): List<Effect> {
        inside += id
        emptySince = null
        return when (state) {
            State.IDLE -> startIntro()
            State.COOLDOWN -> { shownSeconds = -1; emptyList() }
            else -> emptyList()
        }
    }

    fun playerLeft(id: UUID): List<Effect> {
        inside -= id
        return emptyList()
    }

    /** [id] died inside the region: no longer a participant, but remembered as inside for the winner rule. */
    fun playerDied(id: UUID): List<Effect> {
        inside -= id
        if (state == State.INTRO || state == State.FIGHT) diedInside += id
        return emptyList()
    }

    /** Damage dealt to the boss by [by] (the amount that reduced the pool); only counts during FIGHT. */
    fun bossDamaged(by: UUID, amount: Double) {
        if (state == State.FIGHT && amount > 0.0) dealt.merge(by, amount, Double::plus)
    }

    fun bossDead(): List<Effect> {
        if (state != State.FIGHT) return emptyList()
        val winners = dealt.filter { it.value >= 1.0 }.keys.filterTo(LinkedHashSet()) { it in inside || it in diedInside }
        state = State.VICTORY
        victoryEnd = lastNow + VICTORY_MS
        cooldownEnd = lastNow + cooldownS * 1000L
        emptySince = null
        return listOf(Effect.Title(VICTORY_TITLE), Effect.HideBar, Effect.Reward(winners), Effect.RemoveBoss)
    }

    fun tick(nowMs: Long): List<Effect> {
        lastNow = nowMs
        val fx = ArrayList<Effect>()
        if (state == State.INTRO) {
            val s = introStart ?: nowMs.also { introStart = it }
            if (nowMs - s >= INTRO_MS) {
                state = State.FIGHT
                emptySince = null
            }
        }
        if (state == State.FIGHT) {
            if (inside.isNotEmpty()) {
                emptySince = null
            } else {
                val s = emptySince ?: nowMs.also { emptySince = it }
                if (nowMs - s >= RESET_MS) {
                    fx += Effect.RemoveBoss
                    fx += Effect.HideBar
                    toIdle()
                }
            }
        }
        if (state == State.VICTORY && nowMs >= victoryEnd) {
            state = State.COOLDOWN
            shownSeconds = -1
        }
        if (state == State.COOLDOWN) {
            if (nowMs >= cooldownEnd) {
                state = State.IDLE
                if (inside.isNotEmpty()) fx += startIntro()
            } else if (inside.isNotEmpty()) {
                val left = secondsLeft(nowMs)
                if (left != shownSeconds) {
                    shownSeconds = left
                    fx += Effect.CooldownBar(left)
                }
            }
        }
        return fx
    }

    /** Plugin disable / reload: drop the boss, no rewards, no cooldown carried over. */
    fun shutdown(): List<Effect> {
        val fx = if (state == State.INTRO || state == State.FIGHT) listOf(Effect.RemoveBoss, Effect.HideBar) else emptyList()
        inside.clear()
        toIdle()
        return fx
    }

    private fun toIdle() {
        state = State.IDLE
        introStart = null
        emptySince = null
        diedInside.clear()
        dealt.clear()
    }
}
