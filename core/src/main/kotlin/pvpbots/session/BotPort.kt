package pvpbots.session

import pvpbots.profile.Difficulty
import pvpbots.skill.Outcome
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
}
