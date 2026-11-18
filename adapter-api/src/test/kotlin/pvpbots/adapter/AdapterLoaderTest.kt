package pvpbots.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdapterLoaderTest {
    @Test fun exact_1_21_11() = assertEquals("pvpbots.adapter.v1_21_11.NmsBotAdapter", AdapterLoader.classNameFor("1.21.11"))

    @Test fun patch_26_1_x() {
        assertEquals("pvpbots.adapter.v26_1.NmsBotAdapter", AdapterLoader.classNameFor("26.1"))
        assertEquals("pvpbots.adapter.v26_1.NmsBotAdapter", AdapterLoader.classNameFor("26.1.2"))
    }

    @Test fun v26_2() = assertEquals("pvpbots.adapter.v26_2.NmsBotAdapter", AdapterLoader.classNameFor("26.2.1"))
    @Test fun v26_3_selected() {
        assertEquals("pvpbots.adapter.v26_3.NmsBotAdapter", AdapterLoader.classNameFor("26.3"))
        assertEquals("pvpbots.adapter.v26_3.NmsBotAdapter", AdapterLoader.classNameFor("26.3.1"))
        assertNull(AdapterLoader.classNameFor("26.4"))
        assertEquals(listOf("1.21.11", "26.1.x", "26.2.x", "26.3.x"), AdapterLoader.SUPPORTED)
    }

    @Test fun notConfusedBy_26_10() = assertNull(AdapterLoader.classNameFor("26.10"))
    @Test fun unsupported() = assertNull(AdapterLoader.classNameFor("1.21.10"))
}
