package net.lumalyte.lg.infrastructure.services

import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.GuildRepository
import net.lumalyte.lg.application.services.*
import net.lumalyte.lg.config.MainConfig
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.entities.VaultStatus
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import java.time.Instant
import java.util.UUID

/** All calls run on the server thread. A failure leaves the durable defeat pending. */
class FrontierObjectives(
    private val config: MainConfig,
    store: FrontierSiegeStore,
    private val release: FrontierClaimRelease,
    private val claims: ClaimRepository,
    private val guilds: GuildRepository,
    private val partitions: net.lumalyte.lg.application.persistence.PartitionRepository? = null,
    private val isGuildOwner: (UUID) -> Boolean = { false },
    private val vaults: () -> GuildVaultService,
) {
    private var migrated = false

    fun migrateFoundations() {
        if (migrated || partitions == null) return
        val territory = FrontierTerritory(claims, partitions)
        val owners = claims.getAll().map(FrontierReplacementPolicy::ownerOf).toSet()
        for (owner in owners) {
            val owned = territory.owned(owner)
            val first = owned.firstOrNull() ?: continue
            if (owner.type == FrontierOwnerType.PERSONAL && isGuildOwner(owner.id)) {
                // Keep the physical barrel and its inventory as ordinary storage; only remove obsolete ownership.
                release.release(owner)
                Bukkit.getLogger().info("[LumaGuilds] Released legacy personal territory of guild owner ${owner.id}; containers preserved.")
                continue
            }
            val guild = if (owner.type == FrontierOwnerType.GUILD) guilds.getById(owner.id) else null
            if (owner.type == FrontierOwnerType.GUILD && (guild == null || guild.vaultStatus != VaultStatus.AVAILABLE)) {
                // Previously packed vaults must not retain protected, unraidable land.
                release.release(owner)
                continue
            }
            for (claim in owned) {
                val world = requireNotNull(Bukkit.getWorld(claim.worldId))
                val block = world.getBlockAt(claim.position.x,claim.position.y,claim.position.z)
                if (block.type == Material.BELL) block.setType(if (guild == null && claim.id == first.id) Material.BARREL else Material.AIR,false)
                if (claim.name != first.name) check(claims.update(claim.copy(name = first.name)))
                if (claim.id != first.id) {
                    for (partition in partitions.getByClaim(claim.id)) check(partitions.update(partition.copy(claimId = first.id)))
                }
            }
            val oldIds = owned.drop(1).map { it.id }.toSet()
            if (oldIds.isNotEmpty()) release.release(FrontierReleasePlan(UUID.randomUUID(),owner,oldIds))
            if (guild != null) {
                val vault = requireNotNull(guild.vaultChestLocation)
                check(claims.update(first.copy(worldId = vault.worldId, position = net.lumalyte.lg.domain.values.Position3D(vault.x,vault.y,vault.z))))
            }
        }
        migrated = true
        Bukkit.getLogger().info("[LumaGuilds] Founding Vault migration complete: shared territory names, legacy bells retired, packed-vault land released.")
    }
    val siege = FrontierSiege(config.frontier.schedule, store, release::snapshot, ::defeat,
        config.frontier.requiredHits, config.frontier.hitInterval, config.frontier.replacementCooldown)

    fun primaryAnchor(claim: Claim): Boolean = claims.getByPlayer(claim.playerId)
        .filter { it.teamId == null }
        .minWithOrNull(compareBy<Claim> { it.creationTime }.thenBy { it.id })?.id == claim.id

    fun soloAt(location: Location): Claim? {
        val position = net.lumalyte.lg.domain.values.Position3D(location.blockX,location.blockY,location.blockZ)
        return (listOfNotNull(claims.getByPosition(position,location.world.uid)) + claims.getAll())
            .firstOrNull { it.teamId == null && it.worldId == location.world.uid && it.position == position && primaryAnchor(it) }
    }

    fun recover() = siege.recover(Instant.now())

    fun surrender(owner: FrontierOwner) {
        val now = Instant.now()
        check(!config.frontier.schedule.stateAt(now).active && siege.canReplace(owner, now)) { "Cannot pack a vault during raids or cooldowns" }
        release.release(owner)
    }

    private fun defeat(record: FrontierSiegeRecord) {
        val plan = requireNotNull(record.release)
        if (record.owner.type == FrontierOwnerType.GUILD) {
            val guild = guilds.getById(record.owner.id)
            if (guild != null && guild.vaultStatus == VaultStatus.AVAILABLE) {
                val location = requireNotNull(vaults().getVaultLocation(guild)) { "Vault world unavailable" }
                check(key(location) == record.objective) { "Vault location changed during pending defeat" }
                when (val result = vaults().removeVaultChest(guild, dropItems = false)) {
                    is VaultResult.Failure -> error(result.message)
                    is VaultResult.Success -> Unit
                }
            }
        } else {
            // Only remove the recorded objective if its surviving claim still belongs to this owner.
            val anchor = plan.claimIds.mapNotNull(claims::getById).firstOrNull {
                record.owner.owns(it) && key(it.worldId, it.position.x, it.position.y, it.position.z) == record.objective
            }
            if (anchor != null) {
                val world = requireNotNull(Bukkit.getWorld(anchor.worldId)) { "Anchor world unavailable" }
                val block = world.getBlockAt(anchor.position.x, anchor.position.y, anchor.position.z)
                if (block.type == Material.BARREL) {
                    val inventory = (block.state as org.bukkit.block.Barrel).inventory
                    val items = inventory.contents.filterNotNull().map { it.clone() }
                    inventory.clear()
                    items.forEach { world.dropItemNaturally(block.location, it) }
                }
                if (block.type == Material.BELL || block.type == Material.BARREL) block.setType(Material.AIR, false)
            }
        }
        release.release(plan)
    }

    companion object {
        fun key(location: Location) = key(requireNotNull(location.world).uid, location.blockX, location.blockY, location.blockZ)
        private fun key(world: UUID, x: Int, y: Int, z: Int) = "$world:$x:$y:$z"
    }
}
