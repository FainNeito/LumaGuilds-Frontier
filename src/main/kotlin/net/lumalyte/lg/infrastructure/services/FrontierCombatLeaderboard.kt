package net.lumalyte.lg.infrastructure.services

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** All-time Frontier scores, separate from the SMP progression leaderboard. */
class FrontierCombatLeaderboard(private val file: File) {
    private var state=YamlConfiguration().apply { if(file.exists()) load(file) }
    @Synchronized fun kill(killer: UUID, victim: UUID) { if(killer!=victim) increment("kills",killer) }
    @Synchronized fun vault(attacker: UUID, defender: UUID) { if(attacker!=defender) increment("vaults",attacker) }
    @Synchronized fun kills() = ranking("kills")
    @Synchronized fun vaults() = ranking("vaults")
    private fun ranking(section: String): List<Pair<UUID,Long>> = state.getConfigurationSection(section)?.getKeys(false)
        ?.map { UUID.fromString(it) to state.getLong("$section.$it") }
        ?.filter { it.second>0 }?.sortedWith(compareByDescending<Pair<UUID,Long>> { it.second }.thenBy { it.first.toString() }) ?: emptyList()
    private fun increment(section: String,id: UUID) {
        val next=YamlConfiguration().apply { loadFromString(state.saveToString()) }
        val key="$section.$id"; next.set(key,Math.addExact(next.getLong(key),1L))
        file.parentFile?.mkdirs()
        val temporary=file.toPath().resolveSibling(file.name+".tmp")
        Files.writeString(temporary,next.saveToString())
        Files.move(temporary,file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        state=next
    }
}
