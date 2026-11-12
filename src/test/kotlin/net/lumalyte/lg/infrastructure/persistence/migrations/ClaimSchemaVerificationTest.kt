package net.lumalyte.lg.infrastructure.persistence.migrations

import io.mockk.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertTrue

class ClaimSchemaVerificationTest {
    @Test fun `startup repairs current permission tables and stays quiet on next restart`() {
        val logger = mockk<ComponentLogger>(relaxed=true)
        val plugin = mockk<JavaPlugin>(relaxed=true) {
            every { getComponentLogger() } returns logger
        }
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            // The party repository owns creation of this unrelated table.
            db.createStatement().use { it.execute("CREATE TABLE party_requests (id TEXT PRIMARY KEY)") }
            val migration = SQLiteMigrations(plugin, db)
            migration.migrate()
            db.createStatement().use {
                it.execute("DROP TABLE claim_default_permissions")
                it.execute("DROP TABLE claim_player_permissions")
            }
            migration.migrate()
            for (table in listOf("claim_default_permissions", "claim_player_permissions")) {
                db.createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM sqlite_master WHERE name='$table'").use {
                        assertTrue(it.next(), "Missing $table after repair")
                    }
                }
            }
            clearMocks(logger, answers=false)
            migration.migrate()
            verify(exactly=0) { logger.warn(any<Component>()) }
        }
    }
}
