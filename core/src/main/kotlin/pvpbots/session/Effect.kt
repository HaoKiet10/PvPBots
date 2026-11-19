package pvpbots.session

import pvpbots.profile.Difficulty
import pvpbots.skill.Outcome
import pvpbots.team.Side
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
    /** A filler bot for [side] at [rating]; [pad] is the index of its spawn pad on that side. */
    data class SpawnTeamBot(val key: SessionKey.Team, val side: Side, val rating: Double, val pad: Int) : Effect
    /** Teleport [player] to pad [pad] of [side]. */
    data class PlacePlayer(val player: UUID, val side: Side, val pad: Int) : Effect
    /** A team round's result for one player (rating update only; titles are separate). */
    data class TeamResult(val player: UUID, val outcome: Outcome) : Effect
    /** [player] must wait for the next round (show [Messages.WAIT]). */
    data class Wait(val player: UUID) : Effect
}

/** Player-facing strings. */
object Messages {
    const val BUSY = "All trainers busy, please wait\u2026"
    const val YOU_WON = "You won!"
    const val TRAINER_WON = "Trainer won"
    const val DRAW = "Time's up \u2014 draw"
    const val TEAM_WON = "Your team won!"
    const val TEAM_LOST = "Your team lost"
    const val WAIT = "Next round starts soon"
}
