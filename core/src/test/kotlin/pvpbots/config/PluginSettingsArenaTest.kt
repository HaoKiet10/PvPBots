package pvpbots.config

import pvpbots.arena.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginSettingsArenaTest {
    @Test fun absentSection_usesDefaults() {
        val l = PluginSettings.load(emptyMap())
        assertEquals(4000, l.value.arenas.blocksPerTick)
        assertEquals("RED_CONCRETE", l.value.arenas.materials[Part.PAD_B])
        assertEquals(Part.entries.size - 1, l.value.arenas.materials.size) // every part but AIR
        assertTrue(l.warnings.isEmpty())
    }

    @Test fun values_loadedAndInvalidOnesWarn() {
        val l = PluginSettings.load(
            mapOf("arenas" to mapOf("blocks-per-tick" to 0, "materials" to mapOf("pad-a" to "lime_concrete", "wall" to 5))),
        )
        assertEquals(4000, l.value.arenas.blocksPerTick)
        assertEquals("LIME_CONCRETE", l.value.arenas.materials[Part.PAD_A])
        assertEquals("STONE_BRICKS", l.value.arenas.materials[Part.WALL])
        assertEquals(listOf("arenas.blocks-per-tick", "arenas.materials.wall"), l.warnings.map { it.substringBefore(':') })
    }
}
