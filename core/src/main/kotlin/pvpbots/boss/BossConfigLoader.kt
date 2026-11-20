package pvpbots.boss

import pvpbots.config.ConfigReader
import pvpbots.config.Loaded

/** Loads the `bosses:` section. Warnings are prefixed `bosses.<id>.<key>`; broken bosses/moves are skipped. */
object BossConfigLoader {
    const val MIN_TELEGRAPH_TICKS = Move.MIN_TELEGRAPH_TICKS

    private val DAMAGING = Move.DAMAGING_TYPES
    private val COMMON_MOVE_KEYS = setOf("type", "weight", "range", "telegraph-ticks", "damage", "cooldown-ticks", "recovery-ticks")

    fun load(section: Map<String, Any?>): Loaded<Map<String, BossType>> {
        val warnings = mutableListOf<String>()
        val root = ConfigReader(section, "bosses", warnings)
        val out = LinkedHashMap<String, BossType>()
        for (id in (section as Map<*, *>).keys) {
            if (id !is String) {
                warnings += "bosses.$id: boss ids must be text, entry skipped"
                continue
            }
            val r = root.section(id) ?: continue // a non-section entry was already warned about
            parseBoss(id, section[id] as Map<*, *>, r, warnings)?.let { out[id] = it }
        }
        return Loaded(out, warnings)
    }

    private fun parseBoss(id: String, raw: Map<*, *>, r: ConfigReader, warnings: MutableList<String>): BossType? {
        val health = r.double("base-health")
        if (health == null || health <= 0.0) {
            r.warn("base-health", "required and must be > 0, boss skipped")
            return null
        }
        val moves = parseMoves(r, raw["moves"], warnings)
        if (moves.isEmpty()) {
            r.warn("moves", "needs at least one valid move, boss skipped")
            return null
        }
        val ids = moves.map { it.id }.toSet()
        val phases = parsePhases(r, raw["phases"], ids, warnings)

        var kb = r.double("knockback-resistance") ?: 0.8
        if (kb !in 0.0..1.0) {
            r.warn("knockback-resistance", "must be between 0 and 1, using default 0.8")
            kb = 0.8
        }
        var speed = r.double("move-speed") ?: 1.0
        if (speed <= 0.0) {
            r.warn("move-speed", "must be > 0, using default 1")
            speed = 1.0
        }
        val perExtra = r.nonNegDouble("health-per-extra-player", 0.6)

        return BossType(
            id = id,
            name = r.string("name", id),
            skin = parseSkin(r),
            baseHealth = health,
            healthPerExtraPlayer = perExtra,
            gear = parseGear(r, raw["gear"]),
            enchants = parseEnchants(r, raw["enchants"]),
            moveSpeed = speed,
            knockbackResistance = kb,
            moves = moves,
            phases = phases,
            loot = parseLoot(r, raw["loot"], warnings),
            xp = r.int("xp", 0),
            commands = stringList(r, "commands", raw["commands"]),
            cooldownSeconds = r.int("cooldown-seconds", 600),
        )
    }

    private fun parseSkin(r: ConfigReader): BossSkin? {
        val s = r.section("skin") ?: return null
        val value = s.string("value", "")
        val sig = s.string("signature", "")
        if (value.isEmpty() || sig.isEmpty()) {
            r.warn("skin", "needs both value and signature, skin ignored")
            return null
        }
        return BossSkin(value, sig)
    }

