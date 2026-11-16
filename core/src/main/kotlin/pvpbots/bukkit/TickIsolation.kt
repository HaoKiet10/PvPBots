package pvpbots.bukkit

/**
 * Failure isolation for per-bot work. Free of Bukkit types so it can be unit-tested. Everything thrown is caught —
 * including LinkageErrors (NoSuchMethodError, AbstractMethodError, ...) that a mismatched NMS adapter produces —
 * except [VirtualMachineError] (out of memory, stack overflow), which is rethrown.
 */
internal object TickIsolation {
    /** Runs [step] for each item; a failure goes to [onFailure] and the loop continues with the next item. */
    fun <T> forEach(items: Iterable<T>, step: (T) -> Unit, onFailure: (T, Throwable) -> Unit) {
        for (item in items) {
            try {
                step(item)
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                onFailure(item, e)
            }
        }
    }

    /** Runs [notify], then always [remove] (even if [notify] throws); a [notify] failure goes to [onError]. */
    fun endFailed(notify: () -> Unit, remove: () -> Unit, onError: (Throwable) -> Unit) {
        try {
            notify()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            onError(e)
        } finally {
            remove()
        }
    }

    /** Runs [action]; a failure (except [VirtualMachineError]) goes to [onError]. */
    fun guard(action: () -> Unit, onError: (Throwable) -> Unit) {
        try {
            action()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            onError(e)
        }
    }
}
