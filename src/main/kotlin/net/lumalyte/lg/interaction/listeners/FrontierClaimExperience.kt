package net.lumalyte.lg.interaction.listeners

import net.kyori.adventure.text.event.ClickEvent
import net.lumalyte.lg.application.actions.claim.CreateClaim
import net.lumalyte.lg.application.actions.claim.IsNewClaimLocationValid
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.application.persistence.PartitionRepository
import net.lumalyte.lg.application.results.claim.CreateClaimResult
import net.lumalyte.lg.application.results.claim.IsNewClaimLocationValidResult
import net.lumalyte.lg.application.services.*
import net.lumalyte.lg.config.MainConfig
import net.lumalyte.lg.domain.entities.RankPermission
import net.lumalyte.lg.domain.values.Position3D
import net.lumalyte.lg.infrastructure.services.FrontierObjectives
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.UUID

/** All previews and confirmations run on the server thread; confirmation revalidates land. */
class FrontierClaimExperience(
    private val plugin: JavaPlugin,
    private val config: MainConfig,
    private val create: CreateClaim,
    private val valid: IsNewClaimLocationValid,
    private val claims: ClaimRepository,
    private val partitions: PartitionRepository,
    private val guilds: GuildService,
    private val vaults: GuildVaultService,
    private val members: MemberService,
    private val objectives: FrontierObjectives,
    private val clock: () -> Instant = { Instant.now() },
) : Listener {
    private data class Preview(val location: Location, val expires: Instant, val name: String, val owner: FrontierOwner, val foundation: UUID?)
    private data class Pickup(val owner: FrontierOwner, val objective: String, val expires: Instant, val ready: Instant, val chunks: Int)
    private val previews = mutableMapOf<UUID, Preview>()
    private val outlines = mutableMapOf<UUID, Instant>()
    private val pickups = mutableMapOf<UUID, Pickup>()
    private data class Unclaim(val owner: FrontierOwner, val foundation: UUID, val world: UUID, val chunk: net.lumalyte.lg.domain.values.Position2D, val expires: Instant)
    private val unclaims = mutableMapOf<UUID, Unclaim>()
    private val autoPending = mutableMapOf<UUID, Pair<FrontierOwner, Instant>>()
    private val auto = mutableMapOf<UUID, Pair<FrontierOwner, UUID>>()

    fun start() {
        fun command(name: String, action: (Player, Array<out String>) -> Unit) {
            plugin.getCommand(name)?.setExecutor { sender, _, _, args ->
                if (sender is Player) action(sender, args)
                else sender.sendMessage("This command requires a player.")
                true
            }
        }
        command("claimconfirm") { player, _ -> confirm(player) }
        command("claimcancel") { player, _ -> previews.remove(player.uniqueId); tell(player, "Preview cancelled. No claim was created.") }
        command("claimoutline") { player, _ ->
            outlines[player.uniqueId] = clock().plusSeconds(30)
            tell(player, "Showing your nearby personal and guild claim borders for 30 seconds.")
        }
        command("vaultpickup") { player, args -> pickup(player, args.singleOrNull() == "confirm-unclaim-all") }
        command("unclaim") { player, args -> unclaim(player,args.singleOrNull() ?: "") }
        command("autoclaim") { player, args -> autoclaim(player,args.singleOrNull() ?: "") }
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val now = clock()
            previews.entries.removeIf { now >= it.value.expires }
            outlines.entries.removeIf { now >= it.value }
            pickups.entries.removeIf { now >= it.value.expires }
            unclaims.entries.removeIf { now >= it.value.expires }
            autoPending.entries.removeIf { now >= it.value.second }
            for (player in plugin.server.onlinePlayers) {
                previews[player.uniqueId]?.let { preview ->
                    val l = preview.location
                    if (l.world == player.world && l.distanceSquared(player.location) < 96 * 96) {
                        val x = Math.floorDiv(l.blockX, 16) * 16
                        val z = Math.floorDiv(l.blockZ, 16) * 16
                        draw(player, x, z, x + 16, z + 16, Color.fromRGB(0xC9926C))
                    }
                }
                if (outlines.containsKey(player.uniqueId)) {
                    val owned = claims.getByPlayer(player.uniqueId).filter { it.teamId == null }.toMutableSet()
                    guilds.getPlayerGuilds(player.uniqueId).forEach { owned.addAll(claims.getByTeam(it.id)) }
                    owned.filter { it.worldId == player.world.uid }.take(128).forEach { claim ->
                        partitions.getByClaim(claim.id).take(64).forEach { partition ->
                            val a = partition.area
                            draw(player, a.lowerPosition2D.x, a.lowerPosition2D.z,
                                a.upperPosition2D.x + 1, a.upperPosition2D.z + 1, Color.fromRGB(0xD6B56C))
                        }
                    }
                }
            }
        }, 1L, 10L)
    }

    private fun tell(player: Player, text: String) = player.sendMessage(FrontierTheme.message("Claim", text))

    private val territory = FrontierTerritory(claims, partitions)
    private val soloKey get() = NamespacedKey(plugin, "frontier_solo_vault")

    private fun owner(player: Player): FrontierOwner {
        val guild = guilds.getPlayerGuilds(player.uniqueId).firstOrNull()
        return guild?.let { FrontierOwner(FrontierOwnerType.GUILD, it.id) }
            ?: FrontierOwner(FrontierOwnerType.PERSONAL, player.uniqueId)
    }

    private fun manages(player: Player, owner: FrontierOwner) = if (owner.type == FrontierOwnerType.PERSONAL)
        owner.id == player.uniqueId else members.hasPermission(player.uniqueId, owner.id, RankPermission.MANAGE_CLAIMS)

    fun claimCommand(player: Player, arguments: String) {
        val text = arguments.trim()
        when (text.substringBefore(' ').lowercase()) {
            "migrate" -> { migrate(player,text.substringAfter(' ',"")); return }
            "help" -> {
                tell(player, "Use /claim to preview, /claimconfirm to claim, or /claim cancel to cancel.")
                tell(player, "Use /claim outline for borders; /claim rename <name> names your whole territory.")
                tell(player, "Use /autoclaim to preview automatic claiming; /unclaim for chunk removal.")
                tell(player, "Expand around your Founding Vault. Picking it up requires confirmation and releases ALL claims.")
                tell(player, "Claims are overworld-only. Use /raidinfo for raid times and /serverinfo for survival rules.")
                return
            }
            "confirm" -> { confirm(player); return }
            "cancel" -> { previews.remove(player.uniqueId); tell(player, "Preview cancelled."); return }
            "outline" -> { outlines[player.uniqueId] = clock().plusSeconds(30); tell(player, "Showing territory borders for 30 seconds."); return }
            "auto" -> { autoclaim(player,text.substringAfter(' ',"")); return }
            "unclaim" -> { unclaim(player,text.substringAfter(' ',"")); return }
            "rename" -> {
                val name = text.substringAfter(' ', "").trim()
                val l = player.location
                val claim = partitions.getByPosition(Position3D(l.blockX,l.blockY,l.blockZ)).mapNotNull { claims.getById(it.claimId) }.firstOrNull { it.worldId == l.world.uid }
                if (claim == null || !manages(player, FrontierReplacementPolicy.ownerOf(claim))) {
                    tell(player, "Stand in a territory you can manage."); return
                }
                if (territory.rename(FrontierReplacementPolicy.ownerOf(claim), name)) tell(player, "Entire territory renamed to $name.")
                else tell(player, "Use a territory name between 1 and 50 characters.")
                return
            }
        }
        preview(player, player.location.block.location, false, name = text.takeIf { it.isNotBlank() })
    }
    private data class MigrationPreview(val guild: java.util.UUID,val location: Location,val expires: java.time.Instant)
    private val migrations=mutableMapOf<java.util.UUID,MigrationPreview>()
    private fun migrate(player: Player, argument: String) {
        if(!config.frontier.guildOnlyClaims) { tell(player,"Guild-only migration is not enabled.");return }
        if(config.frontier.schedule.stateAt(clock()).active) { tell(player,"Migrate outside the raid window.");return }
        val guild=guilds.getPlayerGuilds(player.uniqueId).singleOrNull()
        if(guild==null || members.getGuildMembers(guild.id).size!=1 || !members.hasPermission(player.uniqueId,guild.id,RankPermission.PLACE_VAULT) || !members.hasPermission(player.uniqueId,guild.id,RankPermission.MANAGE_CLAIMS)) {
            tell(player,"Create a one-person guild before migrating. Its rank must manage claims and place vaults.");return
        }
        val legacy=claims.getByPlayer(player.uniqueId).filter { it.teamId==null }
        if(legacy.size!=1 || claims.getByTeam(guild.id).isNotEmpty()) { tell(player,"Migration needs one existing solo territory and a guild with no territory. Staff must review other cases; no land was changed.");return }
        val root=legacy.single()
        val existing=guild.vaultChestLocation
        val l=if(existing!=null && guild.vaultStatus==net.lumalyte.lg.domain.entities.VaultStatus.AVAILABLE) {
            val world=Bukkit.getWorld(existing.worldId) ?: run { tell(player,"Vault world unavailable.");return }
            Location(world,existing.x.toDouble(),existing.y.toDouble(),existing.z.toDouble())
        } else player.getTargetBlockExact(5)?.takeIf { it.type==Material.CHEST }?.location ?: player.location.block.location
        if(l.world!=player.world || l.distanceSquared(player.location)>64) { tell(player,"Stand near your migration vault.");return }
        if(l.world.uid!=root.worldId || (l.blockX shr 4)!=(root.position.x shr 4) || (l.blockZ shr 4)!=(root.position.z shr 4)) { tell(player,"Stand inside your original solo Founding chunk at the new guild vault location.");return }
        if(config.frontier.serverTerritory?.contains(l.blockX,l.blockZ)==true) { tell(player,"This legacy territory overlaps server land. Staff must review it before migration; nothing was changed.");return }
        if(!objectives.siege.canReplace(FrontierOwner(FrontierOwnerType.PERSONAL,player.uniqueId),clock()) || !objectives.siege.canReplace(FrontierOwner(FrontierOwnerType.GUILD,guild.id),clock())) { tell(player,"Wait until objective cleanup and replacement cooldowns finish.");return }
        if(argument!="confirm") {
            migrations[player.uniqueId]=MigrationPreview(guild.id,l.clone(),clock().plusSeconds(30))
            tell(player,"Migrate all territory to ${guild.name} and place its Founding Vault here. The old barrel and its items remain untouched. All land becomes raidable once conversion succeeds. Confirm within 30 seconds with /claim migrate confirm.");return
        }
        val preview=migrations.remove(player.uniqueId)
        if(preview==null || preview.guild!=guild.id || clock()>=preview.expires || preview.location!=l) { tell(player,"Location or guild changed. Preview with /claim migrate again.");return }
        fun physicalVaultReady(): Boolean {
            val chest=l.block.state as? org.bukkit.block.Chest ?: return false
            return chest.persistentDataContainer.get(NamespacedKey(plugin,"vault_guild_id"),PersistentDataType.STRING)==guild.id.toString() &&
                chest.persistentDataContainer.has(NamespacedKey(plugin,"vault_marker"),PersistentDataType.BYTE) && vaults.getGuildForVaultChest(l)?.id==guild.id
        }
        try {
            val result=net.lumalyte.lg.application.services.FrontierGuildMigration(claims).migrate(player.uniqueId,guild.id,l.world.uid,Position3D(l.blockX,l.blockY,l.blockZ)) {
                val current=guilds.getGuild(guild.id) ?: return@migrate false
                if(current.vaultStatus==net.lumalyte.lg.domain.entities.VaultStatus.AVAILABLE) {
                    val v=current.vaultChestLocation
                    v!=null && v.worldId==l.world.uid && v.x==l.blockX && v.y==l.blockY && v.z==l.blockZ && physicalVaultReady()
                } else {
                    val emptyChest=(l.block.state as? org.bukkit.block.Chest)?.inventory?.contents?.all { it==null || it.type.isAir } == true
                    if((!l.block.type.isAir && !emptyChest) || !l.block.getRelative(0,-1,0).type.isSolid) return@migrate false
                    l.block.setType(Material.CHEST,false)
                    vaults.placeVaultChest(current,l,player) is VaultResult.Success && physicalVaultReady()
                }
            }
            tell(player,if(result) "Migration complete. Your land now belongs to ${guild.name}. Old barrel items remain in place; set your shared home with /guild sethome." else "Migration not completed. Existing land and items remain. Retry at the same vault location or contact staff.")
        } catch(failure: Exception) { plugin.logger.log(java.util.logging.Level.SEVERE,"Guild migration pending; legacy land retained",failure);tell(player,"Migration needs a retry at the same location. Do not remove the new chest; contact staff if needed.") }
    }

    private fun draw(player: Player, x0: Int, z0: Int, x1: Int, z1: Int, color: Color) {
        // Bounded per-viewer rendering, no chunk loads or world edits.
        val y = player.location.y + 0.25
        val dust = Particle.DustOptions(color, 1.2f)
        fun point(x: Double, z: Double) {
            if (kotlin.math.abs(x - player.location.x) <= 96 && kotlin.math.abs(z - player.location.z) <= 96)
                player.spawnParticle(Particle.DUST, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
        val stepX = maxOf(1, (x1 - x0) / 64)
        val stepZ = maxOf(1, (z1 - z0) / 64)
        for (x in x0..x1 step stepX) { point(x.toDouble(), z0.toDouble()); point(x.toDouble(), z1.toDouble()) }
        for (z in z0..z1 step stepZ) { point(x0.toDouble(), z.toDouble()); point(x1.toDouble(), z.toDouble()) }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val meta = event.itemInHand.itemMeta ?: return
        if (!meta.persistentDataContainer.has(net.lumalyte.lg.common.PluginKeys.GUILD_VAULT_ID, PersistentDataType.STRING) &&
            !meta.persistentDataContainer.has(soloKey, PersistentDataType.BYTE)) return
        event.isCancelled = true
        preview(event.player, event.block.location, false)
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK || !event.player.isSneaking) return
        val block = event.clickedBlock ?: return
        val solo = objectives.soloAt(block.location)
        if (vaults.getGuildForVaultChest(block.location) != null || (block.type == Material.BARREL && solo != null)) {
            event.isCancelled = true
            pickup(event.player, false)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun preview(player: Player, location: Location, existingBell: Boolean, bellData: org.bukkit.block.data.BlockData? = null, name: String? = null, consumeBell: Boolean = false) {
        previews.remove(player.uniqueId)
        val owner = owner(player)
        if (config.frontier.guildOnlyClaims && owner.type == FrontierOwnerType.PERSONAL) { tell(player,"Create or join a guild first. One-person guilds are welcome."); return }
        if (config.frontier.guildOnlyClaims && claims.getByPlayer(player.uniqueId).any { it.teamId == null }) { tell(player,"Migrate your existing solo land with /claim migrate before claiming more land."); return }
        if (!player.hasPermission("bellclaims.action.bell") || !manages(player, owner)) { tell(player,"You cannot manage this territory."); return }
        if (location.world.environment != World.Environment.NORMAL || location.world.name != config.frontier.worldName) { tell(player,"Territories belong in the Frontier overworld."); return }
        if (config.frontier.schedule.stateAt(clock()).active || !objectives.siege.canReplace(owner, clock())) { tell(player,"Claiming is locked during raids and replacement cooldowns."); return }
        val foundation = territory.foundation(owner)
        if (foundation == null && owner.type == FrontierOwnerType.GUILD && !members.hasPermission(player.uniqueId, owner.id, RankPermission.PLACE_VAULT)) { tell(player,"Your rank cannot place the Founding Vault."); return }
        val territoryName = foundation?.let(territory::name) ?: name ?: (if (owner.type == FrontierOwnerType.GUILD) guilds.getGuild(owner.id)?.name else player.name) ?: "Territory"
        if (territoryName.isBlank() || territoryName.length > 50) { tell(player,"Use a territory name between 1 and 50 characters."); return }
        val position = Position3D(location.blockX,location.blockY,location.blockZ)
        if (!territory.canExtend(owner,location.world.uid,position.getChunk())) { tell(player,"Claim an edge-adjacent chunk connected back to your Founding Vault."); return }
        if (valid.execute(position,location.world.uid,player.uniqueId, if (owner.type == FrontierOwnerType.GUILD) setOf(owner.id) else emptySet()) != IsNewClaimLocationValidResult.Valid) {
            val serverLand = config.frontier.serverTerritory
            if (serverLand != null && location.world.name == config.frontier.worldName && serverLand.contains(location.blockX,location.blockZ))
                tell(player,"${serverLand.name} is public server territory. You may build and fight here, but cannot claim it.")
            else tell(player,"This chunk overlaps land, its buffer, reserved spawn, or the world border.")
            return
        }
        previews[player.uniqueId] = Preview(location.clone(),clock().plusSeconds(30),territoryName,owner,foundation?.id)
        tell(player,"Preview: $territoryName — chunk ${position.getChunk().x}, ${position.getChunk().z}. ${if(foundation == null) "Your Founding Vault will be placed here." else "Expands your territory; no extra vault or bell is placed."}")
        player.sendMessage(FrontierTheme.message("Confirm","[Claim this chunk]").clickEvent(ClickEvent.runCommand("/claim confirm")))
        player.sendMessage(FrontierTheme.message("Cancel","[Cancel] — expires in 30 seconds.").clickEvent(ClickEvent.runCommand("/claim cancel")))
    }

    fun confirm(player: Player) {
        val preview = previews.remove(player.uniqueId)
        if (preview == null || clock() >= preview.expires) { tell(player,"Use /claim [name] to preview a chunk first."); return }
        val l = preview.location
        if (player.world != l.world || l.distanceSquared(player.location) > 64 || owner(player) != preview.owner || !manages(player, preview.owner)) { tell(player,"Your location or territory permissions changed. Preview again."); return }
        val foundation = territory.foundation(preview.owner)
        if (foundation?.id != preview.foundation) { tell(player,"Your founding claim changed. Preview again."); return }
        if (foundation == null && (!l.block.type.isAir || !l.block.getRelative(0,-1,0).type.isSolid)) { tell(player,"The Founding Vault needs empty space on solid ground."); return }
        if (foundation != null) {
            val guild = if (preview.owner.type == FrontierOwnerType.GUILD) guilds.getGuild(preview.owner.id) else null
            val active = if (guild != null) guild.vaultStatus == net.lumalyte.lg.domain.entities.VaultStatus.AVAILABLE
                else Bukkit.getWorld(foundation.worldId)?.getBlockAt(foundation.position.x,foundation.position.y,foundation.position.z)?.type == Material.BARREL
            if (!active) { tell(player,"Your Founding Vault must be placed before expanding."); return }
        }
        val guildId = preview.owner.id.takeIf { preview.owner.type == FrontierOwnerType.GUILD }
        when (val result = create.executeFrontier(player.uniqueId,preview.name,Position3D(l.blockX,l.blockY,l.blockZ),l.world.uid,guildId)) {
            is CreateClaimResult.Success -> {
                if (foundation == null) {
                    try {
                        if (guildId != null) {
                            val guild = requireNotNull(guilds.getGuild(guildId))
                            check(members.hasPermission(player.uniqueId,guildId,RankPermission.PLACE_VAULT))
                            l.block.setType(Material.CHEST,false)
                            val placed = vaults.placeVaultChest(guild,l,player)
                            check(placed is VaultResult.Success) { (placed as? VaultResult.Failure)?.message ?: "Vault placement failed" }
                        } else {
                            l.block.setType(Material.BARREL,false)
                            val held = player.inventory.itemInMainHand
                            val packed = held.itemMeta as? org.bukkit.inventory.meta.BlockStateMeta
                            if (packed?.persistentDataContainer?.has(soloKey,PersistentDataType.BYTE) == true) {
                                val contents = (packed.blockState as? org.bukkit.block.Barrel)?.inventory?.contents
                                if (contents != null) (l.block.state as org.bukkit.block.Barrel).inventory.contents = contents
                            }
                        }
                        val held = player.inventory.itemInMainHand
                        if ((guildId == null && held.itemMeta?.persistentDataContainer?.has(soloKey,PersistentDataType.BYTE) == true) ||
                            held.itemMeta?.persistentDataContainer?.get(net.lumalyte.lg.common.PluginKeys.GUILD_VAULT_ID,PersistentDataType.STRING) == guildId?.toString() && guildId != null) held.amount -= 1
                    } catch (failure: Exception) {
                        // Roll back land before removing its objective; never leave protected land without a vault.
                        objectives.surrender(preview.owner)
                        l.block.setType(Material.AIR,false)
                        plugin.logger.log(java.util.logging.Level.SEVERE,"Founding vault placement failed",failure)
                        tell(player,"Founding Vault could not be placed. No territory was retained."); return
                    }
                }
                outlines[player.uniqueId] = clock().plusSeconds(30)
                tell(player,"Claimed chunk for ${result.claim.name}. All chunks share this name. Use /claim rename <name> to rename the territory.")
                FrontierTheme.cue(player,FrontierTheme.Cue.SUCCESS)
            }
            CreateClaimResult.RaidLocked -> tell(player,"Claiming is locked during a raid or replacement cooldown.")
            CreateClaimResult.LimitExceeded -> tell(player,"Your territory has reached its chunk limit.")
            else -> tell(player,"Claim failed. Check the buffer, founding connection and world border, then preview again.")
        }
    }

    fun pickup(player: Player, confirm: Boolean) {
        val block = player.getTargetBlockExact(6) ?: return tell(player,"Look at your Founding Vault within six blocks.")
        val guild = vaults.getGuildForVaultChest(block.location)
        val solo = objectives.soloAt(block.location)?.takeIf { block.type == Material.BARREL }
        val owner = guild?.let { FrontierOwner(FrontierOwnerType.GUILD,it.id) } ?: solo?.let { FrontierOwner(FrontierOwnerType.PERSONAL,it.playerId) }
        if (owner == null || !manages(player,owner) || (guild != null && !members.hasPermission(player.uniqueId,guild.id,RankPermission.BREAK_VAULT))) { tell(player,"Look at a Founding Vault you have permission to pack."); return }
        val now = clock()
        if (config.frontier.schedule.stateAt(now).active || !objectives.siege.canReplace(owner,now)) { tell(player,"Vault pickup is locked during raids and replacement cooldowns."); return }
        val key = FrontierObjectives.key(block.location)
        val count = territory.chunks(owner).size
        if (!confirm) {
            pickups[player.uniqueId] = Pickup(owner,key,now.plusSeconds(30),now.plusSeconds(3),count)
            player.showTitle(net.kyori.adventure.title.Title.title(FrontierTheme.heading("ALL CLAIMS WILL BE LOST"),net.kyori.adventure.text.Component.text("Packing this vault unclaims your entire territory")))
            player.sendMessage(FrontierTheme.message("WARNING","Packing this Founding Vault permanently UNCLAIMS ALL $count CHUNKS. Your builds become wilderness and can be raided immediately. Stored vault items stay with you. No raid cooldown starts.",true))
            player.sendMessage(FrontierTheme.message("Confirm","After reading this warning, type /vaultpickup confirm-unclaim-all within 30 seconds. This cannot be undone.",true))
            FrontierTheme.cue(player,FrontierTheme.Cue.FALLEN)
            return
        }
        val pending = pickups[player.uniqueId]
        if (pending == null || pending.owner != owner || pending.objective != key || now >= pending.expires || now < pending.ready || pending.chunks != count) { tell(player,"Run /vaultpickup, read the warning, and wait three seconds before typing /vaultpickup confirm-unclaim-all."); return }
        if (player.inventory.firstEmpty() == -1) { tell(player,"Make an inventory slot available first."); return }
        pickups.remove(player.uniqueId)
        val item = ItemStack(if (guild != null) Material.CHEST else Material.BARREL)
        if (guild != null) item.editMeta {
            it.displayName(FrontierTheme.heading("${guild.name} Founding Vault"))
            it.persistentDataContainer.set(net.lumalyte.lg.common.PluginKeys.GUILD_VAULT_ID,PersistentDataType.STRING,guild.id.toString())
        } else {
            val meta = item.itemMeta as org.bukkit.inventory.meta.BlockStateMeta
            val packed = meta.blockState as org.bukkit.block.Barrel
            packed.inventory.contents = (block.state as org.bukkit.block.Barrel).inventory.contents
            meta.blockState = packed
            meta.displayName(FrontierTheme.heading("Solo Founding Vault"))
            meta.persistentDataContainer.set(soloKey,PersistentDataType.BYTE,1.toByte())
            item.itemMeta = meta
        }
        try {
            // Durable release FIRST: a failed pickup may leave a vault standing, never protected land without one.
            objectives.surrender(owner)
            if (guild != null) {
                val removed = vaults.removeVaultChest(guild,false)
                check(removed is VaultResult.Success) { "Vault removal failed" }
            } else {
                (block.state as org.bukkit.block.Barrel).inventory.clear()
                block.setType(Material.AIR,false)
            }
            player.inventory.addItem(item)
            tell(player,"Vault packed. ALL $count CHUNKS ARE NOW UNCLAIMED. Establish a new Founding Vault with /claim outside raids.")
        } catch (failure: Exception) {
            plugin.logger.log(java.util.logging.Level.SEVERE,"Territory surrender failed; recovery required",failure)
            tell(player,"Packing failed. Contact staff to check territory cleanup before trying again.")
        }
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        previews.remove(event.player.uniqueId); outlines.remove(event.player.uniqueId); pickups.remove(event.player.uniqueId)
        auto.remove(event.player.uniqueId); autoPending.remove(event.player.uniqueId); unclaims.remove(event.player.uniqueId)
    }

    fun unclaim(player: Player, argument: String) {
        if (argument.equals("all",true)) { auto.remove(player.uniqueId); pickup(player,false); return }
        if (argument.isNotEmpty() && !argument.equals("confirm",true)) { tell(player,"Use /unclaim, /unclaim confirm, or /unclaim all beside your vault."); return }
        val l=player.location
        val claim=partitions.getByPosition(Position3D(l.blockX,l.blockY,l.blockZ)).mapNotNull { claims.getById(it.claimId) }.firstOrNull { it.worldId==l.world.uid }
            ?: return tell(player,"This chunk is not claimed.")
        val target=FrontierReplacementPolicy.ownerOf(claim)
        if (!manages(player,target)) return tell(player,"You cannot unclaim this territory.")
        if (config.frontier.schedule.stateAt(clock()).active || !objectives.siege.canReplace(target,clock())) return tell(player,"Unclaiming is locked during raids and replacement cooldowns.")
        val root=territory.foundation(target) ?: return
        val chunk=Position3D(l.blockX,l.blockY,l.blockZ).getChunk()
        if (chunk==root.position.getChunk()) return tell(player,"This is the Founding Claim. Look at its vault and use /vaultpickup to confirm surrendering ALL land.")
        if (!argument.equals("confirm",true)) {
            unclaims[player.uniqueId]=Unclaim(target,root.id,l.world.uid,chunk,clock().plusSeconds(30))
            tell(player,"WARNING: this chunk will become wilderness. Stay here and use /unclaim confirm within 30 seconds."); return
        }
        val pending=unclaims.remove(player.uniqueId)
        if (pending==null || pending.owner!=target || pending.foundation!=root.id || pending.world!=l.world.uid || pending.chunk!=chunk || clock()>=pending.expires)
            return tell(player,"Unclaim confirmation expired or changed. Run /unclaim again.")
        auto.remove(player.uniqueId)
        try {
            if (territory.unclaim(target,l.world.uid,chunk)) { tell(player,"Chunk unclaimed. It is now wilderness."); outlines[player.uniqueId]=clock().plusSeconds(30) }
            else tell(player,"Cannot unclaim a chunk that disconnects land from the Founding Vault.")
        } catch (failure: Exception) {
            plugin.logger.log(java.util.logging.Level.SEVERE,"Unclaim failed",failure)
            tell(player,"Could not finish unclaiming. Contact staff before retrying.")
        }
    }

    fun autoclaim(player: Player, argument: String) {
        if (argument.equals("off",true) || (argument.isBlank() && auto.containsKey(player.uniqueId))) {
            auto.remove(player.uniqueId); autoPending.remove(player.uniqueId); tell(player,"Autoclaim OFF."); return
        }
        val target=owner(player)
        if (!manages(player,target) || !player.hasPermission("bellclaims.action.bell")) return tell(player,"You cannot manage this territory.")
        if (territory.foundation(target)==null) return tell(player,"Use /claim and confirm your Founding Vault before enabling autoclaim.")
        if (config.frontier.schedule.stateAt(clock()).active || !objectives.siege.canReplace(target,clock())) return tell(player,"Autoclaim is locked during raids and cooldowns.")
        if (argument.equals("confirm",true)) {
            val pending=autoPending.remove(player.uniqueId)
            if (pending==null || pending.first!=target || clock()>=pending.second) return tell(player,"Use /autoclaim to review and enable walking claims first.")
            auto[player.uniqueId]=target to requireNotNull(territory.foundation(target)).id
            tell(player,"Autoclaim ON. Walking into eligible neighboring chunks claims them. Use /autoclaim off to stop."); return
        }
        if (argument.isNotBlank() && !argument.equals("on",true)) return tell(player,"Use /autoclaim, /autoclaim confirm, or /autoclaim off.")
        autoPending[player.uniqueId]=target to clock().plusSeconds(30)
        tell(player,"Walking autoclaim will claim each eligible chunk you enter without another confirmation. Limits, buffers and founding connections still apply. Use /autoclaim confirm within 30 seconds.")
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun onAutoMove(event: org.bukkit.event.player.PlayerMoveEvent) {
        if (event is org.bukkit.event.player.PlayerTeleportEvent || !auto.containsKey(event.player.uniqueId)) return
        val to=event.to
        if (event.from.world==to.world && (event.from.blockX shr 4)==(to.blockX shr 4) && (event.from.blockZ shr 4)==(to.blockZ shr 4)) return
        val player=event.player
        val expected=Position3D(to.blockX,to.blockY,to.blockZ).getChunk()
        plugin.server.scheduler.runTask(plugin,Runnable {
            val (target, foundationId)=auto[player.uniqueId] ?: return@Runnable
            if (!player.isOnline || player.world!=to.world || Position3D(player.location.blockX,player.location.blockY,player.location.blockZ).getChunk()!=expected) return@Runnable
            if (territory.foundation(target)?.id != foundationId || owner(player)!=target || !manages(player,target) || config.frontier.schedule.stateAt(clock()).active) {
                auto.remove(player.uniqueId); tell(player,"Autoclaim stopped: ownership, permission or raid state changed."); return@Runnable
            }
            if (expected in territory.chunks(target)) return@Runnable
            preview(player,player.location.block.location,false)
            if (previews.containsKey(player.uniqueId)) confirm(player)
            if (expected !in territory.chunks(target)) { auto.remove(player.uniqueId); tell(player,"Autoclaim stopped because this chunk could not be claimed.") }
        })
    }
}
