package pvpbots.bukkit

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.persistence.PersistentDataType
import pvpbots.combat.KitSlots
import pvpbots.profile.DifficultyProfile

/**
 * Kits from config `kits:` and how they are put on a bot. Hotbar layout is [KitSlots]; the shield goes in the
 * offhand and, when the profile has `totem: true`, a totem goes in hotbar slot [KitSlots.TOTEM].
 */
class KitApplier(val kits: Map<String, Kit>) {
    /** Null / false / 0 in the config mean "not given". [armor] is helmet, chestplate, leggings, boots. */
    data class Kit(
        val armor: List<Material?>,
        val sword: Material?,
        val axe: Material?,
        val food: Material?,
        val foodAmount: Int,
        val shield: Boolean,
        val bow: Material?,
        val arrows: Int,
    )

    /**
     * Clears the bot and gives it the kit, full health/food and no effects. Unknown kit: empty inventory. In a kit arena
     * ([kitArena]) both sides fight in the same kit, so the profile's bonus totem is left out.
     */
    fun apply(p: Player, kitName: String, profile: DifficultyProfile, kitArena: Boolean = false) {
        val inv = p.inventory
        fill(inv, kitName)
        if (profile.totem && !kitArena) inv.setItem(KitSlots.TOTEM, ItemStack(Material.TOTEM_OF_UNDYING))
        inv.heldItemSlot = KitSlots.SWORD
        p.activePotionEffects.forEach { p.removePotionEffect(it.type) }
        p.health = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        p.foodLevel = 20
        p.saturation = 5f
        p.fireTicks = 0
        p.fallDistance = 0f
    }

    /**
     * A real player in a kit arena (§5.6): the same kit items as a bot gets (no totem), full health, food 20 and
     * saturation 5. Potion effects and XP are not touched. The caller must have stashed the inventory first (and taken
     * out anything that is not a kit item, see [isKitItem]). Every item given carries the [KIT_TAG] tag.
     */
    fun applyToPlayer(p: Player, kitName: String) {
        val inv = p.inventory
        fill(inv, kitName, tagged = true)
        inv.heldItemSlot = KitSlots.SWORD
        p.health = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        p.foodLevel = 20
        p.saturation = 5f
    }

    /** One fresh stack of every item [kitName] gives a player (armor, weapons, food, bow, arrows, shield). */
    fun templates(kitName: String): List<ItemStack> {
        val kit = kits[kitName] ?: return emptyList()
        val out = ArrayList<ItemStack>()
        kit.armor.filterNotNull().forEach { out += ItemStack(it) }
        kit.sword?.let { out += ItemStack(it) }
        kit.axe?.let { out += ItemStack(it) }
        kit.food?.let { out += ItemStack(it, kit.foodAmount.coerceIn(1, it.maxStackSize)) }
        kit.bow?.let { out += ItemStack(it) }
        if (kit.arrows > 0) out += ItemStack(Material.ARROW, kit.arrows.coerceAtMost(64))
        if (kit.shield) out += ItemStack(Material.SHIELD)
        return out
    }

    /**
     * Clears [inv] (main, armor, off-hand) and puts the kit in: armor, hotbar per [KitSlots], shield off-hand. Unknown
     * kit: empty. [tagged]: every item carries [KIT_TAG] (players; bots' items are never tagged).
     */
    fun fill(inv: PlayerInventory, kitName: String, tagged: Boolean = false) {
        inv.clear()
        inv.armorContents = arrayOfNulls(4)
        inv.setItemInOffHand(null)
        val kit = kits[kitName] ?: return
        fun item(m: Material, amount: Int = 1): ItemStack = ItemStack(m, amount).also { if (tagged) tag(it) }
        // Bukkit armorContents order is boots, leggings, chestplate, helmet.
        inv.armorContents = kit.armor.reversed().map { m -> m?.let { item(it) } }.toTypedArray()
        kit.sword?.let { inv.setItem(KitSlots.SWORD, item(it)) }
        kit.axe?.let { inv.setItem(KitSlots.AXE, item(it)) }
        kit.food?.let { inv.setItem(KitSlots.FOOD, item(it, kit.foodAmount.coerceIn(1, it.maxStackSize))) }
        kit.bow?.let { inv.setItem(KitSlots.BOW, item(it)) }
        if (kit.arrows > 0) inv.setItem(KitSlots.ARROWS, item(Material.ARROW, kit.arrows.coerceAtMost(64)))
        if (kit.shield) inv.setItemInOffHand(item(Material.SHIELD))
    }

