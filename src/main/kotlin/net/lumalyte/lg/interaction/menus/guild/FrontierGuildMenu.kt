package net.lumalyte.lg.interaction.menus.guild

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.lumalyte.lg.config.MainConfig
import net.lumalyte.lg.utils.inventoryframework.StaticPane
import net.lumalyte.lg.utils.inventoryframework.addPane
import net.lumalyte.lg.interaction.listeners.FrontierTheme
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import net.badgersmc.nexus.i18n.LangService
import net.lumalyte.lg.infrastructure.i18n.guiTitle
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** A vanilla inventory with no SMP dashboard links or resource-pack dependency. */
object FrontierGuildMenu : KoinComponent {
    private val lang: LangService by inject()
    private val guilds: net.lumalyte.lg.application.services.GuildService by inject()
    private val relations: net.lumalyte.lg.application.services.RelationService by inject()
    private fun alliancesEnabled() = (relations as? net.lumalyte.lg.infrastructure.services.RelationServiceBukkit)?.allianceLimit != 0
    fun open(player: Player, config: MainConfig) {
        val gui = ChestGui(3, lang.guiTitle("frontier_menu.title"))
        val pane = StaticPane(0,0,9,3); gui.addPane(pane)
        gui.setOnGlobalClick { it.isCancelled = true }; gui.setOnGlobalDrag { it.isCancelled = true }
        fun button(x: Int,y: Int,material: Material,title: String,lore: List<String>,action: () -> Unit) {
            val item = ItemStack(material)
            item.editMeta { it.displayName(FrontierTheme.heading(title)); it.lore(lore.map { line -> Component.text(line,FrontierTheme.cream) }) }
            pane.addItem(GuiItem(item) { player.closeInventory(); FrontierTheme.cue(player,FrontierTheme.Cue.CLICK); action() },x,y)
        }
        val guild = guilds.getPlayerGuilds(player.uniqueId).firstOrNull()
        button(4,0,Material.WHITE_BANNER,guild?.name ?: "Frontier",listOf("Information, roster and relations.")) { player.performCommand("guild info") }
        button(2,0,Material.WRITABLE_BOOK,"Guild chat",listOf("Toggle private guild conversation.")) { player.performCommand("guild chat") }
        if (alliancesEnabled()) button(6,0,Material.FEATHER,"Ally chat",listOf("Toggle conversation with your guild and allies.","Quick message: /gac <message>")) { player.performCommand("guild allychat") }
        button(1,1,Material.IRON_SWORD,"Ranks & permissions",listOf("Territory, vault access and leadership.")) { FrontierRanksMenu.open(player) }
        button(3,1,Material.MAP,"Territory",listOf("Claiming, borders, map and home.")) { tools(player,config) }
        button(5,1,Material.CHEST,"Guild vault",listOf("Open shared storage inside your guild's land.","Unavailable in combat. Use /g vault.")) { player.performCommand("guild vault") }
        button(7,1,Material.BOOK,"Diplomacy",listOf(if (alliancesEnabled()) "Allies, enemies and requests." else "Enemies and peace requests.")) { FrontierDiplomacyMenu.open(player) }
        button(if (alliancesEnabled()) 2 else 6,if (alliancesEnabled()) 2 else 0,Material.BOOK,"Survival guide",listOf("Claiming, travel and raid rules.")) { player.performCommand("serverinfo") }
        button(if (alliancesEnabled()) 4 else 8,2,Material.BARRIER,"Close",emptyList()) {}
        button(if (alliancesEnabled()) 6 else 4,2,Material.RED_BANNER,"Warfare",listOf("Declarations, objectives and war banners.")) { FrontierWarMenu.open(player) }
        button(0,2,Material.PLAYER_HEAD,"Combat leaderboards",listOf("See player kills and guild vault victories.","All-time scores · /frontiertop")) { player.performCommand("frontiertop") }
        gui.show(player)
    }
    private fun tools(player: Player, config: MainConfig) {
        val gui = ChestGui(3, "Frontier | Territory")
        val pane = StaticPane(0, 0, 9, 3)
        gui.addPane(pane)
        gui.setOnGlobalClick { it.isCancelled = true }
        gui.setOnGlobalDrag { it.isCancelled = true }
        fun button(x: Int, y: Int, material: Material, title: String, lines: List<String>, action: () -> Unit) {
            val item = ItemStack(material)
            item.editMeta { meta ->
                meta.displayName(FrontierTheme.heading(title).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
                meta.lore(lines.map { Component.text(it, FrontierTheme.cream).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false) })
            }
            pane.addItem(GuiItem(item) {
                player.closeInventory()
                FrontierTheme.cue(player, FrontierTheme.Cue.CLICK)
                action()
            }, x, y)
        }
        fun command(value: String) { player.performCommand(value) }
        button(1,0,Material.GRASS_BLOCK,"Claim this chunk",listOf("Preview before confirming.","Your first claim establishes the Founding Vault.")) { command("claim") }
        button(3,0,Material.ENDER_EYE,"Toggle borders",listOf("Show nearby borders for up to 60 seconds.","Click again or use /claim outline to hide them.")) { command("claimoutline") }
        button(5,0,Material.FILLED_MAP,"Claim map",listOf("Toggle the nearby territory map.")) { command("claimmap") }
        button(7,0,Material.LEATHER_BOOTS,"Walking autoclaim",listOf("Preview and confirm connected chunk claiming.","Stop with /autoclaim off.")) { command("autoclaim") }
        button(2,1,Material.RED_BED,"Guild home",listOf("Teleport to your shared guild home.","Set with /g sethome. No raids or combat.")) { command("guildhome") }
        button(4,1,Material.CLOCK,"Raid status",listOf("View the daily raid schedule and objectives.")) { command("raidinfo") }
        button(6,1,Material.NAME_TAG,"Rename territory",listOf("One name for all your guild's claims.")) {
            player.sendMessage(FrontierTheme.message("Claim", "Click to enter a territory name.").clickEvent(ClickEvent.suggestCommand("/claim rename ")))
        }
        button(0,2,Material.SHEARS,"Unclaim this chunk",listOf("Preview removal; confirmation required.","Founding and connecting chunks cannot be removed.")) { command("unclaim") }
        button(4,2,Material.ARROW,"Back",listOf("Return to your guild menu.")) { open(player,config) }
        button(8,2,Material.CHEST,"Pack Founding Vault",listOf("Look at the vault. Outside raids only.","WARNING: confirms removal of ALL guild claims.","Stored items remain; no raid cooldown.")) { command("vaultpickup") }
        gui.show(player)
    }
}
