package pvpbots.bukkit

import kotlin.random.Random

/** Signed skin textures from config `skins:`; an empty pool means bots spawn without textures. */
class SkinPool(val skins: List<Skin>) {
    data class Skin(val value: String, val signature: String)

    fun pick(rng: Random): Skin? = if (skins.isEmpty()) null else skins[rng.nextInt(skins.size)]

    companion object {
        /** [raw] is the parsed `skins:` value: a list of `{ value: "...", signature: "..." }`. */
        fun load(raw: Any?, warnings: MutableList<String>): SkinPool {
            if (raw == null) return SkinPool(emptyList())
            if (raw !is List<*>) {
                warnings += "skins: expected a list of { value, signature }, using no skins"
                return SkinPool(emptyList())
            }
            val out = ArrayList<Skin>()
            raw.forEachIndexed { i, entry ->
                val m = entry as? Map<*, *>
                val value = (m?.get("value") as? String)?.trim()
                val signature = (m?.get("signature") as? String)?.trim()
                if (value.isNullOrEmpty() || signature.isNullOrEmpty()) {
                    warnings += "skins[$i]: needs a non-empty value and signature, skipped"
                } else {
                    out += Skin(value, signature)
                }
            }
            return SkinPool(out)
        }
    }
}
