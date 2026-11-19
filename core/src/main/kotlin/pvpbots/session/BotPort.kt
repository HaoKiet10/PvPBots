package pvpbots.session

import pvpbots.profile.Difficulty
import pvpbots.skill.Outcome
import pvpbots.team.Side
import java.util.UUID

/** The director's view of the server: spawning/removing bots and talking to players. */
interface BotPort {
    fun isBot(id: UUID): Boolean

    /** Spawns a bot for [key]; returns its id, or null if the spawn failed. */
    fun spawn(key: SessionKey, role: Role, difficulty: Difficulty?, area: AreaRef, nearPlayer: UUID): UUID?

    /** Removes a bot. Must be idempotent. */
    fun remove(botId: UUID)
    fun title(player: UUID, text: String)
    fun actionBar(player: UUID, text: String)

    /** A trainer round of [owner] ended with [outcome]. */
    fun roundEnded(owner: UUID, outcome: Outcome) {}

    /** True while [player] is dead (on the respawn screen); a trainer is not spawned for a dead owner. */
    fun isDead(player: UUID): Boolean = false

    /** Spawns a filler bot for team [key] on [side] at skill [rating] on pad [pad]; null if the spawn failed. */
    fun spawnTeamBot(key: SessionKey.Team, side: Side, rating: Double, area: AreaRef, pad: Int): UUID? = null

    /** Teleports [player] to pad [pad] of [side] in [area]. */
    fun placePlayer(player: UUID, area: AreaRef, side: Side, pad: Int) {}

    /** A team round's result for [player] (rating update). The player may be offline or outside the arena. */
    fun teamResult(player: UUID, outcome: Outcome) {}

    /** The player's skill rating (50.0 when unknown). */
    fun rating(player: UUID): Double = 50.0
}
