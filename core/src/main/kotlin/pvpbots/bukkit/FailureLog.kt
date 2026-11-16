package pvpbots.bukkit

/**
 * Log-flood guard for repeated failures. Free of Bukkit types so it can be unit-tested. The first failure with a given
 * [signature] is logged in full (stack trace); later ones are counted and reported as one summary line at most once
 * per [summaryIntervalMs].
 */
internal class FailureLog(private val summaryIntervalMs: Long = 60_000L) {
    sealed interface Action {
        /** Log the full stack trace. */
        data object Full : Action

        /** Log one line: [sinceLast] repeats since the previous report, [total] occurrences overall. */
        data class Summary(val sinceLast: Int, val total: Int) : Action

        /** Counted, not logged. */
        data object Silent : Action
    }

    private class Entry(var total: Int, var unreported: Int, var lastReportMs: Long)

    private val entries = HashMap<String, Entry>()

    fun record(signature: String, nowMs: Long): Action {
        val e = entries[signature]
        if (e == null) {
            entries[signature] = Entry(1, 0, nowMs)
            return Action.Full
        }
        e.total++
        e.unreported++
        if (nowMs - e.lastReportMs < summaryIntervalMs) return Action.Silent
        val n = e.unreported
        e.unreported = 0
        e.lastReportMs = nowMs
        return Action.Summary(n, e.total)
    }

    fun record(t: Throwable, nowMs: Long): Action = record(signature(t), nowMs)

    companion object {
        /** Exception class + the topmost frame from the plugin's own code (core or adapter), else the top frame. */
        fun signature(t: Throwable): String {
            val frame = t.stackTrace.firstOrNull { it.className.startsWith("pvpbots.") } ?: t.stackTrace.firstOrNull()
            return t.javaClass.name + " at " + (frame?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "?")
        }
    }
}
