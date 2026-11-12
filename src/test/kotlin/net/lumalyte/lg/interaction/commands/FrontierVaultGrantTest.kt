package net.lumalyte.lg.interaction.commands
import io.mockk.*
import net.lumalyte.lg.application.services.ConfigService
import net.lumalyte.lg.config.*
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.koin.core.context.*
import org.koin.dsl.module
class FrontierVaultGrantTest {
 @Test fun `repeated getvault commands never create extra Frontier vault items`() {
  stopKoin()
  try {
   val config=mockk<ConfigService>();every { config.loadConfig() } returns MainConfig(frontier=FrontierConfig(enabled=true))
   startKoin {modules(module {single {config}})}
   val player=mockk<Player>(relaxed=true)
   val command=GuildCommand();repeat(3){command.onVaultGet(player)}
   verify(exactly=0){player.inventory}
   verify(exactly=3){player.sendMessage(any<net.kyori.adventure.text.Component>())}
  } finally {stopKoin();unmockkAll()}
 }
}
