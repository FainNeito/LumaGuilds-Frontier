package net.lumalyte.lg.interaction.listeners

import net.lumalyte.lg.application.services.FrontierLivingPolicy
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.entity.minecart.ExplosiveMinecart
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.event.player.*
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/** Frontier-only immersion and combat rules. MaceGuard remains responsible for combat retags. */
class FrontierLivingWorld(private val plugin: JavaPlugin) : Listener {
    val settings: YamlConfiguration
    private val riptideReady = mutableMapOf<UUID, Long>()

    init {
        val file = plugin.dataFolder.resolve("frontier-living-world.yml")
        if (!file.exists()) plugin.saveResource(file.name, false)
        settings = YamlConfiguration().apply { load(file) }
    }

    private fun announce(subject: Player, message: net.kyori.adventure.text.Component?) {
        if (message == null) return
        val radius = settings.getDouble("local-notices.radius", 200.0).coerceIn(0.0, 10000.0)
        plugin.server.onlinePlayers.filter { observer ->
            observer.canSee(subject) && observer.world == subject.world &&
                FrontierLivingPolicy.nearby(true, observer.location.distanceSquared(subject.location), radius)
        }.forEach { it.sendMessage(message) }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun join(event: PlayerJoinEvent) {
        if (!settings.getBoolean("local-notices.enabled", true)) return
        val message = event.joinMessage(); event.joinMessage(null); announce(event.player, message)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun quit(event: PlayerQuitEvent) {
        if (!settings.getBoolean("local-notices.enabled", true)) return
        val message = event.quitMessage(); event.quitMessage(null); announce(event.player, message)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun death(event: PlayerDeathEvent) {
        if (!settings.getBoolean("local-notices.enabled", true)) return
        val message = event.deathMessage(); event.deathMessage(null); announce(event.entity, message)
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun denyRiptide(event: PlayerRiptideEvent) {
        if (event.player.location.y <= event.player.world.seaLevel) return
        val remaining = (riptideReady[event.player.uniqueId] ?: 0) - System.currentTimeMillis()
        if (remaining > 0) {
            event.isCancelled = true
            event.player.sendMessage(FrontierTheme.message("Travel", "Riptide is ready in ${(remaining + 999) / 1000}s."))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun usedRiptide(event: PlayerRiptideEvent) {
        if (event.player.location.y <= event.player.world.seaLevel) return
        val now = System.currentTimeMillis()
        riptideReady.entries.removeIf { it.value <= now }
        val seconds = settings.getLong("combat.above-sea-riptide-cooldown-seconds", 10).coerceIn(0, 3600)
        riptideReady[event.player.uniqueId] = now + seconds * 1000
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun damage(event: EntityDamageByEntityEvent) {
        if (event.entity is Player) {
            val attacker = event.damager as? Player
            val spear = event.damageSource.damageType.key.key == "spear" ||
                attacker?.inventory?.itemInMainHand?.type?.name?.endsWith("_SPEAR") == true
            val cap = when {
                event.damager is ExplosiveMinecart || event.damageSource.directEntity is ExplosiveMinecart ->
                    settings.getDouble("combat.tnt-minecart-player-base-damage-cap", 20.0)
                spear -> settings.getDouble("combat.spear-player-base-damage-cap", 16.0)
                else -> return
            }
            event.damage = FrontierLivingPolicy.capDamage(event.damage, cap)
        }
    }
}
