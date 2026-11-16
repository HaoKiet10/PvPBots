package pvpbots.bukkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TickIsolationTest {
    @Test fun linkageErrorInOneBot_doesNotSkipTheOthers() {
        val ticked = mutableListOf<Int>()
        val failures = mutableListOf<Pair<Int, Throwable>>()
        TickIsolation.forEach(listOf(1, 2, 3), { i ->
            if (i == 2) throw NoSuchMethodError("FakeBot.doTick")
            ticked += i
        }) { i, e -> failures += i to e }
        assertEquals(listOf(1, 3), ticked)
        assertEquals(1, failures.size)
        assertEquals(2, failures[0].first)
        assertIs<NoSuchMethodError>(failures[0].second)
    }

    @Test fun exceptionsAndAbstractMethodErrors_areIsolated() {
        val failed = mutableListOf<Int>()
        TickIsolation.forEach(listOf(1, 2), { i ->
            if (i == 1) throw IllegalStateException("boom") else throw AbstractMethodError("x")
        }) { i, _ -> failed += i }
        assertEquals(listOf(1, 2), failed)
    }

    @Test fun virtualMachineErrors_areRethrown() {
        assertFailsWith<StackOverflowError> {
            TickIsolation.forEach(listOf(1), { throw StackOverflowError() }) { _, _ -> }
        }
    }

    @Test fun endFailed_removesEvenWhenNotifyThrows() {
        var removed = false
        var error: Throwable? = null
        TickIsolation.endFailed({ throw NoSuchFieldError("alive") }, { removed = true }) { error = it }
        assertTrue(removed)
        assertIs<NoSuchFieldError>(error)
    }

    @Test fun endFailed_removesAfterSuccessfulNotify() {
        val order = mutableListOf<String>()
        TickIsolation.endFailed({ order += "notify" }, { order += "remove" }) { order += "error" }
        assertEquals(listOf("notify", "remove"), order)
    }

    @Test fun guard_swallowsLinkageErrors() {
        var error: Throwable? = null
        TickIsolation.guard({ throw NoClassDefFoundError("x") }) { error = it }
        assertIs<NoClassDefFoundError>(error)
    }
}
