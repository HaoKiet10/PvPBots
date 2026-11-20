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

    /** Vanilla armour formula for full unenchanted diamond (armor 20, toughness 8). */
    private fun takenOnDiamond(raw: Double): Double {
        val reduction = minOf(20.0, maxOf(20.0 / 5.0, 20.0 - raw / (2.0 + 8.0 / 4.0))) / 25.0
        return raw * (1.0 - reduction)
    }

    private fun hitsToKill(raw: Double) = Math.ceil(20.0 / takenOnDiamond(raw)).toInt()

    @Test fun bigMovesKillFullDiamondInFourToFiveHits() {
        val big = setOf(MoveType.LEAP_SLAM, MoveType.SHOCKWAVE, MoveType.CHARGED_SHOT)
        for (b in BossConfigLoader.load(bundled()).value.values) {
            for (m in b.moves.filter { it.isDamaging }) {
                val n = hitsToKill(m.damage)
                assertTrue(n >= 4, "${b.id}.${m.id} kills in $n hits")
                if (m.type in big) assertTrue(n in 4..5, "${b.id}.${m.id} kills in $n hits")
            }
        }
    }

    @Test fun phaseScaledTelegraphKeepsTenTickTell() {
        for (b in BossConfigLoader.load(bundled()).value.values) {
            for (p in b.phases) for (m in b.moves.filter { it.isDamaging }) {
                assertTrue(p.scaledTelegraph(m) >= 10, "${b.id}.${m.id} at ${p.at}")
            }
        }
        val p = Phase(0.5, false, 1.0, 0.5, emptyList(), emptyList())
        val slam = Move("s", MoveType.LEAP_SLAM, 1, 0.0..5.0, 12, 10.0, 0, 0, emptyMap())
        val step = Move("b", MoveType.BACKSTEP, 1, 0.0..5.0, 6, 0.0, 0, 0, emptyMap())
        assertEquals(10, p.scaledTelegraph(slam))
        assertEquals(3, p.scaledTelegraph(step))
    }

    @Test fun roarWeightDefaultsToZeroAndBadKeysWarn() {
        @Suppress("UNCHECKED_CAST")
        val r = BossConfigLoader.load(
            mapOf<Any, Any?>(
                "x" to mapOf(
                    "base-health" to 100,
                    "moves" to mapOf<Any, Any?>(
                        "roar" to mapOf("type" to "roar"),
                        5 to mapOf("type" to "combo"),
                        "ok" to mapOf("type" to "combo", "damage" to 5, "telegraph-ticks" to 12),
                    ),
                ),
                7 to mapOf("base-health" to 1),
            ) as Map<String, Any?>,
        )
        assertEquals(0, r.value.getValue("x").moves.first { it.id == "roar" }.weight)
        assertTrue(r.warnings.any { it.startsWith("bosses.7") }, r.warnings.toString())
        assertTrue(r.warnings.any { it.startsWith("bosses.x.moves.5") }, r.warnings.toString())
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
