package net.lumalyte.lg.infrastructure.services

import net.lumalyte.lg.application.services.FrontierRaidClaim
import net.lumalyte.lg.application.services.WarBannerService
import net.lumalyte.lg.common.PluginKeys
import net.lumalyte.lg.domain.values.Position3D
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Banner
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** Completes banner removal before the expired raid-claim journal is discarded. */
class FrontierRaidBannerCleanup(
    private val banners: WarBannerService,
    private val worldById: (UUID) -> World? = Bukkit::getWorld,
) {
    fun remove(claim: FrontierRaidClaim): Boolean {
        val id = claim.bannerId ?: return true
        val world = worldById(claim.chunk.worldId) ?: return false // Retry once the world has loaded.
        val state = banners.state(claim.attacker)
        val matching = state?.takeIf { it.bannerId == id }
        if (matching != null && matching.worldId != claim.chunk.worldId) return false
        val pos = claim.bannerPosition ?: matching?.let { Position3D(it.x, it.y, it.z) } ?: return false
        if (Math.floorDiv(pos.x, 16) != claim.chunk.x || Math.floorDiv(pos.z, 16) != claim.chunk.z) return false

        if (matching?.active == true) {
            banners.destroyAt(matching.worldId, matching.x, matching.y, matching.z)
            // A failed SQL deactivation must retain the cleanup journal for a retry.
            if (banners.state(claim.attacker)?.let { it.bannerId == id && it.active } == true) return false
        }

        val block = world.getBlockAt(pos.x, pos.y, pos.z)
        fun stillPresent(): Boolean = (block.state as? Banner)?.persistentDataContainer
            ?.get(PluginKeys.WAR_BANNER_ID, PersistentDataType.STRING) == id.toString()
        if (stillPresent()) block.type = Material.AIR
        return !stillPresent()
    }
}
