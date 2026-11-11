package net.lumalyte.lg.infrastructure.services

import net.lumalyte.lg.application.services.RankService
import net.lumalyte.lg.domain.entities.Rank
import net.lumalyte.lg.domain.entities.RankPermission
import net.lumalyte.lg.domain.values.ClaimPermission
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Frontier-only rank overrides. Save-before-publish prevents failed writes changing access. */
class FrontierRankClaimPermissions(
    private val file: File,
    private val ranks: RankService,
    private val effective: (Rank) -> Set<ClaimPermission>,
    private val invalidate: (UUID) -> Unit,
) {
    private var state = YamlConfiguration().apply { if (file.exists()) load(file) }
    private fun path(rank: Rank) = "guilds.${rank.guildId}.${rank.id}"
    @Synchronized fun get(rank: Rank): Set<ClaimPermission>? {
        val path = path(rank)
        if (!state.contains(path)) return null
        check(state.isList(path)) { "Invalid Frontier rank permission list" }
        return state.getStringList(path).map { ClaimPermission.valueOf(it) }.toSet()
    }

    @Synchronized fun toggle(actorId: UUID, rankId: UUID, permission: ClaimPermission): Boolean {
        val target = ranks.getRank(rankId) ?: return false
        val actor = ranks.getPlayerRank(actorId,target.guildId) ?: return false
        if (target.priority == 0 || actor.priority >= target.priority) return false
        if (actor.priority != 0 && !actor.permissions.containsAll(setOf(RankPermission.MANAGE_RANKS,RankPermission.MANAGE_PERMISSIONS))) return false
        val current = effective(target)
        val enabling = permission !in current
        if (enabling && actor.priority != 0 && permission !in effective(actor)) return false
        val changed = if (enabling) current + permission else current - permission
        val next = YamlConfiguration().apply { loadFromString(state.saveToString()); set(path(target),changed.map { it.name }.sorted()) }
        val temporary = file.toPath().resolveSibling(file.name + ".tmp")
        Files.writeString(temporary,next.saveToString())
        Files.move(temporary,file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        state = next
        invalidate(target.guildId)
        return true
    }
}
