package net.lumalyte.lg.interaction.listeners

import net.lumalyte.lg.config.FrontierConfig
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.TNTPrimeEvent
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.type.RedstoneWire
import org.bukkit.block.data.type.Repeater
import org.bukkit.event.Event
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.UUID

internal data class FrontierTntBlock(val world: UUID, val x: Int, val y: Int, val z: Int)
internal class FrontierTntArmingRegistry {
    private data class Entry(val owner: UUID?, val expires: Long)
    private val entries = mutableMapOf<FrontierTntBlock, Entry>()
    fun arm(block: FrontierTntBlock, owner: UUID, now: Long, lifetime: Long): Boolean {
        val existing = entries[block]
        if (existing != null && now < existing.expires && existing.owner != owner) return false
        entries[block] = Entry(owner, now + lifetime)
        return true
    }
    fun armAutomatic(block: FrontierTntBlock, owner: UUID, now: Long, lifetime: Long): Boolean {
        val existing=entries[block]
        if (existing != null && now < existing.expires && existing.owner != owner) {
            // Two plausible operators are ambiguous; do not credit either player.
            entries[block]=Entry(null, maxOf(existing.expires,now+lifetime))
            return false
        }
        return arm(block,owner,now,lifetime)
    }
    fun consume(block: FrontierTntBlock, now: Long): UUID? = entries.remove(block)?.takeIf { now < it.expires }?.owner
    fun remove(block: FrontierTntBlock) { entries.remove(block) }
    fun prune(now: Long) { entries.entries.removeIf { now >= it.value.expires } }
}

/** Explicitly associates nearby TNT blocks with their armer, then follows explosion chains. */
internal class FrontierTntArming(private val plugin: JavaPlugin, private val config: FrontierConfig,
    private val registry: FrontierTntArmingRegistry = FrontierTntArmingRegistry(),
    private val resolvePlayer: (UUID) -> Player? = Bukkit::getPlayer) : Listener {
    private data class Pending(val player: Player, val expires: Long)
    private val pending = mutableMapOf<FrontierTntBlock, Pending>()
    private fun key(block: Block) = FrontierTntBlock(block.world.uid, block.x, block.y, block.z)
    private fun tell(player: Player, message: String) = player.sendMessage(FrontierTheme.message("Raid", message))
    private val horizontal = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

    /** Trace a bounded, loaded redstone path. Never claim TNT merely because it is nearby. */
    private fun circuit(control: Block): Set<Block>? {
        val world = control.world
        fun at(block: Block, face: BlockFace): Block? {
            val x=block.x+face.modX; val y=block.y+face.modY; val z=block.z+face.modZ
            if (kotlin.math.abs(x-control.x)>64 || kotlin.math.abs(y-control.y)>64 || kotlin.math.abs(z-control.z)>64) return null
            if (!world.isChunkLoaded(x shr 4,z shr 4)) return null
            return world.getBlockAt(x,y,z)
        }
        fun output(node: Block): Iterable<Block> {
            val out=mutableSetOf<Block>()
            val data=node.blockData
            // Redstone dust powers its supporting solid block, which can power adjacent TNT.
            if (data is RedstoneWire) {
                val below=at(node,BlockFace.DOWN)
                if (below?.type == Material.TNT) out.add(below)
                if (below != null && !below.type.isAir && below.type.isOccluding) for (face in horizontal) {
                    at(below,face)?.takeIf { it.type == Material.TNT }?.let(out::add)
                }
            }
            for (face in horizontal) {
                if (data is RedstoneWire && data.getFace(face) == RedstoneWire.Connection.NONE) continue
                if (data is Repeater && face != data.facing.oppositeFace) continue
                val neighbor=at(node,face) ?: continue
                if (neighbor.type == Material.TNT) { out.add(neighbor); continue }
                val other=neighbor.blockData
                when (other) {
                    is RedstoneWire -> if (other.getFace(face.oppositeFace) != RedstoneWire.Connection.NONE) out.add(neighbor)
                    is Repeater -> if (other.facing == face.oppositeFace) out.add(neighbor)
                }
            }
            return out
        }
        return FrontierCircuitTrace.find(control,1024,::output) { it.type == Material.TNT }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun activate(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock() == Event.Result.DENY) return
        val block=event.clickedBlock ?: return
        if (block.type != Material.LEVER && !block.type.name.endsWith("_BUTTON")) return
        val power=block.blockData as? Powerable ?: return
        if (power.isPowered || !config.schedule.stateAt(Instant.now()).active) return
        val tnt=circuit(block) ?: return
        val now=System.currentTimeMillis(); registry.prune(now)
        tnt.forEach { registry.armAutomatic(key(it),event.player.uniqueId,now,60_000) }
    }
    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.getCommand("raidarm")?.setExecutor { sender, _, _, _ ->
            if (sender !is Player) { sender.sendMessage("Only players can arm TNT."); return@setExecutor true }
            if (!config.schedule.stateAt(Instant.now()).active) {
                tell(sender, "Cannons can only be armed during the raid window."); return@setExecutor true
            }
            val now = System.currentTimeMillis(); registry.prune(now)
            val center = sender.location.block; var armed = 0; var occupied = 0
            for (x in -8..8) for (y in -8..8) for (z in -8..8) {
                val block = center.getRelative(x, y, z)
                if (block.type != Material.TNT) continue
                if (registry.arm(key(block), sender.uniqueId, now, 60_000)) armed++ else occupied++
            }
            val message = if (armed == 0) "No TNT was armed. Stand within 8 blocks of your loaded cannon."
                else "Armed $armed nearby TNT blocks for 60 seconds. Fire your cannon now."
            tell(sender, message + if (occupied > 0) " $occupied blocks are already armed by another player." else "")
            true
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun prime(event: TNTPrimeEvent) {
        val now = System.currentTimeMillis(); val key = key(event.block)
        val armed = registry.consume(key, now)?.let(resolvePlayer)
        val parent = event.primingEntity
        val source = when (parent) { is Player -> parent; is TNTPrimed -> parent.source as? Player; else -> null }
        val player = source ?: armed ?: return
        pending.entries.removeIf { now >= it.value.expires }
        pending[key] = Pending(player, now + 1000)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun spawn(event: EntitySpawnEvent) {
        val entity = event.entity as? TNTPrimed ?: return
        val now = System.currentTimeMillis()
        val value = pending.remove(key(entity.location.block)) ?: return
        if (now < value.expires && entity.source == null) entity.source = value.player
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun broken(event: BlockBreakEvent) { registry.remove(key(event.block)) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun placed(event: BlockPlaceEvent) { registry.remove(key(event.block)) }
}
