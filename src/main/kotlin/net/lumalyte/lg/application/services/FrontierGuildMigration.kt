package net.lumalyte.lg.application.services
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.domain.values.Position3D
import java.util.UUID

/** One durable ownership write; the old barrel and partitions are never modified. */
class FrontierGuildMigration(private val claims: ClaimRepository) {
 fun migrate(owner: UUID,guild: UUID,world: UUID,position: Position3D,verifiedVault: ()->Boolean): Boolean {
  val legacy=claims.getByPlayer(owner).filter { it.teamId==null && it.playerId==owner }
  if(legacy.size!=1 || claims.getByTeam(guild).isNotEmpty()) return false
  val claim=legacy.single()
  if(claim.worldId!=world || claim.position.getChunk()!=position.getChunk()) return false
  if(!verifiedVault()) return false
  return claims.updateIfOwnedBy(claim.copy(teamId=guild,position=position),owner)
 }
}
