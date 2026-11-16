package pvpbots.profile

import org.yaml.snakeyaml.Yaml
import pvpbots.config.PluginSettings
import pvpbots.session.Timings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileLoaderTest {
    @Suppress("UNCHECKED_CAST")
    private fun bundled(): Map<String, Any?> {
        val stream = javaClass.classLoader.getResourceAsStream("config.yml")!!
        return stream.use { Yaml().load<Any?>(it) } as Map<String, Any?>
    }

    @Test fun defaultsMatchSpec() {
        val d = ProfileLoader.defaults()
        val p = d.getValue(Difficulty.PRO)
        assertEquals(2, p.reactionTicks)
        assertEquals(1.0, p.aimErrorDeg)
        assertEquals(ShieldMode.FULL_AXE, p.shield)
        assertTrue(p.totem)
        assertEquals(
            DifficultyProfile(8, 12.0, 15.0, 0.6, 1.0, CritMode.NEVER, false, StrafeMode.NONE, ShieldMode.NONE, 0.0, false, "starter"),
            d.getValue(Difficulty.EASY),
        )
        assertEquals(CritMode.SOMETIMES, d.getValue(Difficulty.MEDIUM).crits)
        assertEquals(0.4, d.getValue(Difficulty.HARD).healAt)
    }

    @Test fun overrideOneValue() {
        val r = ProfileLoader.load(mapOf("hard" to mapOf("reaction-ticks" to 4)))
        assertEquals(4, r.profiles.getValue(Difficulty.HARD).reactionTicks)
        assertEquals(3.0, r.profiles.getValue(Difficulty.HARD).aimErrorDeg)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun invalidValueWarnsAndDefaults() {
        val r = ProfileLoader.load(mapOf("easy" to mapOf("aim-error" to "lots")))
        assertEquals(12.0, r.profiles.getValue(Difficulty.EASY).aimErrorDeg)
        assertTrue(r.warnings[0].contains("difficulties.easy.aim-error"))
    }

    @Test fun enumsAcceptCaseAndSeparatorVariants() {
        val r = ProfileLoader.load(mapOf("easy" to mapOf("shield" to "Full-Axe", "strafe" to "RANDOMISED", "crits" to "often")))
        val p = r.profiles.getValue(Difficulty.EASY)
        assertEquals(ShieldMode.FULL_AXE, p.shield)
        assertEquals(StrafeMode.RANDOMISED, p.strafe)
        assertEquals(CritMode.OFTEN, p.crits)
        assertTrue(r.warnings.isEmpty())
        val r2 = ProfileLoader.load(mapOf("easy" to mapOf("shield" to "full_axe")))
        assertEquals(ShieldMode.FULL_AXE, r2.profiles.getValue(Difficulty.EASY).shield)
    }

    @Test fun badEnumBooleanAndTypeWarn() {
        val r = ProfileLoader.load(mapOf("medium" to mapOf("strafe" to "spin", "w-tap" to "yes", "reaction-ticks" to 2.5, "kit" to 3)))
        val p = r.profiles.getValue(Difficulty.MEDIUM)
        assertEquals(ProfileLoader.defaults().getValue(Difficulty.MEDIUM), p)
        assertEquals(4, r.warnings.size)
        assertTrue(r.warnings.any { it.contains("difficulties.medium.strafe") })
        assertTrue(r.warnings.any { it.contains("difficulties.medium.w-tap") })
        assertTrue(r.warnings.any { it.contains("difficulties.medium.reaction-ticks") })
        assertTrue(r.warnings.any { it.contains("difficulties.medium.kit") })
    }

    @Test fun outOfRangeWarnsAndDefaults() {
        val r = ProfileLoader.load(mapOf("hard" to mapOf("aim-error" to -1, "heal-at" to 1.5, "turn-speed" to -5, "reaction-ticks" to -1)))
        assertEquals(ProfileLoader.defaults().getValue(Difficulty.HARD), r.profiles.getValue(Difficulty.HARD))
        assertEquals(4, r.warnings.size)
        assertTrue(r.warnings.any { it.contains("difficulties.hard.heal-at") })
    }

    @Test fun cooldownMinAboveMaxWarnsAndDefaultsBoth() {
        val r = ProfileLoader.load(mapOf("easy" to mapOf("cooldown-min" to 1.5, "cooldown-max" to 0.7)))
        val p = r.profiles.getValue(Difficulty.EASY)
        assertEquals(0.6, p.cooldownMin)
        assertEquals(1.0, p.cooldownMax)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings[0].contains("difficulties.easy.cooldown-min"))
    }

    @Test fun cooldownOverrideRelativeToDefaultOtherBound() {
        val r = ProfileLoader.load(mapOf("easy" to mapOf("cooldown-max" to 0.5)))
        assertEquals(1, r.warnings.size) // 0.5 < default min 0.6
    }

    @Test fun unknownKeysAndDifficultiesIgnoredAndBadSectionWarns() {
        val r = ProfileLoader.load(mapOf("easy" to mapOf("bogus" to 1), "nightmare" to mapOf("kit" to "x")))
        assertTrue(r.warnings.isEmpty())
        assertEquals(ProfileLoader.defaults(), r.profiles)
        val r2 = ProfileLoader.load(mapOf("easy" to "nope"))
        assertEquals(1, r2.warnings.size)
        assertTrue(r2.warnings[0].contains("difficulties.easy"))
    }

    @Test fun bundledConfigLoadsWithoutWarnings() {
        val root = bundled()
        @Suppress("UNCHECKED_CAST")
        val r = ProfileLoader.load(root["difficulties"] as Map<String, Any?>)
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
        assertEquals(ProfileLoader.defaults(), r.profiles)
        val s = PluginSettings.load(root)
        assertTrue(s.warnings.isEmpty(), s.warnings.toString())
        assertEquals(PluginSettings.load(emptyMap()).value, s.value)
        val kits = root["kits"] as Map<*, *>
        assertEquals(setOf("starter", "iron", "diamond", "netherite"), kits.keys)
        assertEquals(emptyList<Any?>(), root["skins"])
        for (p in r.profiles.values) assertTrue(p.kit in kits.keys)
    }

    @Test fun settingsDefaults() {
        val l = PluginSettings.load(emptyMap())
        val s = l.value
        assertEquals(100, s.maxBots)
        assertEquals(Timings(3, 300, 5, 15, 10), s.timings)
        assertEquals(2, s.botViewDistance)
        assertEquals(5, s.stuckRetrySeconds)
        assertEquals(10, s.stuckTeleportSeconds)
        assertTrue(l.warnings.isEmpty())
    }

    @Test fun settingsOverridesAndNestedPaths() {
        val l = PluginSettings.load(
            mapOf(
                "max-bots" to 7,
                "trainer" to mapOf("countdown-seconds" to 1, "time-limit-seconds" to 60, "rematch-delay-seconds" to 2),
                "ally" to mapOf("respawn-seconds" to 4, "leave-grace-seconds" to 6),
                "bot" to mapOf("view-distance" to 3, "stuck-retry-seconds" to 8, "stuck-teleport-seconds" to 20),
            ),
        )
        assertEquals(7, l.value.maxBots)
        assertEquals(Timings(1, 60, 2, 4, 6), l.value.timings)
        assertEquals(3, l.value.botViewDistance)
        assertEquals(8, l.value.stuckRetrySeconds)
        assertEquals(20, l.value.stuckTeleportSeconds)
        assertTrue(l.warnings.isEmpty())
    }

    @Test fun settingsInvalidWarnsWithFullPath() {
        val l = PluginSettings.load(
            mapOf("max-bots" to 0, "trainer" to mapOf("countdown-seconds" to "x", "time-limit-seconds" to -3), "bot" to 5),
        )
        assertEquals(100, l.value.maxBots)
        assertEquals(Timings(3, 300, 5, 15, 10), l.value.timings)
        assertEquals(4, l.warnings.size)
        assertTrue(l.warnings.any { it.contains("max-bots") })
        assertTrue(l.warnings.any { it.contains("trainer.countdown-seconds") })
        assertTrue(l.warnings.any { it.contains("trainer.time-limit-seconds") })
        assertTrue(l.warnings.any { it.startsWith("bot") })
        assertFalse(l.warnings.any { it.contains("difficulties") })
    }
}
