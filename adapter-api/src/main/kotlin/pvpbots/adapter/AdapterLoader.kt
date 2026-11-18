package pvpbots.adapter

object AdapterLoader {
    val SUPPORTED: List<String> = listOf("1.21.11", "26.1.x", "26.2.x", "26.3.x")

    private val table: List<Pair<Regex, String>> = listOf(
        Regex("""^1\.21\.11$""") to "pvpbots.adapter.v1_21_11.NmsBotAdapter",
        Regex("""^26\.1(\.\d+)?$""") to "pvpbots.adapter.v26_1.NmsBotAdapter",
        Regex("""^26\.2(\.\d+)?$""") to "pvpbots.adapter.v26_2.NmsBotAdapter",
        Regex("""^26\.3(\.\d+)?$""") to "pvpbots.adapter.v26_3.NmsBotAdapter",
    )

    fun classNameFor(minecraftVersion: String): String? =
        table.firstOrNull { it.first.matches(minecraftVersion) }?.second
}
