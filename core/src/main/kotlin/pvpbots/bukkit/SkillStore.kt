package pvpbots.bukkit

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import pvpbots.skill.SkillBlender
import pvpbots.skill.SkillSettings

/** A player's skill rating (0..100) on their own persistent data, key `pvpbots:skill`: one rating for all arenas. */
class SkillStore(private val settings: () -> SkillSettings) {
    private val key = NamespacedKey("pvpbots", "skill")

    /** The stored rating, clamped; the start rating when missing, NaN or stored as another type. */
    fun get(p: Player): Double {
        val stored = try {
            p.persistentDataContainer.get(key, PersistentDataType.DOUBLE)
        } catch (e: IllegalArgumentException) {
            null // another plugin or an old build stored a different type under this key
        }
        return SkillBlender.sanitize(stored, settings())
    }

    fun set(p: Player, rating: Double) {
        p.persistentDataContainer.set(key, PersistentDataType.DOUBLE, SkillBlender.sanitize(rating, settings()))
    }
}
