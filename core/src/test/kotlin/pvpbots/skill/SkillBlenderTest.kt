package pvpbots.skill

import pvpbots.profile.CritMode
import pvpbots.profile.Difficulty
import pvpbots.profile.ProfileLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillBlenderTest {
    private val a = ProfileLoader.defaults()

    @Test fun anchors_returnAnchorProfiles() {
        assertEquals(a.getValue(Difficulty.EASY), SkillBlender.startProfile(0.0, a, null))
        assertEquals(a.getValue(Difficulty.MEDIUM), SkillBlender.startProfile(33.0, a, null))
        assertEquals(a.getValue(Difficulty.HARD), SkillBlender.startProfile(67.0, a, null))
        assertEquals(a.getValue(Difficulty.PRO), SkillBlender.startProfile(100.0, a, null))
    }

    @Test fun midpoint_interpolatesNumbers() {
        val p = SkillBlender.startProfile(50.0, a, null)
        assertEquals(5.0, p.aimErrorDeg, 1e-9)
        assertEquals(32.5, p.turnSpeedDeg, 1e-9)
        assertEquals(4, p.reactionTicks)
        assertEquals(0.35, p.healAt, 1e-9)
    }

    @Test fun discrete_lowerUntilWithin5() {
        val below = SkillBlender.startProfile(61.9, a, null)
        assertEquals(CritMode.SOMETIMES, below.crits)
        assertEquals("iron", below.kit)
        val at = SkillBlender.startProfile(62.0, a, null)
        assertEquals(CritMode.OFTEN, at.crits)
        assertTrue(at.wTap)
        assertEquals("diamond", at.kit)
    }

    @Test fun kitOverride_wins() {
        assertEquals("netherite", SkillBlender.startProfile(10.0, a, "netherite").kit)
    }

    @Test fun retune_changesOnlyNumbers() {
        val base = SkillBlender.startProfile(50.0, a, null)
        val r = SkillBlender.retune(base, 90.0, a)
        assertEquals(base.crits, r.crits)
        assertEquals(base.wTap, r.wTap)
        assertEquals(base.strafe, r.strafe)
        assertEquals(base.shield, r.shield)
        assertEquals(base.totem, r.totem)
        assertEquals(base.kit, r.kit)
        assertTrue(r.aimErrorDeg < 3.0)
    }

    @Test fun rating_outOfRangeOrNaN_clampedOrDefaulted() {
        val s = SkillSettings()
        assertEquals(0.0, SkillBlender.sanitize(-20.0, s))
        assertEquals(100.0, SkillBlender.sanitize(500.0, s))
        assertEquals(50.0, SkillBlender.sanitize(Double.NaN, s))
        assertEquals(50.0, SkillBlender.sanitize(null, s))
    }
}
