package net.lumalyte.lg.application.services

import net.lumalyte.lg.domain.values.Area
import net.lumalyte.lg.domain.values.Position3D
import java.util.UUID

interface WorldManipulationService {
    fun isClaimWorld(worldId: UUID, worldName: String): Boolean? = null
    fun breakWithoutItemDrop(worldId: UUID, position: Position3D): Boolean
    fun isInsideWorldBorder(worldId: UUID, area: Area): Boolean
}
