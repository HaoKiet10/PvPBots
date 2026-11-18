package pvpbots.session

import pvpbots.profile.Difficulty
import pvpbots.skill.Outcome
import java.util.UUID

/** Side effects requested by a session state machine; executed by the director. */
sealed interface Effect {
    data class RequestSlots(val key: SessionKey, val n: Int) : Effect
    data class CancelSlotRequests(val key: SessionKey) : Effect
    data class SpawnBot(val key: SessionKey, val role: Role, val difficulty: Difficulty?, val nearPlayer: UUID) : Effect
    data class RoundEnded(val owner: UUID, val outcome: Outcome) : Effect
    data class RemoveBot(val botId: UUID) : Effect
    data class Title(val player: UUID, val text: String) : Effect
    data class ActionBar(val player: UUID, val text: String) : Effect
}

/** Player-facing strings. */
object Messages {
    const val BUSY = "All trainers busy, please wait\u2026"
    const val YOU_WON = "You won!"
    const val TRAINER_WON = "Trainer won"
    const val DRAW = "Time's up \u2014 draw"
}
