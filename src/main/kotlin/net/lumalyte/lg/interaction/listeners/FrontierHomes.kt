package net.lumalyte.lg.interaction.listeners

import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.application.services.*
import net.lumalyte.lg.config.FrontierConfig
import net.lumalyte.lg.domain.entities.RankPermission
import net.lumalyte.lg.domain.values.Position3D
import org.bukkit.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.*
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.*
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** Frontier-only homes: one persisted slot per owner, bound to the founding claim's identity. */
class FrontierHomes(
    private val plugin: JavaPlugin,
    private val config: FrontierConfig,
    private val claims: ClaimRepository,
    private val partitions: PartitionRepository,
    private val guilds: GuildService,
    private val members: MemberService,
    private val clock: () -> Instant = { Instant.now() },
) : Listener {
    private val territory = FrontierTerritory(claims, partitions)
    private val file = plugin.dataFolder.resolve("frontier-homes.yml")
    private val data = YamlConfiguration().apply {
        // Legacy Bukkit Locations eagerly resolve worlds while loading YAML. Frontier worlds
        // can load after this plugin; retain coordinates as plain data and resolve on use.
        if (file.exists()) loadFromString(file.readText().lineSequence()
            .filterNot { it.trim() == "==: org.bukkit.Location" }.joinToString("\n"))
    }
    private data class Pending(val owner: FrontierOwner, val foundation: UUID, val destination: Location, val origin: Location, val ready: Instant)
    private val pending = mutableMapOf<UUID, Pending>()
    private val combat = mutableMapOf<UUID, Instant>()
    private val cooldown = mutableMapOf<UUID, Instant>()

    fun start() {
        mapOf("home" to false, "sethome" to false, "guildhome" to true, "setguildhome" to true).forEach { (name, guild) ->
            plugin.getCommand(name)?.setExecutor { sender, _, _, args ->
                if (sender is Player) {
                    if (args.isNotEmpty()) tell(sender, "There is one home per territory; no home name is needed.")
                    else command(sender, guild, name.startsWith("set"))
                } else sender.sendMessage("This command requires a player.")
                true
            }
        }
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
    }

    private fun tell(player: Player, text: String) = player.sendMessage(FrontierTheme.message("Home", text))
    private fun key(owner: FrontierOwner) = "${owner.type.name}.${owner.id}"
    private fun owner(player: Player, guild: Boolean): FrontierOwner? = if (guild)
        guilds.getPlayerGuilds(player.uniqueId).firstOrNull()?.let { FrontierOwner(FrontierOwnerType.GUILD, it.id) }
        else FrontierOwner(FrontierOwnerType.PERSONAL, player.uniqueId)
    private fun authorized(player: Player, owner: FrontierOwner) = if (owner.type == FrontierOwnerType.PERSONAL)
        owner.id == player.uniqueId else guilds.getPlayerGuilds(player.uniqueId).any { it.id == owner.id }

    internal fun ownsLocation(owner: FrontierOwner, foundation: UUID, location: Location): Boolean {
        if (location.world.name != config.worldName || location.world.environment != World.Environment.NORMAL) return false
        if (!location.world.worldBorder.isInside(location)) return false
        if (territory.foundation(owner)?.id != foundation) return false
        return partitions.getByPosition(Position3D(location.blockX, location.blockY, location.blockZ))
            .mapNotNull { claims.getById(it.claimId) }.any { it.worldId == location.world.uid && owner.owns(it) }
    }

    private fun safe(location: Location): Boolean {
        if (location.y < location.world.minHeight + 1 || location.y > location.world.maxHeight - 2) return false
        val feet = location.block; val head = feet.getRelative(0, 1, 0); val floor = feet.getRelative(0, -1, 0)
        val hazards = setOf(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.POWDER_SNOW, Material.POINTED_DRIPSTONE, Material.NETHER_PORTAL, Material.END_PORTAL)
        return feet.type.isAir && head.type.isAir && floor.type.isSolid &&
            listOf(feet, head, floor).none { it.type in hazards }
    }

    private fun blocked(player: Player): Boolean = config.schedule.stateAt(clock()).active || combatLocked(player)

    fun combatLocked(player: Player): Boolean = combat[player.uniqueId]?.let { clock() < it } == true ||
        (plugin.server.pluginManager.isPluginEnabled("CombatLogX") && net.lumalyte.lg.utils.CombatUtil.isInCombat(player))

    fun command(player: Player, guild: Boolean, set: Boolean) {
        if (blocked(player)) { tell(player, "Homes are unavailable during raids or combat."); return }
        val owner = owner(player, guild) ?: run { tell(player, "Join a guild with claimed land first."); return }
        val foundation = territory.foundation(owner) ?: run { tell(player, "Establish your Founding Vault before setting a home."); return }
        val path = key(owner)
        if (set) {
            if (guild && !members.hasPermission(player.uniqueId, owner.id, RankPermission.MANAGE_CLAIMS)) {
                tell(player, "Your guild rank cannot change its home."); return
            }
            val destination = player.location.clone()
            if (!ownsLocation(owner, foundation.id, destination) || !safe(destination)) {
                tell(player, "Stand on safe ground inside your ${if (guild) "guild" else "personal"} claim."); return
            }
            val previous = data.saveToString()
            try {
                data.set("$path.foundation", foundation.id.toString())
                data.set("$path.location", null)
                data.set("$path.location.world", destination.world.uid.toString())
                data.set("$path.location.x", destination.x)
                data.set("$path.location.y", destination.y)
                data.set("$path.location.z", destination.z)
                data.set("$path.location.yaw", destination.yaw)
                data.set("$path.location.pitch", destination.pitch)
                file.parentFile.mkdirs()
                val temp = file.toPath().resolveSibling("${file.name}.tmp")
                Files.writeString(temp, data.saveToString())
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                tell(player, "${if (guild) "Guild" else "Personal"} home saved. This replaces the previous home.")
            } catch (e: Exception) {
                data.loadFromString(previous)
                plugin.logger.severe("Could not persist Frontier home: ${e.message}")
                tell(player, "Home could not be saved. Your previous home is unchanged.")
            }
            return
        }
        if (cooldown[player.uniqueId]?.let { clock() < it } == true) { tell(player, "Wait 30 seconds between home teleports."); return }
        val destination = loadLocation("$path.location")
        if (destination == null || data.getString("$path.foundation") != foundation.id.toString() ||
            !ownsLocation(owner, foundation.id, destination)) {
            tell(player, "No valid home. Use ${if (guild) "/guild sethome" else "/sethome"} inside your land."); return
        }
        if (!safe(destination)) { tell(player, "Your home is obstructed or unsafe. Clear it before teleporting."); return }
        pending[player.uniqueId] = Pending(owner, foundation.id, destination.clone(), player.location.clone(), clock().plusSeconds(5))
        tell(player, "Teleporting in 5 seconds. Moving or taking damage cancels.")
    }

    internal fun loadLocation(path: String): Location? {
        val rawWorld = data.getString("$path.world") ?: return null
        val world = runCatching { UUID.fromString(rawWorld) }.getOrNull()?.let(Bukkit::getWorld)
            ?: Bukkit.getWorld(rawWorld) ?: return null
        if (listOf("x", "y", "z").any { !data.contains("$path.$it") }) return null
        return Location(world, data.getDouble("$path.x"), data.getDouble("$path.y"),
            data.getDouble("$path.z"), data.getDouble("$path.yaw").toFloat(), data.getDouble("$path.pitch").toFloat())
    }

    internal fun tick() {
        combat.entries.removeIf { clock() >= it.value }
        cooldown.entries.removeIf { clock() >= it.value }
        pending.toMap().forEach { (id, request) ->
            val player = plugin.server.getPlayer(id)
            if (player == null || !player.isOnline) { pending.remove(id); return@forEach }
            val current = player.location
            if (current.world != request.origin.world || current.distanceSquared(request.origin) > 0.01 || blocked(player) || !authorized(player, request.owner)) {
                pending.remove(id); tell(player, "Home teleport cancelled."); return@forEach
            }
            if (clock() < request.ready) return@forEach
            pending.remove(id)
            if (!ownsLocation(request.owner, request.foundation, request.destination) || !safe(request.destination)) {
                tell(player, "Home teleport cancelled: the destination is no longer safe owned land."); return@forEach
            }
            if (player.teleport(request.destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                cooldown[id] = clock().plusSeconds(30)
                tell(player, "Welcome home.")
                FrontierTheme.cue(player, FrontierTheme.Cue.CLICK)
            } else tell(player, "Home teleport was blocked.")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun damage(event: EntityDamageEvent) {
        (event.entity as? Player)?.let { player ->
            combat[player.uniqueId] = clock().plusSeconds(15)
            if (pending.remove(player.uniqueId) != null) tell(player, "Home teleport cancelled by damage.")
        }
        if (event is EntityDamageByEntityEvent) {
            val attacker = (event.damager as? Player) ?: ((event.damager as? Projectile)?.shooter as? Player)
            attacker?.let { combat[it.uniqueId] = clock().plusSeconds(15); pending.remove(it.uniqueId) }
        }
    }

    @EventHandler fun quit(event: PlayerQuitEvent) { pending.remove(event.player.uniqueId) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun teleport(event: PlayerTeleportEvent) { pending.remove(event.player.uniqueId) }
}
