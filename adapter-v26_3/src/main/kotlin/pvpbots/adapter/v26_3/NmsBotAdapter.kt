package pvpbots.adapter.v26_3

import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Inventory
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Entity
import org.bukkit.inventory.EquipmentSlot
import pvpbots.adapter.BotAdapter
import pvpbots.adapter.BotHandle
import pvpbots.adapter.BotInput
import pvpbots.adapter.BotSpec
import java.util.UUID

/**
 * BotAdapter for Paper 26.3.x (unobfuscated, Mojang-named NMS). Instantiated reflectively by [pvpbots.adapter.AdapterLoader].
 * Every method must be called on the server main thread. Actions on a bot that has already been removed are no-ops.
 */
class NmsBotAdapter : BotAdapter {

    private class Handle(val bot: FakeBot) : BotHandle {
        override val id: UUID get() = bot.uuid
        override val bukkit: CraftPlayer get() = bot.bukkitEntity
        override fun toString(): String = "BotHandle(${bot.scoreboardName}, $id)"
    }

    private var distanceWarned = false

    override fun spawn(spec: BotSpec, at: Location): BotHandle {
        checkMainThread()
        TabVisibility.ensureRegistered()
        val bot = FakeBot.spawn(spec, at)
        // R10: per-player chunk load / tick distance 2 (ClientInformation already requests view distance 2).
        val craft = bot.bukkitEntity
        val view = runCatching { craft.viewDistance = 2 }
        val sim = runCatching { craft.simulationDistance = 2 }
        if ((view.isFailure || sim.isFailure) && !distanceWarned) {
            distanceWarned = true
            Bukkit.getLogger().warning(
                "[pvp-bots] bot distance setup: setViewDistance(2) ${outcome(view)}, setSimulationDistance(2) ${outcome(sim)}",
            )
        }
        return Handle(bot)
    }

    override fun remove(bot: BotHandle) {
        checkMainThread()
        val fake = fake(bot)
        if (fake.botRemoved) return
        fake.removeBot()
        TabVisibility.sendRemove(fake)
    }

    override fun setInput(bot: BotHandle, input: BotInput) {
        checkMainThread()
        fake(bot).input = input
    }

    override fun attack(bot: BotHandle, target: Entity) {
        checkMainThread()
        val fake = live(bot) ?: return
        fake.attack((target as CraftEntity).handle)
    }

    override fun swing(bot: BotHandle) {
        checkMainThread()
        val fake = live(bot) ?: return
        // 26.3: swing(hand) became swing(hand, SwingAnimation, broadcastToSelf). ServerPlayer.swingAndResetAttackStrength
        // broadcasts the arm animation to trackers and resets the attack cooldown (vanilla attack path).
        fake.swingAndResetAttackStrength(InteractionHand.MAIN_HAND, fake.mainHandItem.attackAnimation, false)
    }

    override fun startUsing(bot: BotHandle, hand: EquipmentSlot) {
        checkMainThread()
        val nmsHand = when (hand) {
            EquipmentSlot.HAND -> InteractionHand.MAIN_HAND
            EquipmentSlot.OFF_HAND -> InteractionHand.OFF_HAND
            else -> throw IllegalArgumentException("hand must be HAND or OFF_HAND, was $hand")
        }
        val fake = live(bot) ?: return
        // Same path as the use-item packet: starts eating, drinking, blocking, drawing a bow, ...
        fake.gameMode.useItem(fake, fake.level(), fake.getItemInHand(nmsHand), nmsHand)
    }

    override fun stopUsing(bot: BotHandle) {
        checkMainThread()
        val fake = live(bot) ?: return
        fake.releaseUsingItem()
    }

    override fun selectSlot(bot: BotHandle, slot: Int) {
        checkMainThread()
        require(slot in 0 until Inventory.getSelectionSize()) { "slot must be 0..8, was $slot" }
        val fake = live(bot) ?: return
        val inventory = fake.inventory
        if (inventory.selectedSlot == slot) return
        // As ServerGamePacketListenerImpl.handleSetCarriedItem: changing slot cancels a main-hand use.
        if (fake.usedItemHand == InteractionHand.MAIN_HAND) fake.stopUsingItem()
        inventory.selectedSlot = slot
    }

    override fun swapHands(bot: BotHandle) {
        checkMainThread()
        val fake = live(bot) ?: return
        if (fake.isSpectator) return
        // As the F key (SWAP_ITEM_WITH_OFFHAND), without the Bukkit swap event.
        val offHand = fake.getItemInHand(InteractionHand.OFF_HAND)
        fake.setItemInHand(InteractionHand.OFF_HAND, fake.getItemInHand(InteractionHand.MAIN_HAND))
        fake.setItemInHand(InteractionHand.MAIN_HAND, offHand)
        fake.stopUsingItem()
    }

    private fun fake(bot: BotHandle): FakeBot =
        (bot as? Handle)?.bot ?: throw IllegalArgumentException("Not a handle from this adapter: $bot")

    private fun live(bot: BotHandle): FakeBot? = fake(bot).takeUnless { it.botRemoved || it.isRemoved }

    private fun checkMainThread() {
        check(Bukkit.isPrimaryThread()) { "pvp-bots adapter calls must run on the server main thread" }
    }

    private fun outcome(r: Result<Unit>): String =
        r.fold({ "ok" }, { "failed (${it.javaClass.simpleName}: ${it.message})" })
}
