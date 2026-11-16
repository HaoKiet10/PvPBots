package pvpbots.combat

import pvpbots.profile.DifficultyProfile
import kotlin.random.Random

object TrainerBrain {
    fun decide(p: Perception, prof: DifficultyProfile, s: BrainState, rng: Random): Intent =
        CombatLogic.decide(p, prof, s, rng, ally = false)
}

object AllyBrain {
    fun decide(p: Perception, prof: DifficultyProfile, s: BrainState, rng: Random): Intent =
        CombatLogic.decide(p, prof, s, rng, ally = true)
}
