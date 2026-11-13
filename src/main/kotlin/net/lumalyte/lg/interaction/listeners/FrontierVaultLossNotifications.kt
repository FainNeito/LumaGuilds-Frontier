package net.lumalyte.lg.interaction.listeners

import net.lumalyte.lg.application.services.MemberService
import net.lumalyte.lg.infrastructure.services.FrontierVaultLossInbox
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.UUID
import java.util.logging.Level

class FrontierVaultLossNotifications(
    private val plugin: JavaPlugin,
    private val members: MemberService,
    private val inbox: FrontierVaultLossInbox,
) : Listener {
    fun record(guild: UUID, name: String, attacker: String?) {
        try {
            val offline = members.getGuildMembers(guild).map { it.playerId }
                .filter { plugin.server.getPlayer(it)?.isOnline != true }.toSet()
            inbox.record(offline, name, attacker, Instant.now().toString())
        } catch (failure: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not store offline vault-loss notices for $guild", failure)
        }
    }

    @EventHandler fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            try {
                for (notice in inbox.pending(player.uniqueId)) {
                    val attacker = notice.attacker?.let { " Attacker: $it." } ?: " Attacker unknown."
                    player.sendMessage(FrontierTheme.message("Defeat",
                        "${notice.guild}'s Founding Vault was destroyed while you were away. All guild claims were lost.$attacker", true))
                    inbox.acknowledge(player.uniqueId, notice.id)
                }
            } catch (failure: Exception) {
                plugin.logger.log(Level.SEVERE, "Could not deliver offline vault-loss notices for ${player.uniqueId}", failure)
            }
        }, 40L)
    }
}
