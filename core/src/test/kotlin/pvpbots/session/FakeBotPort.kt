package pvpbots.session

import pvpbots.profile.Difficulty
import java.util.UUID

/** Records every [BotPort] call. Spawned bot ids are deterministic (0x100 + n). */
class FakeBotPort : BotPort {
    data class Spawn(val key: SessionKey, val role: Role, val difficulty: Difficulty, val area: AreaRef, val near: UUID, val botId: UUID)

    val bots = mutableSetOf<UUID>()
    val spawned = mutableListOf<Spawn>()
    val removed = mutableListOf<UUID>()
    private val bars = mutableMapOf<UUID, MutableList<String>>()
    val actionBars: Map<UUID, List<String>> get() = bars
    private val titleLog = mutableMapOf<UUID, MutableList<String>>()
    val titles: Map<UUID, List<String>> get() = titleLog
    var failNextSpawn = false
    /** Thrown (once) by the next spawn call. */
    var throwOnNextSpawn: Throwable? = null
    /** Fail (return null) every spawn while true. */
    var failAllSpawns = false
    /** Players [isDead] reports as dead. */
    val deadPlayers = mutableSetOf<UUID>()
    var spawnCalls = 0
    var onSpawn: ((Spawn) -> Unit)? = null
    private var counter = 0

    val lastSpawnNear: UUID? get() = spawned.lastOrNull()?.near

    /** Spawned and not yet removed. */
    val alive: Set<UUID> get() = spawned.map { it.botId }.filter { it !in removed }.toSet()

    override fun isBot(id: UUID) = id in bots

    override fun spawn(key: SessionKey, role: Role, difficulty: Difficulty, area: AreaRef, nearPlayer: UUID): UUID? {
        spawnCalls++
        throwOnNextSpawn?.let { throwOnNextSpawn = null; throw it }
        if (failAllSpawns) return null
        if (failNextSpawn) {
            failNextSpawn = false
            return null
        }
        val id = UUID(0, 0x100L + counter++)
        val s = Spawn(key, role, difficulty, area, nearPlayer, id)
        spawned += s
        onSpawn?.invoke(s)
        return id
    }

    override fun remove(botId: UUID) {
        removed += botId
    }

    override fun isDead(player: UUID) = player in deadPlayers

    override fun title(player: UUID, text: String) {
        titleLog.getOrPut(player) { mutableListOf() } += text
    }

    override fun actionBar(player: UUID, text: String) {
        bars.getOrPut(player) { mutableListOf() } += text
    }
}
