package net.lumalyte.lg.interaction.menus.guild

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import net.lumalyte.lg.application.services.*
import net.lumalyte.lg.domain.entities.*
import net.lumalyte.lg.interaction.listeners.FrontierTheme
import net.lumalyte.lg.utils.inventoryframework.StaticPane
import net.lumalyte.lg.utils.inventoryframework.addPane
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

/** Small Frontier view over durable diplomacy; never grants claim access or exposes SMP war menus. */
object FrontierDiplomacyMenu : KoinComponent {
    private val guilds: GuildService by inject()
    private val relations: RelationService by inject()
    private val config: ConfigService by inject()
    private fun alliancesEnabled() = (relations as? net.lumalyte.lg.infrastructure.services.RelationServiceBukkit)?.allianceLimit != 0
    private fun own(player: Player) = guilds.getPlayerGuilds(player.uniqueId).firstOrNull()
    private fun item(pane: StaticPane,slot: Int,material: Material,name: String,lore: List<String>,action: () -> Unit) {
        val stack = ItemStack(material)
        stack.editMeta { it.displayName(FrontierTheme.heading(name)); it.lore(lore.map { line -> Component.text(line,FrontierTheme.cream) }) }
        pane.addItem(GuiItem(stack) { it.isCancelled = true; action() },slot%9,slot/9)
    }
    private fun gui(title: String,rows: Int): Pair<ChestGui,StaticPane> {
        val gui=ChestGui(rows,title); val pane=StaticPane(0,0,9,rows);gui.addPane(pane)
        gui.setOnGlobalClick { it.isCancelled=true };gui.setOnGlobalDrag { it.isCancelled=true };return gui to pane
    }
    fun open(player: Player,page: Int = 0) {
        val owner=own(player) ?: run { player.sendMessage(FrontierTheme.message("Diplomacy","Join a guild first."));return }
        val targets=guilds.getAllGuilds().filter { it.id!=owner.id }.sortedBy { it.name }
        val index=page.coerceIn(0,((targets.size-1)/36).coerceAtLeast(0))
        val (gui,pane)=gui("Frontier · Diplomacy",6)
        item(pane,4,Material.BOOK,if (alliancesEnabled()) "Allies & enemies" else "Guild relations",listOf(if (alliancesEnabled()) "Select a guild. Alliances require acceptance." else "Select a guild to manage relations.","Relations never grant territory or vault access.")) {}
        targets.drop(index*36).take(36).forEachIndexed { i,target ->
            val relation=relations.getRelation(owner.id,target.id)
            item(pane,9+i,Material.WHITE_BANNER,target.name,listOf("Relation: ${relations.getRelationType(owner.id,target.id)}",
                if(relation?.status==RelationStatus.PENDING) "Pending request" else "Click to manage relations")) { target(player,target.id) }
        }
        item(pane,45,Material.ARROW,"Previous",emptyList()) { open(player,index-1) }
        item(pane,49,Material.BOOK,"Guild menu",emptyList()) { FrontierGuildMenu.open(player,config.loadConfig()) }
        item(pane,53,Material.ARROW,"Next",emptyList()) { open(player,index+1) }
        gui.show(player)
    }
    private fun target(player: Player,targetId: UUID) {
        val owner=own(player) ?: return
        val target=guilds.getGuild(targetId) ?: return
        val relation=relations.getRelation(owner.id,targetId)
        val (gui,pane)=gui("Diplomacy · ${target.name}",3)
        fun action(action: (UUID)->Boolean) {
            // Membership and management authority are checked again after the menu was opened.
            val current=own(player)
            val ok=current?.id==owner.id && relations.canManageRelations(player.uniqueId,owner.id) && action(owner.id)
            player.sendMessage(FrontierTheme.message("Diplomacy",if(ok) "Relations updated." else "That change is unavailable or you lack permission."))
            open(player)
        }
        item(pane,4,Material.WHITE_BANNER,target.name,listOf("Relation: ${relations.getRelationType(owner.id,targetId)}")) {}
        if(relation?.status==RelationStatus.PENDING) {
            val incoming=relation.requestingGuildId==targetId
            if(incoming && (relation.type != RelationType.ALLY || alliancesEnabled())) item(pane,11,Material.LIME_DYE,"Accept request",emptyList()) { action { id ->
                when(relation.type) {
                    RelationType.ALLY -> relations.acceptAlliance(relation.id,id,player.uniqueId)!=null
                    RelationType.NEUTRAL -> relations.acceptUnenemy(relation.id,id,player.uniqueId)!=null
                    else -> false
                }
            } }
            item(pane,if (incoming && relation.type == RelationType.ALLY && !alliancesEnabled()) 13 else 15,Material.RED_DYE,if(incoming) "Reject request" else "Cancel request",emptyList()) { action { id ->
                if(incoming) relations.rejectRequest(relation.id,id,player.uniqueId) else relations.cancelRequest(relation.id,id,player.uniqueId)
            } }
        } else when(relations.getRelationType(owner.id,targetId)) {
            RelationType.ALLY -> item(pane,13,Material.SHEARS,"End alliance",listOf("Click to end this alliance.")) { action { id -> relation!=null && relations.breakAlliance(relation.id,id,player.uniqueId) } }
            RelationType.ENEMY -> item(pane,13,Material.WHITE_BANNER,"Offer peace",listOf("The other guild must accept.")) { action { id -> relations.requestUnenemy(id,targetId,player.uniqueId)!=null } }
            else -> {
                if (alliancesEnabled()) item(pane,11,Material.LIME_DYE,"Request alliance",emptyList()) { action { id -> relations.requestAlliance(id,targetId,player.uniqueId)!=null } }
                item(pane,if (alliancesEnabled()) 15 else 13,Material.RED_BANNER,"Declare enemy",listOf("Marks rivalry; does not open claims.","This is not a scheduled war declaration.")) { action { id -> relations.declareWar(id,targetId,player.uniqueId)!=null } }
            }
        }
        item(pane,22,Material.ARROW,"Back",emptyList()) { open(player) };gui.show(player)
    }
}
