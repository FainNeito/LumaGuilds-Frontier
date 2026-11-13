package net.lumalyte.lg.interaction.menus.guild

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import net.lumalyte.lg.application.services.GuildService
import net.lumalyte.lg.infrastructure.services.*
import net.lumalyte.lg.interaction.listeners.FrontierTheme
import net.lumalyte.lg.interaction.menus.GuildBannerItemResolver
import net.lumalyte.lg.utils.inventoryframework.StaticPane
import net.lumalyte.lg.utils.inventoryframework.addPane
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin

internal object FrontierDialogCapability {
    fun supported(protocol: Int?): Boolean = protocol != null && protocol >= 771
    fun supported(player: Player): Boolean {
        if (RecruitmentPresentation.supportsDialog(player)) return true
        val via=Bukkit.getPluginManager().getPlugin("ViaVersion")?.takeIf { it.isEnabled } ?: return false
        return runCatching {
            val loader=via.javaClass.classLoader
            val api=Class.forName("com.viaversion.viaversion.api.Via",true,loader).getMethod("getAPI").invoke(null)
            val apiType=Class.forName("com.viaversion.viaversion.api.ViaAPI",true,loader)
            supported(apiType.getMethod("getPlayerVersion",java.util.UUID::class.java).invoke(api,player.uniqueId) as Int)
        }.getOrDefault(false)
    }
}

/** Frontier-only all-time combat boards. */
internal class FrontierCombatBoards(private val plugin: JavaPlugin, private val scores: FrontierCombatLeaderboard,
    private val guilds: GuildService, private val config: net.lumalyte.lg.config.MainConfig) : Listener {
    private data class Row(val icon: ItemStack,val name: String,val score: Long)
    private fun scoreLabel(row: Row,vaults: Boolean): String = "${row.score} " +
        if(vaults) (if(row.score==1L) "vault destroyed" else "vaults destroyed")
        else (if(row.score==1L) "player kill" else "player kills")
    fun start() {
        plugin.server.pluginManager.registerEvents(this,plugin)
        plugin.getCommand("frontiertop")?.setExecutor { sender,_,_,_ ->
            if(sender is Player) open(sender,false) else sender.sendMessage("This command requires a player.")
            true
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun death(event: PlayerDeathEvent) {
        val killer=event.entity.killer ?: return
        try { scores.kill(killer.uniqueId,event.entity.uniqueId) }
        catch(failure: Exception) { plugin.logger.log(java.util.logging.Level.SEVERE,"Could not record player kill leaderboard score",failure) }
    }
    private fun rows(vaults: Boolean): List<Row> = if(vaults) scores.vaults().mapNotNull { (id,score) ->
        guilds.getGuild(id)?.let { Row(GuildBannerItemResolver.resolveForDisplay(it),it.name,score) }
    }.take(10) else scores.kills().take(10).map { (id,score) ->
        val profile=Bukkit.getOfflinePlayer(id)
        val head=ItemStack(Material.PLAYER_HEAD)
        val meta=head.itemMeta as SkullMeta;meta.owningPlayer=profile;head.itemMeta=meta
        Row(head,profile.name ?: "Unknown player",score)
    }
    private fun open(player: Player,vaults: Boolean) {
        val rows=rows(vaults);val title=if(vaults) "Vaults Destroyed" else "Player Kills"
        if(!FrontierDialogCapability.supported(player)) { inventory(player,vaults,rows);return }
        val dialog=GuildMenuDialog()
        val cards=rows.mapIndexed { index,row ->
            val lines=listOf(Component.text("#${index+1}  ${row.name}",FrontierTheme.gold),Component.text(scoreLabel(row,vaults),FrontierTheme.cream))
            GuildDialogItem(row.icon,Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(),lines),lines,descriptionWidth=128)
        }
        val toggle=GuildDialogAction(Component.text(if(vaults) "Player Kills" else "Vaults Destroyed"),Component.empty()) { open(player,!vaults) }
        val back=GuildDialogAction(Component.text("Guild Menu"),Component.empty()) { player.closeDialog();FrontierGuildMenu.open(player,config) }
        dialog.show(player,GuildDialogPage(Component.text("Frontier | $title"),cards,listOf(toggle),back,
            subtitle=Component.text(if(rows.isEmpty()) "No recorded scores yet" else "All-time · Top ${rows.size}")),
            { player.isOnline },{ inventory(player,vaults,rows) })
    }
    private fun inventory(player: Player,vaults: Boolean,rows: List<Row>) {
        val gui=ChestGui(3,"Frontier | ${if(vaults) "Vaults" else "Kills"}")
        val pane=StaticPane(0,0,9,3);gui.addPane(pane);gui.setOnGlobalClick { it.isCancelled=true };gui.setOnGlobalDrag { it.isCancelled=true }
        rows.forEachIndexed { index,row ->
            val item=row.icon.clone();item.editMeta { it.displayName(FrontierTheme.heading("#${index+1} ${row.name}"));it.lore(listOf(Component.text(scoreLabel(row,vaults),FrontierTheme.cream))) }
            pane.addItem(GuiItem(item),index%9,index/9)
        }
        if(rows.isEmpty()) {
            val empty=ItemStack(Material.PAPER);empty.editMeta { it.displayName(FrontierTheme.heading("No recorded scores yet")) }
            pane.addItem(GuiItem(empty),4,0)
        }
        val button=ItemStack(if(vaults) Material.IRON_SWORD else Material.WHITE_BANNER)
        button.editMeta { it.displayName(FrontierTheme.heading(if(vaults) "Player Kills" else "Vaults Destroyed")) }
        pane.addItem(GuiItem(button) { open(player,!vaults) },4,2)
        val back=ItemStack(Material.ARROW);back.editMeta { it.displayName(FrontierTheme.heading("Guild Menu")) }
        pane.addItem(GuiItem(back) { FrontierGuildMenu.open(player,config) },0,2)
        val close=ItemStack(Material.BARRIER);close.editMeta { it.displayName(FrontierTheme.heading("Close")) }
        pane.addItem(GuiItem(close) { player.closeInventory() },8,2)
        gui.show(player)
    }
}