    companion object {
        private const val ARMOR_PIECES = 4

        /** `pvpbots:kit` (byte 1) on the meta of every kit item given to a player: refill and restore take these back. */
        val KIT_TAG = NamespacedKey("pvpbots", "kit")

        fun tag(item: ItemStack) {
            item.editMeta { it.persistentDataContainer.set(KIT_TAG, PersistentDataType.BYTE, 1) }
        }

        /** A kit item the plugin gave a player (anything else in a kitted inventory belongs to the player). */
        fun isKitItem(item: ItemStack?): Boolean =
            item != null && !item.type.isAir && item.hasItemMeta() && item.itemMeta.persistentDataContainer.has(KIT_TAG, PersistentDataType.BYTE)

        /** [raw] is the parsed `kits:` section (name -> kit map). Warnings are prefixed `kits.<name>.<key>`. */
        fun load(raw: Map<String, Any?>, warnings: MutableList<String>): KitApplier {
            val out = LinkedHashMap<String, Kit>()
            for ((name, v) in raw) {
                val m = v as? Map<*, *>
                if (m == null) {
                    warnings += "kits.$name: expected a section, kit skipped"
                    continue
                }
                out[name] = parseKit("kits.$name", m, warnings)
            }
            return KitApplier(out)
        }

        private fun parseKit(path: String, m: Map<*, *>, warnings: MutableList<String>): Kit {
            fun material(key: String, value: Any?): Material? {
                if (absent(value)) return null
                val mat = (value as? String)?.let { Material.matchMaterial(it.trim()) }
                if (mat == null || !mat.isItem) {
                    warnings += "$path.$key: unknown item '$value', ignored"
                    return null
                }
                return mat
            }

            val armorRaw = m["armor"]
            val armor: List<Material?> = when {
                absent(armorRaw) -> List(ARMOR_PIECES) { null }
                armorRaw is List<*> -> {
                    if (armorRaw.size != ARMOR_PIECES) warnings += "$path.armor: expected 4 entries (helmet, chestplate, leggings, boots)"
                    List(ARMOR_PIECES) { i -> material("armor[$i]", armorRaw.getOrNull(i)) }
                }
                else -> {
                    warnings += "$path.armor: expected a list, ignored"
                    List(ARMOR_PIECES) { null }
                }
            }
            var food: Material? = null
            var foodAmount = 0
            val f = m["food"]
            when {
                absent(f) -> {}
                f is String -> { food = material("food", f); foodAmount = 1 }
                f is Map<*, *> -> {
                    food = material("food.item", f["item"])
                    foodAmount = (f["amount"] as? Number)?.toInt() ?: 1
                    if (foodAmount <= 0) food = null
                }
                else -> warnings += "$path.food: expected { item, amount }, ignored"
            }
            if (food != null && !food.isEdible) {
                warnings += "$path.food: $food is not edible, ignored"
                food = null
            }
            val s = m["shield"]
            val shield = when {
                absent(s) -> false
                s == true -> true
                else -> { warnings += "$path.shield: expected true or false, ignored"; false }
            }
            val a = m["arrows"]
            val arrows = when {
                absent(a) -> 0
                a is Number -> a.toInt().coerceAtLeast(0)
                else -> { warnings += "$path.arrows: expected a number, ignored"; 0 }
            }
            return Kit(
                armor = armor,
                sword = material("sword", m["sword"]),
                axe = material("axe", m["axe"]),
                food = food,
                foodAmount = foodAmount,
                shield = shield,
                bow = material("bow", m["bow"]),
                arrows = arrows,
            )
        }

        private fun absent(v: Any?): Boolean = v == null || v == false || (v is Number && v.toDouble() == 0.0)
    }
}
