package net.lumalyte.lg.interaction.listeners

import net.lumalyte.lg.config.FrontierConfig
import net.kyori.adventure.text.Component
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.entity.EnderCrystal
import org.bukkit.event.*
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.world.WorldLoadEvent
import java.util.concurrent.ThreadLocalRandom

/** Registered only on the isolated Frontier profile. Items are denied, not deleted. */
class FrontierServerRules(private val config: FrontierConfig, private val openMenu: (Player) -> Unit = {}) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMobSpawn(event: org.bukkit.event.entity.CreatureSpawnEvent) {
        if (disabledMob(event.entity)) event.isCancelled = true
    }

    fun removeDisabledMobs(entities: Collection<org.bukkit.entity.Entity>) {
        entities.filter(::disabledMob).forEach { it.remove() }
    }

    private fun disabledMob(entity: org.bukkit.entity.Entity) =
        entity is org.bukkit.entity.Wither || entity is org.bukkit.entity.Shulker

    @EventHandler
    fun onMobsLoaded(event: org.bukkit.event.world.EntitiesLoadEvent) {
        removeDisabledMobs(event.entities)
    }

    var homeAction: (Player, Boolean) -> Unit = { _, _ -> }
    var ranksAction: (Player) -> Unit = {}
    var rosterLocked: () -> Boolean = { false }
    var warAction: (Player) -> Unit = {}
    companion object {
        private val endOpens = java.time.Instant.parse("2026-11-27T21:00:00Z")
        internal fun endLocked(world: World?, now: java.time.Instant = java.time.Instant.now()) =
            world?.environment == World.Environment.THE_END && now.isBefore(endOpens)
        private val shulkerBoxes = Material.entries.filter { !it.isLegacy && it.name.endsWith("SHULKER_BOX") }.toSet()
        val bannedItems = setOf(Material.END_CRYSTAL, Material.ELYTRA, Material.RESPAWN_ANCHOR, Material.MACE, Material.ENDER_CHEST) + shulkerBoxes
        val guildCommands = setOf("war", "ranks", "warbanner", "home", "sethome", "menu", "help", "create", "rename", "invite", "join", "accept", "decline", "invites",
            "kick", "leave", "disband", "transfer", "tag", "description", "desc", "info", "getvault", "vault", "allychat", "chat", "notify", "notifications")
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: org.bukkit.event.player.PlayerCommandPreprocessEvent) {
        val words = event.message.removePrefix("/").lowercase(java.util.Locale.ROOT).split(Regex("\\s+"))
        val root = words.first().substringAfter(':')
        if (root in setOf("g", "guild", "guilds") && words.getOrNull(1) in setOf("create", "join", "accept", "kick") && rosterLocked()) {
            event.isCancelled = true
            event.player.sendMessage(FrontierTheme.message("Guild", "Guild rosters are locked during raids. Joining and kicking resume after the raid window; you may still leave."))
            return
        }
        if (root in setOf("g", "guild", "guilds") && words.getOrNull(1) == "war") {
            event.isCancelled = true
            warAction(event.player)
            return
        }
        if (root in setOf("g", "guild", "guilds") && words.getOrNull(1) == "ranks") {
            event.isCancelled = true
            if (event.player.hasPermission("lumaguilds.guild.ranks")) ranksAction(event.player)
            return
        }
        if (root in setOf("g", "guild", "guilds") && words.getOrNull(1) in setOf("home", "sethome")) {
            event.isCancelled = true
            if (words.size != 2) event.player.sendMessage(FrontierTheme.message("Home", "Use /guild home or /guild sethome. One shared home is available."))
            else homeAction(event.player, words[1] == "sethome")
            return
        }
        if (root in setOf("g", "guild", "guilds") && (words.getOrNull(1).isNullOrBlank() || words.getOrNull(1) in setOf("menu", "help"))) {
            event.isCancelled = true
            if (event.player.hasPermission("lumaguilds.guild.menu")) openMenu(event.player)
            else event.player.sendMessage(FrontierTheme.message("Guild", "You do not have permission to open this menu.", true))
            return
        }
        if (root in setOf("g", "guild", "guilds") && words.getOrNull(1) !in guildCommands) {
            event.isCancelled = true
            event.player.sendMessage(FrontierTheme.message("Guild", "That feature is unavailable in Frontier. Use /guild for your menu and command help."))
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTab(event: org.bukkit.event.server.TabCompleteEvent) {
        val buffer = event.buffer.removePrefix("/")
        val root = buffer.substringBefore(' ').substringAfter(':').lowercase()
        if (root !in setOf("g", "guild", "guilds") || !buffer.contains(' ')) return
        val argument = buffer.substringAfter(' ')
        if (!argument.contains(' ')) event.completions = guildCommands.filter { it.startsWith(argument.lowercase()) }.sorted()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onAsyncTab(event: com.destroystokyo.paper.event.server.AsyncTabCompleteEvent) {
        if (!event.isCommand) return
        val buffer = event.buffer.removePrefix("/")
        val root = buffer.substringBefore(' ').substringAfter(':').lowercase()
        if (root !in setOf("g", "guild", "guilds") || !buffer.contains(' ')) return
        val argument = buffer.substringAfter(' ')
        if (!argument.contains(' ')) {
            event.completions = guildCommands.filter { it.startsWith(argument.lowercase()) }.sorted()
            event.isHandled = true
        }
    }

    fun configure(world: World) {
        if (world.environment == World.Environment.NORMAL && world.name != config.worldName) return
        world.worldBorder.setCenter(0.0, 0.0)
        world.worldBorder.size = (if (world.environment == World.Environment.THE_END) config.endBorderSize else config.borderSize).toDouble()
        world.pvp = true
    }

    fun start(plugin: org.bukkit.plugin.java.JavaPlugin) {
        // Also catches mounted players and gateway blocks generated after the dragon fight.
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            plugin.server.onlinePlayers.filter { onRoof(it.location) }.forEach { player ->
                spawn()?.let { player.leaveVehicle(); player.teleport(it) }
            }
            plugin.server.worlds.filter { it.environment == World.Environment.THE_END }.forEach { world ->
                world.loadedChunks.forEach(::removeGateways)
            }
        }, 1L, 100L)
    }

    private fun onRoof(location: Location) = location.world.environment == World.Environment.NETHER && location.y >= 128

    /** Override vanilla's 8:1 portal search coordinates in BOTH directions. */
    fun portalDestination(from: Location, destination: Location): Location {
        val environments = setOf(from.world.environment, destination.world.environment)
        if (environments != setOf(World.Environment.NORMAL, World.Environment.NETHER)) return destination
        return destination.clone().apply {
            val limit = config.borderSize / 2.0 - 16
            x = from.x.coerceIn(-limit, limit)
            z = from.z.coerceIn(-limit, limit)
            if (world.environment == World.Environment.NETHER) y = y.coerceIn(8.0, 120.0)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPortal(event: org.bukkit.event.player.PlayerPortalEvent) {
        if (endLocked(event.to?.world)) {
            event.isCancelled = true
            event.player.sendMessage(FrontierTheme.message("Frontier", "The End opens November 27 at 4 PM Eastern."))
            return
        }
        if (event.cause != org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return
        event.to = portalDestination(event.from, event.to ?: return)
        event.searchRadius = 16
        event.creationRadius = 16
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityPortal(event: org.bukkit.event.entity.EntityPortalEvent) {
        if (endLocked(event.to?.world)) {
            event.isCancelled = true
            return
        }
        event.to = portalDestination(event.from, event.to ?: return)
        event.searchRadius = 16
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: org.bukkit.event.player.PlayerTeleportEvent) {
        if (endLocked(event.to?.world) || event.cause == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.END_GATEWAY || event.to?.let(::onRoof) == true)
            event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityGateway(event: com.destroystokyo.paper.event.entity.EntityTeleportEndGatewayEvent) { event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: org.bukkit.event.player.PlayerMoveEvent) {
        if (event.to?.let(::onRoof) == true && !onRoof(event.from)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onRoofBreak(event: org.bukkit.event.block.BlockBreakEvent) {
        if (onRoof(event.block.location)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPortalCreate(event: org.bukkit.event.world.PortalCreateEvent) {
        if (event.blocks.any { onRoof(it.location) || it.type == Material.END_GATEWAY }) event.isCancelled = true
    }

    private fun removeGateways(chunk: Chunk) {
        if (chunk.world.environment != World.Environment.THE_END) return
        chunk.tileEntities.filterIsInstance<org.bukkit.block.EndGateway>().forEach { it.block.setType(Material.AIR, false) }
    }

    @EventHandler fun onChunk(event: org.bukkit.event.world.ChunkLoadEvent) = removeGateways(event.chunk)

    @EventHandler fun onWorld(event: WorldLoadEvent) = configure(event.world)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val block = event.block
        if (onRoof(block.location) || block.type == Material.END_GATEWAY) { event.isCancelled = true; return }
        if (event.itemInHand.type in bannedItems || block.type == Material.RESPAWN_ANCHOR) {
            event.isCancelled = true
            event.player.sendMessage(FrontierTheme.message("Frontier", "That item is disabled on Frontier.", true))
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onUse(event: PlayerInteractEvent) {
        if (event.item?.type in bannedItems || event.clickedBlock?.type == Material.RESPAWN_ANCHOR ||
            event.clickedBlock?.type == Material.ENDER_CHEST || event.clickedBlock?.type in shulkerBoxes) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onDispense(event: org.bukkit.event.block.BlockDispenseEvent) {
        if (event.item.type in shulkerBoxes) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onOpen(event: org.bukkit.event.inventory.InventoryOpenEvent) {
        when (event.inventory.type) {
            org.bukkit.event.inventory.InventoryType.ENDER_CHEST,
            org.bukkit.event.inventory.InventoryType.SHULKER_BOX -> event.isCancelled = true
            else -> Unit
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onHopperTransfer(event: org.bukkit.event.inventory.InventoryMoveItemEvent) {
        val box = org.bukkit.event.inventory.InventoryType.SHULKER_BOX
        if (event.source.type == box || event.destination.type == box) event.isCancelled = true
    }

    @EventHandler fun onChangedWorld(event: org.bukkit.event.player.PlayerChangedWorldEvent) {
        if (endLocked(event.player.world)) spawn()?.let { event.player.teleport(it) }
    }

    @EventHandler fun onRecipe(event: PrepareItemCraftEvent) {
        if (event.inventory.result?.type in bannedItems) event.inventory.result = null
    }

    @EventHandler(ignoreCancelled = true)
    fun onAutoCraft(event: org.bukkit.event.block.CrafterCraftEvent) {
        if (event.result.type in bannedItems) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        if (event.recipe.result.type in bannedItems) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onGlide(event: EntityToggleGlideEvent) {
        if (event.isGliding) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (event.entity is Player && (isTnt(event.damager) || isTnt(event.damageSource.directEntity))) {
            event.isCancelled = true
            return
        }
        val attacker = event.damager as? Player
        if (event.damager is EnderCrystal || attacker?.inventory?.itemInMainHand?.type == Material.MACE)
            event.isCancelled = true
    }

    private fun isTnt(entity: org.bukkit.entity.Entity?) =
        entity is org.bukkit.entity.TNTPrimed || entity is org.bukkit.entity.minecart.ExplosiveMinecart

    private fun spawn(): Location? {
        val world = Bukkit.getWorld(config.worldName) ?: return null
        val random = ThreadLocalRandom.current()
        repeat(128) {
            val angle = random.nextDouble(Math.PI * 2)
            val radius = random.nextDouble(config.spawnMinRadius.toDouble(), config.spawnMaxRadius.toDouble())
            val x = (kotlin.math.cos(angle) * radius).toInt()
            val z = (kotlin.math.sin(angle) * radius).toInt()
            val floor = world.getHighestBlockAt(x, z)
            if (floor.type.isSolid && floor.type !in setOf(Material.MAGMA_BLOCK, Material.CACTUS, Material.POWDER_SNOW) &&
                floor.y + 2 < world.maxHeight && floor.getRelative(0, 1, 0).isPassable && floor.getRelative(0, 2, 0).isPassable)
                return Location(world, x + 0.5, floor.y + 1.0, z + 0.5)
        }
        // A fixed offset fallback never places a player at 0,0 or in unsafe terrain.
        val x = config.spawnMinRadius
        val y = world.getHighestBlockYAt(x, 0).coerceIn(world.minHeight + 1, world.maxHeight - 4)
        for (dx in -1..1) for (dz in -1..1) {
            world.getBlockAt(x + dx, y, dz).setType(Material.STONE, false)
            world.getBlockAt(x + dx, y + 1, dz).setType(Material.AIR, false)
            world.getBlockAt(x + dx, y + 2, dz).setType(Material.AIR, false)
        }
        return Location(world, x + 0.5, y + 1.0, 0.5)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onFirstJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
        // Player data is loaded here, and terrain access stays on the server thread.
        if (!event.player.hasPlayedBefore() || endLocked(event.player.world) || onRoof(event.player.location))
            spawn()?.let { event.player.teleport(it) }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onRespawn(event: PlayerRespawnEvent) {
        if ((!event.isBedSpawn && !event.isAnchorSpawn) || endLocked(event.respawnLocation.world) || onRoof(event.respawnLocation)) spawn()?.let { event.respawnLocation = it }
    }
}
