package net.lumalyte.lg.config

import net.lumalyte.lg.interaction.listeners.FrontierTheme
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class FrontierThemeTest {
    @AfterEach fun reset() { FrontierTheme.enabled = false }
    @Test fun `SMP components are returned unchanged`() {
        FrontierTheme.enabled = false
        val original = Component.text("Original", NamedTextColor.GREEN)
        assertSame(original, FrontierTheme.present(original))
    }
    @Test fun `theme preserves commands text and custom colors while mapping legacy accents`() {
        FrontierTheme.enabled = true
        val custom = TextColor.color(0x123456)
        val original = Component.text("Guild ", NamedTextColor.GOLD)
            .append(Component.text("<player supplied>", custom))
            .clickEvent(ClickEvent.suggestCommand("/guild invite Player"))
        val result = FrontierTheme.present(original)
        assertEquals(FrontierTheme.gold, result.color())
        assertEquals(custom, result.children().single().color())
        assertEquals(original.clickEvent(), result.clickEvent())
        assertEquals("Guild <player supplied>", PlainTextComponentSerializer.plainText().serialize(result))
    }
    @Test fun `gradient prefix retains brackets and never interprets body markup`() {
        val result = FrontierTheme.message("Raid", "<red>literal</red>", true)
        assertEquals("[Raid] <red>literal</red>", PlainTextComponentSerializer.plainText().serialize(result))
        assertEquals(FrontierTheme.crimson, result.children().first().color())
    }
}
