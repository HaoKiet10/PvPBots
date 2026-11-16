package pvpbots.combat

import pvpbots.math.Vec3
import java.util.UUID

object TargetSelector {
    private const val PEEL_RADIUS = 4.0

    /** Prefer mobs attacking a nearby real player (nearest to bot); else highest max health, ties nearest. */
    fun pickAllyTarget(botPos: Vec3, hostiles: List<MobView>, realPlayers: Map<UUID, Vec3>): UUID? {
        fun dist(m: MobView) = (m.pos - botPos).length()
        val peel = hostiles.filter { m ->
            val tp = m.targetId?.let { realPlayers[it] }
            tp != null && (m.pos - tp).length() <= PEEL_RADIUS
        }
        if (peel.isNotEmpty()) return peel.minByOrNull { dist(it) }?.id
        return hostiles.sortedWith(compareByDescending<MobView> { it.maxHealth }.thenBy { dist(it) }).firstOrNull()?.id
    }
}
