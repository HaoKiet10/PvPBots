package pvpbots.bukkit

import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
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

    /** Clears the bot and gives it the kit, full health/food and no effects. Unknown kit: empty inventory. */
    fun apply(p: Player, kitName: String, profile: DifficultyProfile) {
        val inv = p.inventory
        fill(inv, kitName)
        if (profile.totem) inv.setItem(KitSlots.TOTEM, ItemStack(Material.TOTEM_OF_UNDYING))
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
     * saturation 5. Potion effects and XP are not touched. The caller must have stashed the inventory first.
     */
    fun applyToPlayer(p: Player, kitName: String) {
        val inv = p.inventory
        fill(inv, kitName)
        inv.heldItemSlot = KitSlots.SWORD
        p.health = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        p.foodLevel = 20
        p.saturation = 5f
    }

    /** Clears [inv] (main, armor, off-hand) and puts the kit in: armor, hotbar per [KitSlots], shield off-hand. Unknown kit: empty. */
    fun fill(inv: PlayerInventory, kitName: String) {
        inv.clear()
        inv.armorContents = arrayOfNulls(4)
        inv.setItemInOffHand(null)
        val kit = kits[kitName] ?: return
        // Bukkit armorContents order is boots, leggings, chestplate, helmet.
        inv.armorContents = kit.armor.reversed().map { m -> m?.let { ItemStack(it) } }.toTypedArray()
        kit.sword?.let { inv.setItem(KitSlots.SWORD, ItemStack(it)) }
        kit.axe?.let { inv.setItem(KitSlots.AXE, ItemStack(it)) }
        kit.food?.let { inv.setItem(KitSlots.FOOD, ItemStack(it, kit.foodAmount.coerceIn(1, it.maxStackSize))) }
        kit.bow?.let { inv.setItem(KitSlots.BOW, ItemStack(it)) }
        if (kit.arrows > 0) inv.setItem(KitSlots.ARROWS, ItemStack(Material.ARROW, kit.arrows.coerceAtMost(64)))
        if (kit.shield) inv.setItemInOffHand(ItemStack(Material.SHIELD))
    }

    companion object {
        private const val ARMOR_PIECES = 4

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
