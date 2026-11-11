package net.lumalyte.lg.interaction.listeners

import net.lumalyte.lg.application.services.FrontierOwner
import net.lumalyte.lg.application.services.FrontierOwnerType
import net.lumalyte.lg.application.services.MemberService
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** No provider is resolved until both delivery and the player's opt-in are enabled. */
class FrontierRaidNotifications(
    private val plugin: JavaPlugin,
    private val settings: YamlConfiguration,
    private val members: MemberService,
) {
    private val file = plugin.dataFolder.resolve("frontier-raid-alerts.yml")
    private val preferences = YamlConfiguration().apply { if (file.exists()) load(file) }
    private val sent = mutableMapOf<Pair<UUID,String>, Long>()

    fun start() {
        plugin.getCommand("raidalerts")?.setExecutor { sender, _, _, args ->
            if (sender is Player) {
                val path = "players.${sender.uniqueId}"
                when (args.firstOrNull()?.lowercase()) {
                    "on", "off" -> {
                        val before = preferences.getBoolean(path)
                        preferences.set(path, args[0].equals("on", true))
                        try {
                            val temporary = file.toPath().resolveSibling(file.name + ".tmp")
                            Files.writeString(temporary, preferences.saveToString())
                            Files.move(temporary,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                        } catch (failure: Exception) {
                            preferences.set(path,before)
                            sender.sendMessage(FrontierTheme.message("Raid", "Could not save your alert preference. Please try again later."))
                            return@setExecutor true
                        }
                    }
                    null, "status" -> Unit
                    else -> { sender.sendMessage("Use /raidalerts on|off|status"); return@setExecutor true }
                }
                sender.sendMessage(FrontierTheme.message("Raid", "Discord opt-in: ${if(preferences.getBoolean(path)) "on" else "off"}. " +
                    if (settings.getBoolean("discord.enabled",false)) "Delivery requires a linked DiscordSRV account." else "Discord delivery is currently disabled on this server."))
            }
            true
        }
    }

    fun notify(owner: FrontierOwner, kind: String, message: String) {
        if (!settings.getBoolean("discord.enabled",false) || !plugin.server.pluginManager.isPluginEnabled("DiscordSRV")) return
        val recipients = if (owner.type == FrontierOwnerType.PERSONAL) setOf(owner.id)
            else members.getGuildMembers(owner.id).map { it.playerId }.toSet()
        val now = System.currentTimeMillis()
        sent.entries.removeIf { now - it.value >= 300000 }
        for (id in recipients) {
            if (!preferences.getBoolean("players.$id",false)) continue
            val key = id to kind
            if (sent.containsKey(key)) continue
            sent[key] = now
            // Keep all optional-provider linkage outside the class loaded on servers without it.
            try { net.lumalyte.lg.infrastructure.services.FrontierDiscordDelivery.send(id,message) }
            catch (_: LinkageError) { plugin.logger.warning("Frontier Discord alert provider is incompatible; raid processing continues") }
            catch (_: Exception) { plugin.logger.fine("Frontier Discord alert unavailable; raid processing continues") }
        }
    }
}
