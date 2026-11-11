package net.lumalyte.lg.config
import net.lumalyte.lg.domain.values.*
import org.junit.jupiter.api.Test
import io.mockk.*
import kotlin.test.*
class FrontierServerTerritoryTest {
 @Test fun `watchers ruin covers exactly sixteen chunks per axis with no outside buffer`() {
  val zone=FrontierServerTerritory()
  val config=FrontierConfig(enabled=true,serverTerritory=zone)
  assertEquals("Watcher's Ruin",zone.name)
  for(x in -8..7) for(z in -8..7) assertFalse(config.permitsLand(Area(Position2D(x*16,z*16),Position2D(x*16+15,z*16+15))))
  for(x in listOf(-9,8)) assertTrue(config.permitsLand(Area(Position2D(x*16,0),Position2D(x*16+15,15))))
  assertTrue(zone.contains(-128,-128));assertTrue(zone.contains(127,127));assertFalse(zone.contains(128,0));assertFalse(zone.contains(-129,0))
 }
 @Test fun `disabled reservation does not affect claiming and bounds must align to chunks`() {
  assertTrue(FrontierConfig(enabled=true).permitsLand(Area(Position2D(0,0),Position2D(15,15))))
  assertFailsWith<IllegalArgumentException> { FrontierServerTerritory(minChunkX=8,maxChunkX=7) }
 }
 @Test fun `normal placement validator reserves server chunks but permits directly adjacent chunks`() {
  val world=java.util.UUID.randomUUID()
  val worlds=mockk<net.lumalyte.lg.application.services.WorldManipulationService>(relaxed=true)
  every { worlds.isClaimWorld(world,any()) } returns true
  every { worlds.isInsideWorldBorder(world,any()) } returns true
  val validator=net.lumalyte.lg.application.actions.claim.IsNewClaimLocationValid(mockk(relaxed=true),mockk(relaxed=true),worlds,
   MainConfig(claimGeometry=ClaimGeometry.CHUNKS,frontier=FrontierConfig(enabled=true,serverTerritory=FrontierServerTerritory())))
  assertNotEquals(net.lumalyte.lg.application.results.claim.IsNewClaimLocationValidResult.Valid,validator.execute(Position3D(0,64,0),world))
  assertEquals(net.lumalyte.lg.application.results.claim.IsNewClaimLocationValidResult.Valid,validator.execute(Position3D(128,64,0),world))
 }
 @Test fun `server territory configuration can be renamed resized and disabled`() {
  val yaml=org.bukkit.configuration.file.YamlConfiguration()
  yaml.set("frontier.enabled",true);yaml.set("claim_geometry","CHUNKS")
  val service=net.lumalyte.lg.infrastructure.services.ConfigServiceBukkit(yaml)
  assertEquals("Watcher's Ruin",service.loadConfig().frontier.serverTerritory?.name)
  yaml.set("frontier.server_territory.name","Ruin");yaml.set("frontier.server_territory.max_chunk_x",9)
  assertTrue(service.loadConfig().frontier.serverTerritory!!.contains(159,0))
  assertEquals("Ruin",service.loadConfig().frontier.serverTerritory?.name)
  yaml.set("frontier.server_territory.enabled",false)
  assertNull(service.loadConfig().frontier.serverTerritory)
 }
}
