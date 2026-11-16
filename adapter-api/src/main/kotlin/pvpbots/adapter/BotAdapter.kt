package pvpbots.adapter

interface BotAdapter {
    /** Unlisted in tab, no join message, never saved, view/sim distance 2. */
    fun spawn(spec: BotSpec, at: org.bukkit.Location): BotHandle

    /** No quit message, no save. */
    fun remove(bot: BotHandle)

    /** Applied during the bot's own vanilla tick. */
    fun setInput(bot: BotHandle, input: BotInput)

    fun attack(bot: BotHandle, target: org.bukkit.entity.Entity)
    fun swing(bot: BotHandle)
    fun startUsing(bot: BotHandle, hand: org.bukkit.inventory.EquipmentSlot)
    fun stopUsing(bot: BotHandle)
    fun selectSlot(bot: BotHandle, slot: Int)
    fun swapHands(bot: BotHandle)
}
