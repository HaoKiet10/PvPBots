package pvpbots.skill

import kotlin.test.Test
import kotlin.test.assertEquals

class FightJudgeTest {
    private val s = SkillSettings()
    private fun next(w: Window, cur: Double = 50.0, start: Double = 50.0) = FightJudge.next(cur, start, w, s)

    @Test fun playerFarBehind_stepsDown() = assertEquals(47.0, next(Window(0.3, 0.9, 2.0, 10.0)))
    @Test fun playerFarAhead_stepsUp() = assertEquals(53.0, next(Window(0.9, 0.3, 10.0, 2.0)))
    @Test fun closeFight_noChange() = assertEquals(50.0, next(Window(0.6, 0.6, 5.0, 5.0)))

    @Test fun inDeadZone_oneSidedDamage_steps() {
        assertEquals(53.0, next(Window(0.6, 0.6, 9.0, 1.0)))
        assertEquals(47.0, next(Window(0.6, 0.6, 1.0, 9.0)))
    }

    @Test fun targetLead_biasesTowardBot() = assertEquals(50.0, next(Window(0.75, 0.55, 0.0, 0.0)))

    @Test fun maxShift_capped() {
        assertEquals(65.0, next(Window(0.9, 0.3, 10.0, 2.0), cur = 65.0))
        assertEquals(35.0, next(Window(0.3, 0.9, 2.0, 10.0), cur = 35.0))
    }

    @Test fun noDamageTraded_usesHealthOnly() = assertEquals(50.0, next(Window(0.6, 0.6, 0.0, 0.0)))
}
