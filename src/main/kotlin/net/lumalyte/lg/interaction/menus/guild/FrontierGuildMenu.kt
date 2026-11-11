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

/** A vanilla inventory with no SMP dashboard links or resource-pack dependency. */
object FrontierGuildMenu {
    fun open(player: Player, config: MainConfig) {
        val gui = ChestGui(5, "§6Frontier §8| §6Guild & Claims")
        val pane = StaticPane(0, 0, 9, 5)
        gui.addPane(pane)
        gui.setOnGlobalClick { it.isCancelled = true }
        gui.setOnGlobalDrag { it.isCancelled = true }
        for (x in 0..8) {
            val trim = ItemStack(if (x == 0 || x == 8) Material.RED_STAINED_GLASS_PANE else Material.YELLOW_STAINED_GLASS_PANE)
            trim.editMeta { it.displayName(Component.text(" ")) }
            pane.addItem(GuiItem(trim) { it.isCancelled = true }, x, 0)
        }
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
        button(1, 1, Material.WHITE_BANNER, "Guild information", listOf("View your guild and members.")) { command("guild info") }
        button(2, 2, Material.CHEST, "Guild vault", listOf("54 storage slots, available immediately.", "Use /claim to establish your Founding Vault.", "Expand into connected neighboring chunks.", "Visit the chest to access its contents.")) { command("guild getvault") }
        button(6, 2, Material.MAP, "Territory & raids", listOf("Personal: ${config.personalChunkLimit} chunks | Guild: ${config.guildChunkLimit} chunks", "Use /claim [name] to preview a chunk.", "One territory name across every chunk.", "Your Founding Vault protects all your land.")) { command("raidinfo") }
        button(4, 1, Material.WRITABLE_BOOK, "Guild chat", listOf("Toggle private guild conversation.")) { command("guild chat") }
        button(7, 1, Material.BOOK, "Guild commands", listOf("Clickable command suggestions.")) {
            listOf("create <name>", "invite <player>", "join <guild>", "invites", "kick <player>", "tag <tag>", "description <text>", "rename <name>", "leave", "disband").forEach { syntax ->
                val base = syntax.substringBefore(' ')
                player.sendMessage(FrontierTheme.message("Guild", "/guild $syntax")
                    .clickEvent(ClickEvent.suggestCommand("/guild $base ")))
            }
        }
        button(2, 3, Material.ENDER_EYE, "Show claim borders", listOf("View your nearby solo and guild claims.", "Gold particles remain visible for 30 seconds.")) { command("claimoutline") }
        button(6, 3, Material.CHEST, "Pick up vault", listOf("Look at your vault and select this.", "Relocate outside the raid window.", "WARNING: packing UNCLAIMS ALL LAND.", "Stored items stay; no raid cooldown.")) { command("vaultpickup") }
        button(3, 2, Material.RED_BED, "Guild home", listOf("One shared destination inside guild land.", "Set it with /guild sethome.", "5-second warmup; unavailable during raids.")) { command("guildhome") }
        button(5, 2, Material.WHITE_BED, "Personal home", listOf("One destination inside your solo claims.", "Set it with /sethome.", "Movement or damage cancels teleporting.")) { command("home") }
        button(1, 4, Material.GRASS_BLOCK, "Walking autoclaim", listOf("Review and confirm /autoclaim.", "Claim connected chunks as you walk.", "Use /autoclaim off to stop.")) { command("autoclaim") }
        button(3, 4, Material.SHEARS, "Unclaim this chunk", listOf("Preview removing your current chunk.", "Confirm with /unclaim confirm.", "Cannot remove a bridge or Founding chunk.")) { command("unclaim") }
        button(5, 4, Material.RED_BANNER, "War banner", listOf("Raid window only. One use per guild per 24h.", "Lasts up to 15 minutes; 20 damage points.", "Guild members: /guild warbanner tp")) { command("guild warbanner") }
        button(7, 4, Material.BARRIER, "Close", emptyList()) {}
        gui.show(player)
    }
}
