package net.lumalyte.lg.config

import net.lumalyte.lg.domain.values.FrontierRaidSchedule
import net.lumalyte.lg.domain.values.PlayerActionType
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class FrontierConfig(
    val enabled: Boolean = false,
    val schedule: FrontierRaidSchedule = FrontierRaidSchedule(
        ZoneId.of("UTC"), LocalTime.of(20, 0), Duration.ofHours(1)),
    val requiredHits: Int = 20,
    val hitInterval: Duration = Duration.ofSeconds(2),
    val replacementCooldown: Duration = Duration.ofMinutes(30),
    val worldName: String = "frontier",
    val borderSize: Int = 5000,
    val spawnMinRadius: Int = 80,
    val spawnMaxRadius: Int = 160,
    val reservedSpawnRadius: Int = 0,
    val claimTitles: Boolean = true,
    val foreignClaimBufferChunks: Int = 4,
    val endBorderSize: Int = 512,
    val raidMiningSpeed: Double = 0.35,
    val tntVaultDamage: Int = 4,
) {
    init {
        require(foreignClaimBufferChunks in 0..8)
        require(endBorderSize in 256..1024)
        require(raidMiningSpeed > 0.0 && raidMiningSpeed <= 1.0)
        require(tntVaultDamage in 1..1000)
        require(requiredHits in 1..1000)
        require(hitInterval >= Duration.ofMillis(100) && hitInterval <= Duration.ofMinutes(1))
        require(!replacementCooldown.isNegative && replacementCooldown <= Duration.ofDays(1))
        require(worldName.isNotBlank())
        require(reservedSpawnRadius >= 0 && reservedSpawnRadius < borderSize / 2)
        require(borderSize in 512..30000)
        require(spawnMinRadius >= 16 && spawnMaxRadius > spawnMinRadius && spawnMaxRadius < borderSize / 2 - 32)
    }
    fun allows(action: PlayerActionType, now: Instant): Boolean =
        enabled && action in raidActions && schedule.stateAt(now).active

    fun permitsLand(area: net.lumalyte.lg.domain.values.Area): Boolean {
        if (!enabled) return true
        val low = area.lowerPosition2D
        val high = area.upperPosition2D
        val radius = reservedSpawnRadius
        return low.x >= -borderSize / 2 && low.z >= -borderSize / 2 &&
            high.x < borderSize / 2 && high.z < borderSize / 2 &&
            (radius == 0 || high.x < -radius || low.x > radius || high.z < -radius || low.z > radius)
    }

    companion object {
        private val raidActions = setOf(
            PlayerActionType.BREAK_BLOCK, PlayerActionType.PLACE_BLOCK,
            PlayerActionType.OPEN_CONTAINER, PlayerActionType.OPEN_DOOR,
            PlayerActionType.USE_REDSTONE, PlayerActionType.HARVEST_CROP,
        )
    }
}
