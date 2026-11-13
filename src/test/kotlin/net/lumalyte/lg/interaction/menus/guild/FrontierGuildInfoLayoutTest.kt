package net.lumalyte.lg.interaction.menus.guild

import net.lumalyte.lg.domain.entities.RelationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FrontierGuildInfoLayoutTest {
    @Test fun `alliance disabled centers remaining relations`() {
        assertEquals(listOf(3 to RelationType.TRUCE,5 to RelationType.ENEMY),frontierGuildInfoRelations(false))
        assertEquals(listOf(2 to RelationType.ALLY,4 to RelationType.TRUCE,6 to RelationType.ENEMY),frontierGuildInfoRelations(true))
    }
}
