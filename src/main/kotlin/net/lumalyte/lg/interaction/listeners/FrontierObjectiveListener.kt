package net.lumalyte.lg.interaction.listeners

import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.services.*
import net.lumalyte.lg.infrastructure.services.FrontierObjectives
import net.lumalyte.lg.infrastructure.adapters.bukkit.toPosition3D
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.logging.Level

class FrontierObjectiveListener(
    private val objectives: FrontierObjectives,
    private val claims: ClaimRepository,
    private val vaults: GuildVaultService,
    private val members: MemberService,
    private val plugin: JavaPlugin,
    private val config: net.lumalyte.lg.config.MainConfig = net.lumalyte.lg.config.MainConfig(),
    private val partitions: net.lumalyte.lg.application.persistence.PartitionRepository? = null,
) : Listener {
    var raidNotifications: FrontierRaidNotifications? = null
    var frontierWars: net.lumalyte.lg.infrastructure.services.FrontierWars? = null
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        val guild = if (block.type == Material.CHEST) vaults.getGuildForVaultChest(block.location) else null
        val claim = if (block.type == Material.BARREL || block.type == Material.BELL)
            objectives.soloAt(block.location) else null
        if (guild == null && (claim == null || claim.teamId != null || !objectives.primaryAnchor(claim))) return
        event.isCancelled = true // Never allow vanilla drops or the legacy destruction path.
        if (guild == null && (claim!!.teamId != null || !objectives.primaryAnchor(claim))) {
            event.player.sendActionBar(FrontierTheme.message("Raid", "Raid the territory's Founding Vault."))
            return
        }
        val owner = if (guild != null) FrontierOwner(FrontierOwnerType.GUILD, guild.id)
            else FrontierOwner(FrontierOwnerType.PERSONAL, claim!!.playerId)
        val defender = if (guild != null) members.getMember(event.player.uniqueId, guild.id) != null
            else claim!!.playerId == event.player.uniqueId
        try {
            val result = objectives.siege.hit(owner, FrontierObjectives.key(block.location), defender, Instant.now())
            val message = when (result) {
                FrontierSiegeResult.Protected -> "Objectives are protected outside the raid window."
                FrontierSiegeResult.Defender -> if (guild != null) "Use sneak-right-click or /vaultpickup to pack your vault outside raids." else "Use /vaultpickup to surrender all claims and pack this vault outside raids."
                FrontierSiegeResult.TooSoon -> "Wait two seconds between objective hits."
                FrontierSiegeResult.DifferentObjective -> "This owner already has an objective under attack."
                is FrontierSiegeResult.Progress -> "Objective damage: ${result.hits}/${result.required}"
                is FrontierSiegeResult.Cooldown -> "Objective replacement is locked until ${result.until}."
                FrontierSiegeResult.Defeated -> "Objective destroyed. Its owner's claims have fallen."
            }
            event.player.sendActionBar(FrontierTheme.message("Raid", message, true))
            if (result is FrontierSiegeResult.Progress) {
                FrontierTheme.cue(event.player, FrontierTheme.Cue.HIT)
                underAttack(owner, event.player.name)
            }
            if (result == FrontierSiegeResult.Defeated) {
                fallen(owner, guild?.name ?: claim!!.name, event.player)
            }
        } catch (failure: Exception) {
            plugin.logger.log(Level.SEVERE, "Frontier objective operation failed for $owner", failure)
            event.player.sendMessage(FrontierTheme.message("Raid", "Objective cleanup is pending. Please contact staff.", true))
        }
    }

    private fun defending(player: org.bukkit.entity.Player, owner: FrontierOwner): Boolean =
        if (owner.type == FrontierOwnerType.GUILD) members.getMember(player.uniqueId, owner.id) != null
        else player.uniqueId == owner.id

    private fun underAttack(owner: FrontierOwner, attacker: String) {
        raidNotifications?.notify(owner, "raid", "$attacker is damaging your Frontier Founding Vault.")
        plugin.server.onlinePlayers.filter { defending(it, owner) }.forEach {
            FrontierFeedback.show(it, "raid", "VAULT UNDER ATTACK", "$attacker is damaging your Founding Vault",
                danger = true, important = true)
        }
    }

    private fun fallen(owner: FrontierOwner, name: String, attacker: org.bukkit.entity.Player?) {
        frontierWars?.vaultFallen(owner,attacker?.let { members.getPlayerGuilds(it.uniqueId) } ?: emptySet())
        raidNotifications?.notify(owner, "defeat", "Your Frontier Founding Vault was destroyed. All territory claims have fallen.")
        plugin.server.onlinePlayers.filter { defending(it, owner) }.forEach {
            it.sendMessage(FrontierTheme.message("Defeat", "Your Founding Vault was destroyed. ALL territory claims have fallen.", true))
            FrontierFeedback.show(it, "defeat", "DEFEAT", "Your vault has fallen · All claims lost",
                danger = true, important = true)
        }
        // TNT without a player source still causes defeat, but never invents a winning guild.
        if (attacker == null || defending(attacker, owner)) return
        val attackingGuilds = members.getPlayerGuilds(attacker.uniqueId)
        plugin.server.onlinePlayers.filter { !defending(it, owner) &&
            (it.uniqueId == attacker.uniqueId || members.getPlayerGuilds(it.uniqueId).any(attackingGuilds::contains))
        }.forEach {
            it.sendMessage(FrontierTheme.message("Victory", "${attacker.name} destroyed $name's Founding Vault. Its territory is now unclaimed."))
            FrontierFeedback.show(it, "victory", "VICTORY", "$name · Vault destroyed", important = true)
        }
    }

    private fun claimAt(block: org.bukkit.block.Block): net.lumalyte.lg.domain.entities.Claim? =
        partitions?.getByPosition(block.location.toPosition3D())?.mapNotNull { claims.getById(it.claimId) }?.firstOrNull { it.worldId == block.world.uid }

    private fun objectiveOwner(block: org.bukkit.block.Block): FrontierOwner? {
        vaults.getGuildForVaultChest(block.location)?.let { return FrontierOwner(FrontierOwnerType.GUILD,it.id) }
        if (block.type !in setOf(Material.BARREL,Material.BELL)) return null
        val claim = claims.getByPosition(block.location.toPosition3D(),block.world.uid) ?: return null
        return if (claim.teamId == null && objectives.primaryAnchor(claim)) FrontierOwner(FrontierOwnerType.PERSONAL,claim.playerId) else null
    }

    private fun protect(blocks: MutableList<org.bukkit.block.Block>, tnt: Boolean = false, wither: Boolean = false,
                        attacker: org.bukkit.entity.Player? = null) {
        val now = Instant.now()
        val active = config.frontier.schedule.stateAt(now).active
        for (block in blocks.toList()) {
            val owner = objectiveOwner(block)
            if (owner != null) {
                blocks.remove(block) // Never allow vanilla destruction to bypass durable claim loss.
                if (tnt && active) {
                    try {
                        val name = vaults.getGuildForVaultChest(block.location)?.name ?: claimAt(block)?.name ?: "Solo territory"
                        val result = objectives.siege.hit(owner,FrontierObjectives.key(block.location),false,now,
                            minOf(config.frontier.tntVaultDamage,config.frontier.requiredHits))
                        if (result is FrontierSiegeResult.Progress) underAttack(owner, attacker?.name ?: "TNT")
                        if (result == FrontierSiegeResult.Defeated) fallen(owner, name, attacker)
                    } catch (failure: Exception) { plugin.logger.log(Level.SEVERE,"TNT objective cleanup pending",failure) }
                }
            } else if ((wither || !active) && claimAt(block) != null) blocks.remove(block)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) = protect(event.blockList())

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplosion(event: EntityExplodeEvent) = protect(event.blockList(),event.entity is org.bukkit.entity.TNTPrimed,
        event.entity is org.bukkit.entity.Wither || event.entity is org.bukkit.entity.WitherSkull,
        (event.entity as? org.bukkit.entity.TNTPrimed)?.source as? org.bukkit.entity.Player)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onWitherChange(event: org.bukkit.event.entity.EntityChangeBlockEvent) {
        if ((event.entity is org.bukkit.entity.Wither || event.entity is org.bukkit.entity.WitherSkull) &&
            (claimAt(event.block) != null || objectiveOwner(event.block) != null)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBurn(event: org.bukkit.event.block.BlockBurnEvent) {
        if (objectiveOwner(event.block) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDestroy(event: com.destroystokyo.paper.event.block.BlockDestroyEvent) {
        if (objectiveOwner(event.block) != null) event.isCancelled = true
    }
}
