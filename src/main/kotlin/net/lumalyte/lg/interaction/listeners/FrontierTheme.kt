package net.lumalyte.lg.interaction.listeners

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/** Frontier-only presentation. Never parses player input as MiniMessage. */
object FrontierTheme {
    @Volatile var enabled: Boolean = false
    val crimson = TextColor.color(0xC0261D)
    val gold = TextColor.color(0xD6B56C)
    val cream = TextColor.color(0xEFE5D0)
    val copper = TextColor.color(0xC9926C)
    val rose = TextColor.color(0xD5A09A)
    val muted = TextColor.color(0xB5ACA0)
    private val palette = mapOf(
        0xFF5555 to crimson, 0xAA0000 to TextColor.color(0xA6221A),
        0xFFAA00 to gold, 0xFFFF55 to TextColor.color(0xE2C68A),
        0x55FF55 to gold, 0x00AA00 to TextColor.color(0xBA9B59),
        0x55FFFF to rose, 0x00AAAA to rose, 0x5555FF to copper,
        0x0000AA to copper, 0xFF55FF to rose, 0xAA00AA to rose,
        0xFFFFFF to cream, 0xAAAAAA to muted, 0x555555 to TextColor.color(0x81796D),
    )
    fun style(component: Component): Component = component
        .color(component.color()?.let { palette[it.value()] ?: it })
        .children(component.children().map(::style))
    fun present(component: Component): Component = if (enabled) style(component).colorIfAbsent(cream) else component
    fun heading(text: String, danger: Boolean = false): Component {
        val from = if (danger) crimson else gold
        val to = if (danger) TextColor.color(0xD13B30) else TextColor.color(0xE2C68A)
        var result = Component.empty()
        text.forEachIndexed { index, char ->
            val ratio = index.toFloat() / (text.length - 1).coerceAtLeast(1)
            result = result.append(Component.text(char, TextColor.lerp(ratio, from, to)))
        }
        return result
    }
    fun message(label: String, body: String, danger: Boolean = false): Component =
        heading("[$label]", danger).append(Component.text(" $body", cream))
    enum class Cue { CLICK, SUCCESS, DENIED, HIT, FALLEN }
    fun cue(player: Player, cue: Cue) {
        when (cue) {
            Cue.CLICK -> player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HARP, 0.25f, 1.2f)
            Cue.SUCCESS -> {
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HARP, 0.35f, 1.2f)
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.25f, 1.5f)
            }
            Cue.DENIED -> player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.25f, 0.8f)
            Cue.HIT -> player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.3f, 0.8f)
            Cue.FALLEN -> {
                player.playSound(player.location, Sound.BLOCK_BELL_USE, 0.65f, 0.6f)
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.4f, 0.5f)
            }
        }
    }
    fun duration(seconds: Long): String = if (seconds >= 3600)
        "%dh %02dm %02ds".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%dm %02ds".format(seconds / 60, seconds % 60)
    fun alarm(plugin: JavaPlugin, player: Player) {
        player.playSound(player.location, Sound.BLOCK_BELL_USE, 0.65f, 0.9f)
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.35f, 0.65f)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline) player.playSound(player.location, Sound.BLOCK_BELL_USE, 0.5f, 0.8f)
        }, 4L)
    }
}

fun Player.sendFrontierMessage(component: Component) = sendMessage(FrontierTheme.present(component))
fun Player.sendFrontierMessage(text: String) {
    if (FrontierTheme.enabled) sendMessage(FrontierTheme.present(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(text)))
    else sendMessage(text)
}

/** Legacy command feedback retains its original sound on SMP. */
fun Player.playFrontierSound(location: org.bukkit.Location, sound: Sound, volume: Float, pitch: Float) {
    if (!FrontierTheme.enabled) { playSound(location, sound, volume, pitch); return }
    val key = sound.key.key
    val cue = when {
        key.contains("villager.no") || key.contains("break") || key.contains("growl") -> FrontierTheme.Cue.DENIED
        key.contains("levelup") || key.contains("pickup") -> FrontierTheme.Cue.SUCCESS
        else -> FrontierTheme.Cue.CLICK
    }
    FrontierTheme.cue(this, cue)
}
