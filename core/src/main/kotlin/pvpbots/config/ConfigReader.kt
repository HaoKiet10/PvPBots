package pvpbots.config

/** Result of loading a config section: the value plus human-readable warnings. */
data class Loaded<T>(val value: T, val warnings: List<String>)

/**
 * Typed, validating reader over one already-parsed map section. Accessors return the default
 * and record a warning naming the full path when a present value is invalid. Absent keys
 * silently yield the default. Unknown keys are never inspected.
 */
internal class ConfigReader(private val map: Map<*, *>, private val path: String, private val warnings: MutableList<String>) {
    fun key(name: String) = if (path.isEmpty()) name else "$path.$name"

    fun warn(name: String, msg: String) {
        warnings += "${key(name)}: $msg"
    }

    fun section(name: String): ConfigReader? {
        val v = map[name] ?: return null
        if (v is Map<*, *>) return ConfigReader(v, key(name), warnings)
        warn(name, "expected a section, using defaults")
        return null
    }

    fun int(name: String, default: Int, min: Int = 0): Int {
        val v = map[name] ?: return default
        val n: Long? = when (v) {
            is Int, is Long, is Short, is Byte -> (v as Number).toLong()
            is Double, is Float -> (v as Number).toDouble().takeIf { it.isFinite() && it == Math.floor(it) && Math.abs(it) < 1e15 }?.toLong()
            else -> null
        }
        if (n == null || n > Int.MAX_VALUE || n < Int.MIN_VALUE) {
            warn(name, "expected an integer, using default $default")
            return default
        }
        if (n < min) {
            warn(name, "must be >= $min, using default $default")
            return default
        }
        return n.toInt()
    }

    /** Finite number, or null when absent or wrong type (warns on wrong type). Range checks are the caller's. */
    fun double(name: String): Double? {
        val v = map[name] ?: return null
        if (v is Number && v.toDouble().isFinite()) return v.toDouble()
        warn(name, "expected a number")
        return null
    }

    fun nonNegDouble(name: String, default: Double): Double {
        val d = double(name) ?: return default
        if (d < 0) {
            warn(name, "must be >= 0, using default $default")
            return default
        }
        return d
    }

    fun boolean(name: String, default: Boolean): Boolean {
        val v = map[name] ?: return default
        if (v is Boolean) return v
        warn(name, "expected true or false, using default $default")
        return default
    }

    fun string(name: String, default: String): String {
        val v = map[name] ?: return default
        if (v is String && v.isNotBlank()) return v
        warn(name, "expected a non-empty string, using default $default")
        return default
    }

    fun <E : Enum<E>> enum(name: String, values: Array<E>, default: E): E {
        val v = map[name] ?: return default
        val norm = (v as? String)?.trim()?.replace('-', '_')?.uppercase()
        val hit = values.firstOrNull { it.name == norm }
        if (hit != null) return hit
        val opts = values.joinToString { it.name.lowercase().replace('_', '-') }
        warn(name, "expected one of $opts, using default ${default.name.lowercase().replace('_', '-')}")
        return default
    }
}
