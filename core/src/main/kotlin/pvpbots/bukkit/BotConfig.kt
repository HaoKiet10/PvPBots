package pvpbots.bukkit

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import pvpbots.config.PluginSettings
import pvpbots.profile.Difficulty
import pvpbots.profile.DifficultyProfile
import pvpbots.profile.ProfileLoader
import java.io.File
import java.io.Reader

/** Everything read from config.yml. Built by [load]; every problem becomes a warning, never an exception. */
class BotConfig(
    val settings: PluginSettings,
    val profiles: Map<Difficulty, DifficultyProfile>,
    val kits: KitApplier,
    val skins: SkinPool,
) {
    companion object {
        /**
         * Reads [file]. A missing `kits:` section falls back to the kits of the bundled config ([bundled]);
         * a missing or malformed `difficulties:` section falls back to the built-in profiles.
         */
        fun load(file: File, bundled: () -> Reader?): Pair<BotConfig, List<String>> {
            val warnings = ArrayList<String>()
            val yaml = YamlConfiguration()
            try {
                yaml.load(file)
            } catch (e: Exception) {
                warnings += "config.yml could not be read (${e.message}); using defaults"
            }
            val root = yaml.toPlainMap()

            val settings = PluginSettings.load(root)
            warnings += settings.warnings

            val profiles = when (val d = root["difficulties"]) {
                null -> {
                    warnings += "difficulties: section missing, using built-in profiles"
                    ProfileLoader.defaults()
                }
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val r = ProfileLoader.load(d as Map<String, Any?>)
                    warnings += r.warnings
                    r.profiles
                }
                else -> {
                    warnings += "difficulties: expected a section, using built-in profiles"
                    ProfileLoader.defaults()
                }
            }

            @Suppress("UNCHECKED_CAST")
            val kitsRaw: Map<String, Any?> = when (val k = root["kits"]) {
                is Map<*, *> -> k as Map<String, Any?>
                else -> {
                    warnings += if (k == null) "kits: section missing, using bundled kits" else "kits: expected a section, using bundled kits"
                    bundledKits(bundled)
                }
            }
            val kits = KitApplier.load(kitsRaw, warnings)
            profiles.forEach { (d, p) ->
                if (p.kit !in kits.kits) warnings += "difficulties.${d.name.lowercase()}.kit: no kit named '${p.kit}', bots get no items"
            }

            val skins = SkinPool.load(root["skins"], warnings)
            return BotConfig(settings.value, profiles, kits, skins) to warnings
        }

        @Suppress("UNCHECKED_CAST")
        private fun bundledKits(bundled: () -> Reader?): Map<String, Any?> {
            val reader = bundled() ?: return emptyMap()
            val yaml = reader.use { YamlConfiguration.loadConfiguration(it) }
            return yaml.toPlainMap()["kits"] as? Map<String, Any?> ?: emptyMap()
        }

        /** Bukkit sections -> nested plain maps (lists keep their elements; list maps are already plain). */
        private fun ConfigurationSection.toPlainMap(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for (key in getKeys(false)) {
                val v = get(key)
                out[key] = if (v is ConfigurationSection) v.toPlainMap() else v
            }
            return out
        }
    }
}