    private fun parseGear(r: ConfigReader, raw: Any?): Map<String, String> {
        if (raw == null) return emptyMap()
        if (raw !is Map<*, *>) {
            r.warn("gear", "expected a section, ignored")
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        for ((k, v) in raw) {
            if (k is String && v is String && v.isNotBlank()) out[k] = v else r.warn("gear.$k", "expected a material name, ignored")
        }
        return out
    }

    private fun parseEnchants(r: ConfigReader, raw: Any?): Map<String, Map<String, Int>> {
        if (raw == null) return emptyMap()
        if (raw !is Map<*, *>) {
            r.warn("enchants", "expected a section, ignored")
            return emptyMap()
        }
        val out = LinkedHashMap<String, Map<String, Int>>()
        for ((slot, v) in raw) {
            if (slot !is String || v !is Map<*, *>) {
                r.warn("enchants.$slot", "expected enchantment: level pairs, ignored")
                continue
            }
            val m = LinkedHashMap<String, Int>()
            for ((e, lvl) in v) {
                val n = (lvl as? Number)?.toInt()
                if (e is String && n != null && n > 0) m[e] = n else r.warn("enchants.$slot.$e", "expected a level >= 1, ignored")
            }
            out[slot] = m
        }
        return out
    }

    private fun stringList(r: ConfigReader, key: String, raw: Any?): List<String> {
        if (raw == null) return emptyList()
        if (raw !is List<*>) {
            r.warn(key, "expected a list, ignored")
            return emptyList()
        }
        return raw.mapIndexedNotNull { i, v ->
            if (v is String && v.isNotBlank()) v else {
                r.warn("$key[$i]", "expected text, ignored")
                null
            }
        }
    }

    private fun parseMoves(r: ConfigReader, raw: Any?, warnings: MutableList<String>): List<Move> {
        if (raw == null) return emptyList()
        if (raw !is Map<*, *>) {
            r.warn("moves", "expected a section of moves")
            return emptyList()
        }
        val moves = r.section("moves")!!
        val out = ArrayList<Move>()
        for (key in raw.keys) {
            if (key !is String) {
                m0Warn(r, key, warnings)
                continue
            }
            val mid = key
            val m = moves.section(mid) ?: continue
            val mraw = raw[key] as Map<*, *>
            val typeName = (mraw["type"] as? String)?.trim()?.replace('-', '_')?.uppercase()
            val type = MoveType.entries.firstOrNull { it.name == typeName }
            if (type == null) {
                m.warn("type", "unknown move type '${mraw["type"]}', move skipped")
                continue
            }
            var telegraph = m.int("telegraph-ticks", if (type in DAMAGING) 20 else 0)
            val damage = m.nonNegDouble("damage", 0.0)
            if (type in DAMAGING && damage > 0.0 && telegraph < MIN_TELEGRAPH_TICKS) {
                m.warn("telegraph-ticks", "must be >= $MIN_TELEGRAPH_TICKS so the tell is readable, raised to $MIN_TELEGRAPH_TICKS")
                telegraph = MIN_TELEGRAPH_TICKS
            }
            val params = LinkedHashMap<String, Double>()
            for ((k, v) in mraw) {
                if (k is String && k !in COMMON_MOVE_KEYS && v is Number && v.toDouble().isFinite()) params[k] = v.toDouble()
            }
            out += Move(
                id = mid,
                type = type,
                weight = m.int("weight", if (type == MoveType.ROAR) 0 else 1),
                range = parseRange(m, mraw["range"]),
                telegraphTicks = telegraph,
                damage = damage,
                cooldownTicks = m.int("cooldown-ticks", 0),
                recoveryTicks = m.int("recovery-ticks", 0),
                params = params,
            )
        }
        return out
    }

    private fun m0Warn(r: ConfigReader, key: Any?, warnings: MutableList<String>) {
        warnings += "${r.key("moves")}.$key: move ids must be text, move skipped"
    }

    private fun parseRange(m: ConfigReader, raw: Any?): ClosedFloatingPointRange<Double> {
        val default = 0.0..100.0
        if (raw == null) return default
        val l = raw as? List<*>
        val lo = (l?.getOrNull(0) as? Number)?.toDouble()
        val hi = (l?.getOrNull(1) as? Number)?.toDouble()
        if (l == null || l.size != 2 || lo == null || hi == null || lo < 0 || lo > hi) {
            m.warn("range", "expected [min, max] with 0 <= min <= max, using default 0..100")
            return default
        }
        return lo..hi
    }

    private fun parsePhases(r: ConfigReader, raw: Any?, moveIds: Set<String>, warnings: MutableList<String>): List<Phase> {
        if (raw == null) return emptyList()
        if (raw !is List<*>) {
            r.warn("phases", "expected a list, ignored")
            return emptyList()
        }
        val out = ArrayList<Phase>()
        raw.forEachIndexed { i, e ->
            val path = "phases[$i]"
            if (e !is Map<*, *>) {
                r.warn(path, "expected a section, ignored")
                return@forEachIndexed
            }
            val p = ConfigReader(e, r.key(path), warnings)
            val at = p.double("at")
            if (at == null || at <= 0.0 || at >= 1.0) {
                p.warn("at", "required, between 0 and 1 (exclusive), phase skipped")
                return@forEachIndexed
            }
            var ms = p.double("move-speed") ?: 1.0
            if (ms <= 0.0) { p.warn("move-speed", "must be > 0, using 1"); ms = 1.0 }
            var ts = p.double("telegraph-scale") ?: 1.0
            if (ts <= 0.0) { p.warn("telegraph-scale", "must be > 0, using 1"); ts = 1.0 }
            fun refs(key: String): List<String> = stringList(p, key, e[key]).filter {
                (it in moveIds).also { ok -> if (!ok) p.warn(key, "unknown move '$it', ignored") }
            }
            out += Phase(at, p.boolean("roar", false), ms, ts, refs("add-moves"), refs("remove-moves"))
        }
        return out.sortedByDescending { it.at }
    }

    private fun parseLoot(r: ConfigReader, raw: Any?, warnings: MutableList<String>): LootTable {
        val empty = LootTable(emptyList(), 0, emptyList())
        if (raw == null) return empty
        val s = r.section("loot") ?: return empty
        val m = raw as Map<*, *>
        return LootTable(lootList(s, "guaranteed", m["guaranteed"], 1.0, warnings), s.int("rolls", 0), lootList(s, "table", m["table"], 1.0, warnings))
    }

    private fun lootList(s: ConfigReader, key: String, raw: Any?, defaultChance: Double, warnings: MutableList<String>): List<LootEntry> {
        if (raw == null) return emptyList()
        if (raw !is List<*>) {
            s.warn(key, "expected a list, ignored")
            return emptyList()
        }
        val out = ArrayList<LootEntry>()
        raw.forEachIndexed { i, e ->
            if (e !is Map<*, *>) {
                s.warn("$key[$i]", "expected { item, amount, chance }, ignored")
                return@forEachIndexed
            }
            val p = ConfigReader(e, s.key("$key[$i]"), warnings)
            val item = (e["item"] as? String)?.takeIf { it.isNotBlank() }
            if (item == null) {
                p.warn("item", "required, entry skipped")
                return@forEachIndexed
            }
            var chance = p.double("chance") ?: defaultChance
            if (chance !in 0.0..1.0) { p.warn("chance", "must be between 0 and 1, using $defaultChance"); chance = defaultChance }
            out += LootEntry(item, p.int("amount", 1, min = 1), chance)
        }
        return out
    }
}
