package net.lumalyte.lg.interaction.listeners

import org.junit.jupiter.api.Test
import kotlin.test.*

class FrontierCircuitTraceTest {
    @Test fun `traces only connected nodes and terminates cycles`() {
        val edges=mapOf("control" to listOf("wire"), "wire" to listOf("control","repeater","tnt"), "repeater" to listOf("wire","tnt2"))
        val found=FrontierCircuitTrace.find("control", 20, { edges[it].orEmpty() }, { it.startsWith("tnt") })
        assertEquals(setOf("tnt","tnt2"),found)
        assertFalse("unrelated" in found.orEmpty())
    }
    @Test fun `overly large circuits fail closed`() {
        assertNull(FrontierCircuitTrace.find(0,3,{ listOf(it+1) },{ false }))
    }
}
