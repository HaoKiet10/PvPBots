package pvpbots.config

import pvpbots.skill.SkillSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginSettingsSkillTest {
    @Test fun absentSection_usesDefaults() {
        val l = PluginSettings.load(emptyMap())
        assertEquals(SkillSettings(), l.value.skill)
        assertTrue(l.warnings.isEmpty())
    }

    @Test fun values_loadedAndInvalidOnesWarn() {
        val l = PluginSettings.load(mapOf("skill" to mapOf("start-rating" to 70, "step" to 2.5, "learn-rate" to 5, "adjust-every-seconds" to 0)))
        assertEquals(70.0, l.value.skill.startRating)
        assertEquals(2.5, l.value.skill.step)
        assertEquals(0.3, l.value.skill.learnRate)
        assertEquals(5, l.value.skill.adjustEverySeconds)
        assertEquals(2, l.warnings.size)
        assertTrue(l.warnings.all { it.startsWith("skill.") })
    }
}
