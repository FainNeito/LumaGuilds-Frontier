package net.lumalyte.lg.interaction.menus.guild

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import net.lumalyte.lg.application.services.*
import net.lumalyte.lg.domain.entities.RankPermission
import net.lumalyte.lg.domain.values.ClaimPermission
import net.lumalyte.lg.infrastructure.services.FrontierRankClaimPermissions
import net.lumalyte.lg.infrastructure.services.GuildRolePermissionResolverBukkit
import net.lumalyte.lg.interaction.listeners.FrontierTheme
import net.lumalyte.lg.utils.inventoryframework.StaticPane
import net.lumalyte.lg.utils.inventoryframework.addPane
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

object FrontierRanksMenu : KoinComponent {
    private val ranks: RankService by inject()
    private val guilds: GuildService by inject()
    private val resolver: GuildRolePermissionResolver by inject()
    private val access: FrontierRankClaimPermissions by inject()
    private val configs: ConfigService by inject()
    private val management = listOf(RankPermission.MANAGE_RANKS,RankPermission.MANAGE_MEMBERS,
        RankPermission.MANAGE_PERMISSIONS,RankPermission.MANAGE_CLAIMS,RankPermission.MANAGE_HOME,
        RankPermission.MANAGE_RELATIONS,RankPermission.DECLARE_WAR,RankPermission.PLACE_WAR_BANNER,
        RankPermission.ACCESS_VAULT,RankPermission.DEPOSIT_TO_VAULT,RankPermission.WITHDRAW_FROM_VAULT,
        RankPermission.PLACE_VAULT,RankPermission.BREAK_VAULT,RankPermission.MANAGE_VAULT,RankPermission.MANAGE_DESCRIPTION,RankPermission.MANAGE_BANNER)

    private fun menu(title: String): Pair<ChestGui,StaticPane> {
        val gui = ChestGui(6,title); val pane = StaticPane(0,0,9,6); gui.addPane(pane)
        gui.setOnGlobalClick { it.isCancelled = true }; gui.setOnGlobalDrag { it.isCancelled = true }
        return gui to pane
    }
    private fun button(pane: StaticPane,slot: Int,material: Material,title: String,lore: List<String>,action: () -> Unit) {
        val item = ItemStack(material)
        item.editMeta { meta -> meta.displayName(FrontierTheme.heading(title)); meta.lore(lore.map { Component.text(it,FrontierTheme.cream) }) }
        pane.addItem(GuiItem(item) { it.isCancelled = true; action() },slot%9,slot/9)
    }
    fun open(player: Player,page: Int = 0) {
        val guild = guilds.getPlayerGuilds(player.uniqueId).firstOrNull() ?: run {
            player.sendMessage(FrontierTheme.message("Ranks","Join a guild first.")); return
        }
        val all = ranks.listRanks(guild.id).sortedBy { it.priority }
        val index = page.coerceIn(0,((all.size-1)/36).coerceAtLeast(0))
        val (gui,pane) = menu("Frontier · Guild ranks")
        button(pane,4,Material.WHITE_BANNER,guild.name,listOf("Select a rank to inspect its permissions.")) {}
        all.drop(index*36).take(36).forEachIndexed { i,rank ->
            button(pane,9+i,Material.IRON_SWORD,rank.name,listOf("Priority ${rank.priority}","Territory, vault and leadership controls.")) { edit(player,rank.id) }
        }
        button(pane,45,Material.ARROW,"Previous",emptyList()) { open(player,index-1) }
        button(pane,49,Material.BOOK,"Guild menu",emptyList()) { FrontierGuildMenu.open(player,configs.loadConfig()) }
        button(pane,53,Material.ARROW,"Next",emptyList()) { open(player,index+1) }
        gui.show(player)
    }
    private fun edit(player: Player,rankId: UUID, territory: Boolean = true) {
        val target = ranks.getRank(rankId) ?: return
        if (ranks.getPlayerRank(player.uniqueId,target.guildId) == null) { player.closeInventory(); return }
        val actual = (resolver as GuildRolePermissionResolverBukkit).permissionsForRank(target)
        val (gui,pane) = menu("Frontier · ${target.name}")
        button(pane,4,Material.MAP,"${target.name} permissions",listOf("Applies to all guild territory.","Raid vulnerability is always enforced.","Owner rank is protected.")) {}
        fun feedback(changed: Boolean) {
            if (!changed) player.sendMessage(FrontierTheme.message("Ranks","You can only edit lower ranks with the required authority, and cannot grant access you lack."))
            edit(player,rankId,territory)
        }
        button(pane,0,Material.GRASS_BLOCK,"Territory permissions",listOf("Building, containers and interactions.","Click to view territory access.")) { edit(player,rankId,true) }
        button(pane,8,Material.IRON_SWORD,"Guild permissions",listOf("Leadership, diplomacy and vault access.","Click to view guild authority.")) { edit(player,rankId,false) }
        if (territory) ClaimPermission.entries.forEachIndexed { i,permission ->
            val enabled = permission in actual
            button(pane,9+i,if(enabled) Material.LIME_DYE else Material.GRAY_DYE,
                permission.name.lowercase().replace('_',' ').replaceFirstChar { it.titlecase() },listOf(claimDescription(permission), "", "Status: ${if(enabled) "Allowed" else "Denied"}","Click to toggle. Changes save immediately.")) {
                try { feedback(access.toggle(player.uniqueId,rankId,permission)) }
                catch (_: Exception) { player.sendMessage(FrontierTheme.message("Ranks","Could not save permissions. Previous access retained.")) }
            }
        }
        if (!territory) management.forEachIndexed { i,permission ->
            val enabled = permission in target.permissions
            button(pane,9+i,if(enabled) Material.YELLOW_DYE else Material.GRAY_DYE,
                permission.name.lowercase().replace('_',' ').replaceFirstChar { it.titlecase() },listOf(guildDescription(permission), "", "Status: ${if(enabled) "Allowed" else "Denied"}","Click to toggle. Changes save immediately.")) {
                val fresh = ranks.getRank(rankId)
                if (fresh == null || fresh.priority == 0) feedback(false)
                else feedback(if(permission in fresh.permissions) ranks.removeRankPermission(rankId,permission,player.uniqueId)
                    else ranks.addRankPermission(rankId,permission,player.uniqueId))
            }
        }
        button(pane,49,Material.ARROW,"Back to ranks",emptyList()) { open(player) }
        gui.show(player)
    }

