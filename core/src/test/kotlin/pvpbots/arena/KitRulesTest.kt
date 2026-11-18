package pvpbots.arena

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class KitRulesTest {
    @Test fun enterSurvival_stashes() =
        assertEquals(Action.STASH_AND_GIVE, KitRules.action(inKitArena = true, wasInKitArena = false, gameMode = "SURVIVAL", hasStash = false, dead = false))

    @Test fun enterAdventure_stashes() =
        assertEquals(Action.STASH_AND_GIVE, KitRules.action(inKitArena = true, wasInKitArena = false, gameMode = "ADVENTURE", hasStash = false, dead = false))

    @Test fun enterCreative_none() =
        assertEquals(Action.NONE, KitRules.action(inKitArena = true, wasInKitArena = false, gameMode = "CREATIVE", hasStash = false, dead = false))

    @Test fun enterSpectator_none() =
        assertEquals(Action.NONE, KitRules.action(inKitArena = true, wasInKitArena = false, gameMode = "SPECTATOR", hasStash = false, dead = false))

    @Test fun leave_restores() =
        assertEquals(Action.RESTORE, KitRules.action(inKitArena = false, wasInKitArena = true, gameMode = "SURVIVAL", hasStash = true, dead = false))

    @Test fun enter_withExistingStash_restoresBeforeStashing() =
        assertEquals(Action.RESTORE_THEN_STASH_AND_GIVE, KitRules.action(inKitArena = true, wasInKitArena = false, gameMode = "SURVIVAL", hasStash = true, dead = false))

    @Test fun kitArenaToKitArena_restoresThenStashes() =
        assertEquals(Action.RESTORE_THEN_STASH_AND_GIVE, KitRules.action(inKitArena = true, wasInKitArena = true, gameMode = "SURVIVAL", hasStash = true, dead = false))

    @Test fun enterWhileDead_none() =
        assertEquals(Action.NONE, KitRules.action(inKitArena = true, wasInKitArena = false, gameMode = "SURVIVAL", hasStash = false, dead = true))

    /** A dead player's inventory is reset by the respawn: restoring now could lose the items; the respawn hook restores. */
    @Test fun leaveWhileDead_none() =
        assertEquals(Action.NONE, KitRules.action(inKitArena = false, wasInKitArena = true, gameMode = "SURVIVAL", hasStash = true, dead = true))

    @Test fun leave_withoutStash_none() =
        assertEquals(Action.NONE, KitRules.action(inKitArena = false, wasInKitArena = true, gameMode = "SURVIVAL", hasStash = false, dead = false))

    @Test fun enterCreative_withStash_restores() =
        assertEquals(Action.RESTORE, KitRules.action(inKitArena = true, wasInKitArena = false, gameMode = "CREATIVE", hasStash = true, dead = false))

    @Test fun nowhere_withStash_restores() =
        assertEquals(Action.RESTORE, KitRules.action(inKitArena = false, wasInKitArena = false, gameMode = "SURVIVAL", hasStash = true, dead = false))
}
