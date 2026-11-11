package net.lumalyte.lg.interaction.listeners
import org.junit.jupiter.api.Test
import kotlin.test.*
class FrontierMapBoardPolicyTest {
 @Test fun `map never replaces teams or non-sidebar objectives`() {
  assertTrue(FrontierMapBoardPolicy.canCreate(0,0))
  assertFalse(FrontierMapBoardPolicy.canCreate(0,1))
  assertFalse(FrontierMapBoardPolicy.canCreate(1,0))
 }
}