    private fun claimDescription(permission: ClaimPermission): String = when(permission) {
        ClaimPermission.BUILD -> "Place and break blocks in guild territory."
        ClaimPermission.HARVEST -> "Harvest and replant crops."
        ClaimPermission.CONTAINER -> "Open ordinary chests and other containers."
        ClaimPermission.DISPLAY -> "Add or remove items from display blocks."
        ClaimPermission.VEHICLE -> "Place and destroy boats and minecarts."
        ClaimPermission.SIGN -> "Edit text on signs."
        ClaimPermission.REDSTONE -> "Use buttons, levers and redstone controls."
        ClaimPermission.DOOR -> "Open doors, trapdoors and gates."
        ClaimPermission.TRADE -> "Trade with villagers and wandering traders."
        ClaimPermission.HUSBANDRY -> "Interact with and care for passive animals."
        ClaimPermission.DETONATE -> "Ignite explosives; raid rules still apply."
        ClaimPermission.EVENT -> "Trigger events caused by omen effects."
        ClaimPermission.SLEEP -> "Sleep in beds in guild territory."
        ClaimPermission.VIEW -> "View interactable blocks without editing them."
    }
    private fun guildDescription(permission: RankPermission): String = when(permission) {
        RankPermission.MANAGE_RANKS -> "Edit lower ranks; cannot grant access you lack."
        RankPermission.MANAGE_MEMBERS -> "Manage guild membership within rank limits."
        RankPermission.MANAGE_PERMISSIONS -> "Edit lower ranks' territory access."
        RankPermission.MANAGE_CLAIMS -> "Claim, unclaim and name guild territory."
        RankPermission.MANAGE_HOME -> "Set the guild's shared home."
        RankPermission.MANAGE_RELATIONS -> "Manage allies, enemies and diplomacy."
        RankPermission.DECLARE_WAR -> "Declare wars against other guilds."
        RankPermission.PLACE_WAR_BANNER -> "Place a raid-window rally banner; limits apply."
        RankPermission.ACCESS_VAULT -> "Open the shared Founding Vault inventory."
        RankPermission.DEPOSIT_TO_VAULT -> "Put items into the shared vault."
        RankPermission.WITHDRAW_FROM_VAULT -> "Take items out of the shared vault."
        RankPermission.PLACE_VAULT -> "Establish the guild's single Founding Vault."
        RankPermission.BREAK_VAULT -> "Pack the vault after confirming loss of all land."
        RankPermission.MANAGE_VAULT -> "Manage vault storage, including deposits and withdrawals."
        RankPermission.MANAGE_DESCRIPTION -> "Change the guild's public description."
        RankPermission.MANAGE_BANNER -> "Change the guild's banner design."
        else -> "Manage this guild capability within rank limits."
    }
}
