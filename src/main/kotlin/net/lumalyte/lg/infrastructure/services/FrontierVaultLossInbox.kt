package net.lumalyte.lg.infrastructure.services

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Durable, independent notices for each member who was offline when a vault fell. */
class FrontierVaultLossInbox(private val file: File) {
    data class Notice(val id: UUID, val guild: String, val attacker: String?, val time: String)
    private var state = YamlConfiguration().apply { if (file.exists()) load(file) }

    @Synchronized fun record(recipients: Set<UUID>, guild: String, attacker: String?, time: String) {
        if (recipients.isEmpty()) return
        val next = copyState()
        for (player in recipients) {
            val key = "players.$player.${UUID.randomUUID()}"
            next.set("$key.guild", guild)
            next.set("$key.attacker", attacker)
            next.set("$key.time", time)
        }
        save(next)
    }

    @Synchronized fun pending(player: UUID): List<Notice> {
        val section = state.getConfigurationSection("players.$player") ?: return emptyList()
        return section.getKeys(false).map { id ->
            Notice(UUID.fromString(id), section.getString("$id.guild") ?: "Your guild",
                section.getString("$id.attacker"), section.getString("$id.time") ?: "")
        }.sortedWith(compareBy<Notice> { it.time }.thenBy { it.id.toString() })
    }

    @Synchronized fun acknowledge(player: UUID, id: UUID) {
        val next = copyState()
        next.set("players.$player.$id", null)
        if (next.getConfigurationSection("players.$player")?.getKeys(false)?.isEmpty() == true)
            next.set("players.$player", null)
        save(next)
    }

    private fun copyState() = YamlConfiguration().apply { loadFromString(state.saveToString()) }
    private fun save(next: YamlConfiguration) {
        file.parentFile?.mkdirs()
        val temporary = file.toPath().resolveSibling(file.name + ".tmp")
        Files.writeString(temporary, next.saveToString())
        Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        state = next
    }
}
