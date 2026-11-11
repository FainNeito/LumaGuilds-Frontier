package net.lumalyte.lg.interaction.menus.guild

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import net.lumalyte.lg.application.services.*
import net.lumalyte.lg.domain.entities.RelationType
import net.lumalyte.lg.infrastructure.services.FrontierWars
import net.lumalyte.lg.interaction.listeners.FrontierTheme
import net.lumalyte.lg.utils.inventoryframework.StaticPane
import net.lumalyte.lg.utils.inventoryframework.addPane
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object FrontierWarMenu : KoinComponent {
    private val wars: FrontierWars by inject()
    private val guilds: GuildService by inject()
    private val relations: RelationService by inject()
    private val config: ConfigService by inject()
    private fun menu(title:String,rows:Int):Pair<ChestGui,StaticPane> {
        val gui=ChestGui(rows,title);val pane=StaticPane(0,0,9,rows);gui.addPane(pane)
        gui.setOnGlobalClick { it.isCancelled=true };gui.setOnGlobalDrag { it.isCancelled=true };return gui to pane
    }
    private fun button(pane:StaticPane,slot:Int,type:Material,title:String,lore:List<String>,action:()->Unit) {
        val item=ItemStack(type);item.editMeta { it.displayName(FrontierTheme.heading(title));it.lore(lore.map { line->Component.text(line,FrontierTheme.cream) }) }
        pane.addItem(GuiItem(item) { it.isCancelled=true;action() },slot%9,slot/9)
    }
    private fun confirm(player:Player,title:String,lines:List<String>,action:()->Unit) {
        val (gui,pane)=menu(title,3)
        button(pane,13,Material.RED_BANNER,"Confirm",lines) {
            try { action() } catch(_:Exception) { player.sendMessage(FrontierTheme.message("War","Could not save the war change. Try again later.")) }
            open(player)
        }
        button(pane,22,Material.ARROW,"Cancel",emptyList()) { open(player) };gui.show(player)
    }
    fun open(player:Player,page:Int=0) {
        val guild=guilds.getPlayerGuilds(player.uniqueId).firstOrNull() ?: run { player.sendMessage(FrontierTheme.message("War","Join a guild first."));return }
        val current=wars.current(guild.id);val (gui,pane)=menu("Frontier · Warfare",6)
        val zone=config.loadConfig().frontier.schedule.zone
        button(pane,4,Material.RED_BANNER,current?.let { "${wars.name(it.attacker)} vs ${wars.name(it.defender)}" } ?: "Prepare for war",
            current?.let { listOf("Starts ${it.starts.atZone(zone)}","Ends ${it.ends.atZone(zone)}","Destroy the enemy Founding Vault to win.") }
                ?: listOf("One war per guild; next raid window only.","15-minute notice; 24h declaration cooldown.")) {}
        if(current==null) {
            val enemies=guilds.getAllGuilds().filter { it.id!=guild.id && relations.getRelationType(guild.id,it.id)==RelationType.ENEMY }.sortedBy { it.name }
            val index=page.coerceIn(0,((enemies.size-1)/18).coerceAtLeast(0))
            enemies.drop(index*18).take(18).forEachIndexed { i,target ->
                button(pane,9+i,Material.IRON_SWORD,target.name,listOf("Review war declaration.")) {
                    confirm(player,"Declare war?",listOf("Next raid window; no acceptance required.","Removing founding land before battle forfeits.")) {
                        player.sendMessage(FrontierTheme.message("War",wars.declare(player.uniqueId,guild.id,target.id)))
                    }
                }
            }
            button(pane,45,Material.ARROW,"Previous",emptyList()) { open(player,index-1) }
            button(pane,53,Material.ARROW,"Next",emptyList()) { open(player,index+1) }
        } else {
            button(pane,11,Material.WHITE_BANNER,if(current.peaceFrom==current.other(guild.id)) "Accept peace" else "Offer peace",listOf("Mutual peace ends this war as a draw.")) {
                confirm(player,"Confirm peace?",listOf("No land transfer or raid immunity.")) {
                    if(!wars.peace(player.uniqueId,guild.id)) player.sendMessage(FrontierTheme.message("War","Your rank cannot manage this war."))
                }
            }
            button(pane,15,Material.BARRIER,"Surrender",listOf("Your opponent wins. Claims retain normal rules.")) {
                confirm(player,"Surrender?",listOf("Records a defeat for your guild.")) {
                    if(!wars.surrender(player.uniqueId,guild.id)) player.sendMessage(FrontierTheme.message("War","Your rank cannot surrender this war."))
                }
            }
        }
        wars.records().filter { it.involves(guild.id) && it.reason!=null }.sortedByDescending { it.ends }.take(9).forEachIndexed { i,war ->
            button(pane,36+i,Material.BOOK,"vs ${wars.name(war.other(guild.id))}",listOf(if(war.winner==null) "Draw" else if(war.winner==guild.id) "Victory" else "Defeat",war.reason!!)) {}
        }
        button(pane,47,Material.RED_BANNER,"War banner",listOf("Raid window only; 15-minute rally point.")) { player.closeInventory();player.performCommand("guild warbanner") }
        button(pane,49,Material.BOOK,"Guild menu",emptyList()) { FrontierGuildMenu.open(player,config.loadConfig()) }
        button(pane,51,Material.CLOCK,"Raid schedule",emptyList()) { player.closeInventory();player.performCommand("raidinfo") };gui.show(player)
    }
}
