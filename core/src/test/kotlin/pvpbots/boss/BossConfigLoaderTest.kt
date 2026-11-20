package pvpbots.boss

import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BossConfigLoaderTest {
    @Suppress("UNCHECKED_CAST")
    private fun bundled(): Map<String, Any?> {
        val stream = javaClass.classLoader.getResourceAsStream("config.yml")!!
        val root = stream.use { Yaml().load<Any?>(it) } as Map<String, Any?>
        return root["bosses"] as Map<String, Any?>
    }

    private val damaging = setOf(MoveType.COMBO, MoveType.LEAP_SLAM, MoveType.SHOCKWAVE, MoveType.VOLLEY, MoveType.CHARGED_SHOT)

    @Test fun shippedBossesLoadCleanly() {
        val r = BossConfigLoader.load(bundled())
        assertEquals(emptyList(), r.warnings)
        assertEquals(setOf("ashblade", "thornbow"), r.value.keys)
        for (b in r.value.values) {
            assertTrue(b.baseHealth > 0 && b.moves.isNotEmpty() && b.phases.isNotEmpty())
            for (m in b.moves.filter { it.type in damaging }) assertTrue(m.telegraphTicks >= 10, "${b.id}.${m.id}")
            val ids = b.moves.map { it.id }.toSet()
            for (p in b.phases) assertTrue((p.addMoves + p.removeMoves).all { it in ids })
        }
    }

    @Test fun shippedPhasesMatchSpec() {
        val v = BossConfigLoader.load(bundled()).value
        val ash = v.getValue("ashblade")
        assertEquals(0.5, ash.phases.single().at)
        assertTrue(ash.phases.single().addMoves.isNotEmpty())
        assertEquals(0.5, v.getValue("thornbow").phases.single().at)
        assertEquals(600, ash.cooldownSeconds)
        assertEquals(0.6, ash.healthPerExtraPlayer)
        assertEquals(0.8, ash.knockbackResistance)
        assertNotNull(ash.loot.guaranteed.firstOrNull())
    }

    @Test fun shortTelegraphRaisedWithWarning() {
        val r = BossConfigLoader.load(
            mapOf(
                "x" to mapOf(
                    "base-health" to 100,
                    "moves" to mapOf("slam" to mapOf("type" to "leap-slam", "damage" to 10, "telegraph-ticks" to 4)),
                ),
            ),
        )
        assertEquals(10, r.value.getValue("x").moves.single().telegraphTicks)
        assertTrue(r.warnings.any { it.startsWith("bosses.x.moves.slam.telegraph-ticks") }, r.warnings.toString())
    }

    @Test fun unknownMoveTypeSkipped() {
        val r = BossConfigLoader.load(
            mapOf(
                "x" to mapOf(
                    "base-health" to 100,
                    "moves" to mapOf(
                        "bad" to mapOf("type" to "meteor"),
                        "ok" to mapOf("type" to "combo", "damage" to 5, "telegraph-ticks" to 12),
                    ),
                ),
            ),
        )
        assertEquals(listOf("ok"), r.value.getValue("x").moves.map { it.id })
        assertTrue(r.warnings.any { it.startsWith("bosses.x.moves.bad.type") }, r.warnings.toString())
    }

    @Test fun bossWithoutMovesIsSkipped() {
        val r = BossConfigLoader.load(mapOf("x" to mapOf("base-health" to 100)))
        assertTrue(r.value.isEmpty())
        assertTrue(r.warnings.any { it.startsWith("bosses.x") })
    }

    @Test fun badPhaseReferenceDropped() {
        val r = BossConfigLoader.load(
            mapOf(
                "x" to mapOf(
                    "base-health" to 100,
                    "moves" to mapOf("ok" to mapOf("type" to "combo", "damage" to 5, "telegraph-ticks" to 12)),
                    "phases" to listOf(mapOf("at" to 0.5, "add-moves" to listOf("ghost"))),
                ),
            ),
        )
        assertTrue(r.value.getValue("x").phases.single().addMoves.isEmpty())
        assertTrue(r.warnings.any { it.contains("ghost") })
    }
}
