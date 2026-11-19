package pvpbots.combat

import pvpbots.math.Vec3
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TargetSelectorTest {
    private val wither = UUID(1, 1)
    private val zombie = UUID(1, 2)
    private val skeleton = UUID(1, 3)
    private val player = UUID(2, 1)

    private fun mob(id: UUID, pos: Vec3, hp: Double, target: UUID?) = MobView(id, pos, hp, target)
    private fun at(x: Double, y: Double, z: Double) = Vec3(x, y, z)

    @Test
    fun peelBeatsBoss() {
        val r = TargetSelector.pickAllyTarget(
            at(0.0, 0.0, 0.0),
            listOf(mob(wither, at(5.0, 0.0, 0.0), 300.0, null), mob(zombie, at(2.0, 0.0, 0.0), 20.0, player)),
            mapOf(player to at(1.0, 0.0, 0.0)),
        )
        assertEquals(zombie, r)
    }

    @Test
    fun highestMaxHealthFocus() {
        val r = TargetSelector.pickAllyTarget(
            at(0.0, 0.0, 0.0),
            listOf(mob(wither, at(9.0, 0.0, 0.0), 300.0, null), mob(zombie, at(2.0, 0.0, 0.0), 20.0, null)),
            emptyMap(),
        )
        assertEquals(wither, r)
    }

    @Test
    fun healthTie_nearestWins() {
        val r = TargetSelector.pickAllyTarget(
            at(0.0, 0.0, 0.0),
            listOf(mob(zombie, at(9.0, 0.0, 0.0), 20.0, null), mob(skeleton, at(3.0, 0.0, 0.0), 20.0, null)),
            emptyMap(),
        )
        assertEquals(skeleton, r)
    }

    @Test
    fun peelOnlyWithinFourBlocksOfPlayer() {
        val r = TargetSelector.pickAllyTarget(
            at(0.0, 0.0, 0.0),
            listOf(mob(wither, at(20.0, 0.0, 0.0), 300.0, null), mob(zombie, at(1.0, 0.0, 9.0), 20.0, player)),
            mapOf(player to at(0.0, 0.0, 0.0)),
        )
        assertEquals(wither, r)
    }

    @Test
    fun peelNearestToBotWins() {
        val r = TargetSelector.pickAllyTarget(
            at(0.0, 0.0, 0.0),
            listOf(mob(zombie, at(3.0, 0.0, 0.0), 20.0, player), mob(skeleton, at(1.0, 0.0, 0.0), 20.0, player)),
            mapOf(player to at(2.0, 0.0, 0.0)),
        )
        assertEquals(skeleton, r)
    }

    @Test
    fun noHostiles_null() {
        assertNull(TargetSelector.pickAllyTarget(at(0.0, 0.0, 0.0), emptyList(), emptyMap()))
    }

    @Test
    fun pickTeamTarget_nearestReachableEnemy() {
        val near = UUID(4, 1)
        val far = UUID(4, 2)
        val cut = UUID(4, 3)
        val enemies = mapOf(near to at(2.0, 0.0, 0.0), far to at(9.0, 0.0, 0.0), cut to at(1.0, 0.0, 0.0))
        assertEquals(cut, TargetSelector.pickTeamTarget(at(0.0, 0.0, 0.0), enemies) { true })
        assertEquals(near, TargetSelector.pickTeamTarget(at(0.0, 0.0, 0.0), enemies) { it != cut })
        assertEquals(far, TargetSelector.pickTeamTarget(at(0.0, 0.0, 0.0), enemies) { it == far })
        assertNull(TargetSelector.pickTeamTarget(at(0.0, 0.0, 0.0), enemies) { false })
        assertNull(TargetSelector.pickTeamTarget(at(0.0, 0.0, 0.0), emptyMap()) { true })
    }
}
