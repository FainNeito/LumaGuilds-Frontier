package net.lumalyte.lg.config

import io.mockk.*
import net.lumalyte.lg.infrastructure.services.ProgressionConfigService
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class FrontierQuestConfigTest {
    @TempDir lateinit var directory: Path
    @Test fun `frontier disables quests even when existing progression file enables them`() {
        directory.resolve("progression.yml").toFile().writeText("quests:\n  enabled: true\n")
        val config = YamlConfiguration()
        val plugin = mockk<Plugin>(relaxed = true)
        every { plugin.dataFolder } returns directory.toFile()
        every { plugin.config } returns config
        val loader = ProgressionConfigService(plugin)
        assertTrue(loader.loadProgressionConfig().quests.enabled)
        config.set("frontier.enabled", true)
        assertFalse(loader.reloadProgressionConfig().quests.enabled)
        config.set("frontier.enabled", false)
        assertTrue(loader.reloadProgressionConfig().quests.enabled)
    }
}
