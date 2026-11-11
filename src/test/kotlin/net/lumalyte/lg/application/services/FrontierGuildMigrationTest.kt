package net.lumalyte.lg.application.services
import io.mockk.*
import net.lumalyte.lg.application.persistence.ClaimRepository
import net.lumalyte.lg.domain.entities.Claim
import net.lumalyte.lg.domain.values.Position3D
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*
class FrontierGuildMigrationTest {
 @Test fun `migration only changes ownership after vault verification and preserves identity`() {
  val owner=UUID.randomUUID();val guild=UUID.randomUUID();val world=UUID.randomUUID()
  val old=Claim(world,owner,null,Position3D(0,64,0),"Keep")
  val repo=mockk<ClaimRepository>(relaxed=true)
  every { repo.getByPlayer(owner) } returns setOf(old)
  every { repo.updateIfOwnedBy(any(),owner) } returns true
  val service=FrontierGuildMigration(repo)
  assertFalse(service.migrate(owner,guild,world,Position3D(1,64,1)){false})
  verify(exactly=0) { repo.updateIfOwnedBy(any(),any()) }
  every { repo.updateIfOwnedBy(any(),owner) } returns false
  assertFalse(service.migrate(owner,guild,world,Position3D(1,64,1)){true})
  assertNull(old.teamId)
  every { repo.updateIfOwnedBy(any(),owner) } returns true
  assertTrue(service.migrate(owner,guild,world,Position3D(1,64,1)){true})
  verify { repo.updateIfOwnedBy(match { it.id==old.id && it.teamId==guild && it.name==old.name && it.position==Position3D(1,64,1) },owner) }
  assertNull(old.teamId)
 }
 @Test fun `migration rejects occupied guild and wrong founding chunk before placing a vault`() {
  val owner=UUID.randomUUID();val guild=UUID.randomUUID();val world=UUID.randomUUID()
  val old=Claim(world,owner,null,Position3D(-1,64,-1),"Keep")
  val repo=mockk<ClaimRepository>(relaxed=true)
  every { repo.getByPlayer(owner) } returns setOf(old)
  val place=mockk<()->Boolean>()
  assertFalse(FrontierGuildMigration(repo).migrate(owner,guild,world,Position3D(0,64,0),place))
  every { repo.getByTeam(guild) } returns setOf(old.copy(teamId=guild))
  assertFalse(FrontierGuildMigration(repo).migrate(owner,guild,world,Position3D(-2,64,-2),place))
  verify(exactly=0) { place() }
 }
}
