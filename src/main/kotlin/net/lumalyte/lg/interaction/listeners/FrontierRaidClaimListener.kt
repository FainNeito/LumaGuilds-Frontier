package net.lumalyte.lg.interaction.listeners

import net.kyori.adventure.text.event.ClickEvent
import net.lumalyte.lg.application.services.FrontierRaidClaimCheck
import net.lumalyte.lg.application.services.FrontierRaidClaims
import net.lumalyte.lg.application.services.GuildService
import net.lumalyte.lg.application.services.MemberService
import net.lumalyte.lg.config.FrontierConfig
import net.lumalyte.lg.domain.entities.RankPermission
import net.lumalyte.lg.domain.entities.VaultStatus
import net.lumalyte.lg.domain.values.FrontierChunk
import net.lumalyte.lg.common.PluginKeys
import net.lumalyte.lg.infrastructure.services.FrontierRaidBannerCleanup
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/** /raidclaim previews an edge-adjacent wilderness chunk before writing a temporary record. */
class FrontierRaidClaimListener(
    private val plugin: JavaPlugin,
    private val config: FrontierConfig,
    private val raids: FrontierRaidClaims,
    private val guilds: GuildService,
    private val members: MemberService,
    private val cleanup: FrontierRaidBannerCleanup,
    private val clock: () -> Long = System::currentTimeMillis,
) : Listener {
    private data class Preview(val guildId: UUID, val target: UUID, val chunk: FrontierChunk, val expires: Long)
    private val previews = mutableMapOf<UUID, Preview>()

    fun start() {
        plugin.getCommand("raidclaim")?.setExecutor { sender, _, _, args ->
            if (sender is Player) command(sender, args) else sender.sendMessage("Only players can use /raidclaim.")
            true
        }
        plugin.getCommand("raidclaim")?.tabCompleter = org.bukkit.command.TabCompleter { _, _, _, args ->
            if (args.size == 1) listOf("confirm", "cancel", "abandon", "status").filter { it.startsWith(args[0], true) }
            else emptyList()
        }
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val now = clock()
            previews.entries.removeIf { now >= it.value.expires }
            try { raids.reconcile(now, cleanup::remove) }
            catch (failure: Exception) { plugin.logger.log(java.util.logging.Level.SEVERE, "Raid claim cleanup failed; retrying", failure) }
        }, 20L, 100L)
    }

    private fun tell(player: Player, text: String, danger: Boolean = false) {
        player.sendMessage(FrontierTheme.message("Raid claim", text, danger))
    }

    private fun guild(player: Player): UUID? {
        val manageable = guilds.getPlayerGuilds(player.uniqueId).filter { guild ->
            members.hasPermission(player.uniqueId, guild.id, RankPermission.MANAGE_CLAIMS) &&
                members.hasPermission(player.uniqueId, guild.id, RankPermission.PLACE_WAR_BANNER)
        }
        if (manageable.size != 1) {
            tell(player, if (manageable.isEmpty()) "Your guild rank needs both Manage Claims and Place War Banner permissions."
            else "You have multiple eligible guilds. Ask staff to choose a single Frontier guild.", true)
            return null
        }
        val guild = manageable.single()
        if (guild.vaultStatus != VaultStatus.AVAILABLE) {
            tell(player, "Your Founding Vault must be placed before staging a raid.", true)
            return null
        }
        return guild.id
    }

    private fun inWorld(player: Player): Boolean {
        if (player.world.name == config.worldName && player.world.environment == World.Environment.NORMAL &&
            player.world.worldBorder.isInside(player.location)) return true
        tell(player, "Use /raidclaim inside the Frontier Overworld border.", true)
        return false
    }

    private fun command(player: Player, args: Array<out String>) {
        when (args.firstOrNull()?.lowercase()) {
            "cancel" -> { previews.remove(player.uniqueId); tell(player, "Preview cancelled."); return }
            "status" -> {
                val guildId = guild(player) ?: return
                val current = raids.forGuild(guildId, clock())
                if (current == null) tell(player, "No active raid claim. Stand in a border wilderness chunk and run /raidclaim.")
                else tell(player, "${current.chunk.x}, ${current.chunk.z} against ${guilds.getGuild(current.defender)?.name ?: "unknown"}; " +
                    if (current.bannerId == null) "place your war banner within ${((current.graceEnd - clock()).coerceAtLeast(0) + 999) / 1000}s."
                    else "banner deployed; capture the objective before the raid expires.")
                return
            }
            "abandon" -> {
                val guildId = guild(player) ?: return
                val removed = runCatching { raids.abandon(guildId, clock()) }.getOrElse {
                    plugin.logger.log(java.util.logging.Level.SEVERE, "Raid claim abandon failed", it)
                    tell(player, "Raid claim storage is unavailable. Please retry.", true)
                    return
                }
                if (removed) tell(player, "Unbannered raid claim abandoned.")
                else tell(player, "Only an unbannered staging claim can be abandoned. Defenders must destroy an active banner.", true)
                return
            }
            "confirm" -> { confirm(player); return }
        }
        val guildId = guild(player) ?: return
        if (!inWorld(player)) return
        val target = if (args.isEmpty()) null else guilds.getGuildByName(args.joinToString(" "))?.id
        if (args.isNotEmpty() && target == null) { tell(player, "Target guild not found.", true); return }
        val chunk = FrontierChunk(player.world.uid, player.chunk.x, player.chunk.z)
        when (val result = raids.check(guildId, chunk, target, clock())) {
            is FrontierRaidClaimCheck.Rejected -> tell(player, result.reason, true)
            is FrontierRaidClaimCheck.Allowed -> {
                previews[player.uniqueId] = Preview(guildId, result.defender, chunk, clock() + 30_000)
                tell(player, "Preview: a one-chunk forward position at ${chunk.x}, ${chunk.z} against ${guilds.getGuild(result.defender)?.name}. Place your war banner here within two minutes after confirming.")
                player.sendMessage(FrontierTheme.message("Confirm", "[Stage raid claim]").clickEvent(ClickEvent.runCommand("/raidclaim confirm")))
                player.sendMessage(FrontierTheme.message("Cancel", "[Cancel preview]").clickEvent(ClickEvent.runCommand("/raidclaim cancel")))
            }
        }
    }

    private fun confirm(player: Player) {
        val preview = previews.remove(player.uniqueId)
        if (preview == null || clock() >= preview.expires) { tell(player, "Use /raidclaim to preview first.", true); return }
        if (guild(player) != preview.guildId || !inWorld(player) ||
            FrontierChunk(player.world.uid, player.chunk.x, player.chunk.z) != preview.chunk) {
            tell(player, "Your location or guild permissions changed. Preview again.", true); return
        }
        val result = runCatching { raids.create(preview.guildId, preview.chunk, preview.target, clock()) }.getOrElse {
            plugin.logger.log(java.util.logging.Level.SEVERE, "Raid claim creation failed", it)
            tell(player, "Raid claim storage is unavailable. Check /raidclaim status before retrying.", true)
            return
        }
        when (result) {
            is FrontierRaidClaimCheck.Rejected -> tell(player, result.reason, true)
            is FrontierRaidClaimCheck.Allowed -> {
                val attacker = guilds.getGuild(preview.guildId)?.name ?: "An attacking guild"
                val defender = guilds.getGuild(result.defender)?.name ?: "the defenders"
                val text = "$attacker has staged a raid claim beside $defender at chunk ${preview.chunk.x}, ${preview.chunk.z}. Their war banner must be placed within two minutes."
                Bukkit.getOnlinePlayers().filter { it.world.uid == preview.chunk.worldId }.forEach { tell(it, text, true) }
                plugin.logger.info("[Frontier] $text")
                FrontierTheme.cue(player, FrontierTheme.Cue.SUCCESS)
            }
        }
    }

    /** Temporary claims cannot become permanent storage/farm infrastructure. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val chunk = FrontierChunk(event.block.world.uid, event.block.chunk.x, event.block.chunk.z)
        if (raids.at(chunk, clock()) == null) return
        val meta = event.itemInHand.itemMeta
        if (event.block.type == Material.SPAWNER ||
            meta?.persistentDataContainer?.has(PluginKeys.GUILD_VAULT_ID, PersistentDataType.STRING) == true) {
            event.isCancelled = true
            tell(event.player, "Spawners and Founding Vaults cannot be placed in temporary raid claims.", true)
        }
    }
}
