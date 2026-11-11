package net.lumalyte.lg.application.services

import net.lumalyte.lg.application.persistence.*
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.values.Position2D
import java.util.UUID

/** One territory name and a connected chunk graph rooted at the founding vault. */
class FrontierTerritory(private val claims: ClaimRepository, private val partitions: PartitionRepository) {
    fun owned(owner: FrontierOwner): List<Claim> = (if (owner.type == FrontierOwnerType.GUILD)
        claims.getByTeam(owner.id) else claims.getByPlayer(owner.id)).filter(owner::owns)
        .sortedWith(compareBy<Claim> { it.creationTime }.thenBy { it.id })

    fun foundation(owner: FrontierOwner): Claim? = owned(owner).firstOrNull()
    fun name(claim: Claim): String = foundation(FrontierReplacementPolicy.ownerOf(claim))?.name ?: claim.name
    fun chunks(owner: FrontierOwner) = owned(owner).flatMap { partitions.getByClaim(it.id) }.flatMap { it.area.getChunks() }.toSet()

    /** Remove a non-founding chunk only when every remaining chunk still reaches the vault. */
    fun unclaim(owner: FrontierOwner, world: UUID, chunk: Position2D): Boolean {
        val root = foundation(owner) ?: return false
        if (root.worldId != world || root.position.getChunk() == chunk) return false
        val land = chunks(owner)
        if (chunk !in land) return false
        val remaining = land - chunk
        val reached = mutableSetOf<Position2D>()
        val queue = ArrayDeque<Position2D>(); queue.add(root.position.getChunk())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next !in remaining || !reached.add(next)) continue
            neighbors(next).filter { it in remaining && it !in reached }.forEach(queue::add)
        }
        if (reached != remaining) return false
        val affected = owned(owner).flatMap { partitions.getByClaim(it.id) }.filter { chunk in it.area.getChunks() }
        // Preserve all other chunks of legacy rectangular partitions before removing the originals.
        for (partition in affected) {
            for (keep in partition.area.getChunks() - chunk) {
                val low = Position2D(keep.x * 16, keep.z * 16)
                check(partitions.add(net.lumalyte.lg.domain.entities.Partition(partition.claimId,
                    net.lumalyte.lg.domain.values.Area(low, Position2D(low.x + 15, low.z + 15)))))
            }
            check(partitions.remove(partition.id)) { "Could not remove territory chunk" }
        }
        return true
    }

    fun canExtend(owner: FrontierOwner, world: UUID, chunk: Position2D): Boolean {
        val root = foundation(owner) ?: return true
        if (root.worldId != world) return false
        val land = chunks(owner)
        val visited = mutableSetOf<Position2D>()
        val queue = ArrayDeque<Position2D>()
        queue.add(root.position.getChunk())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next !in land || !visited.add(next)) continue
            neighbors(next).filter { it in land && it !in visited }.forEach(queue::add)
        }
        return chunk !in land && neighbors(chunk).any { it in visited }
    }

    fun rename(owner: FrontierOwner, name: String): Boolean {
        if (name.isBlank() || name.length > 50) return false
        val originals = owned(owner)
        if (originals.isEmpty()) return false
        // The founding record is canonical, including if a later legacy-row update fails.
        for (claim in originals) check(claims.update(claim.copy(name = name))) { "Could not save territory name" }
        return true
    }

    companion object {
        fun neighbors(p: Position2D) = listOf(Position2D(p.x-1,p.z), Position2D(p.x+1,p.z), Position2D(p.x,p.z-1), Position2D(p.x,p.z+1))
    }
}
