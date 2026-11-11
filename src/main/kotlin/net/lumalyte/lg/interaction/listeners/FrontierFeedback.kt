package net.lumalyte.lg.interaction.listeners

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.time.Duration
import java.util.UUID

/** Shared recipient throttling: crossing chunk edges or several raiders cannot stack alarms. */
internal class FrontierFeedbackGate {
    private val last = mutableMapOf<Pair<UUID, String>, Long>()
    private val importantUntil = mutableMapOf<UUID, Long>()
    fun allow(player: UUID, kind: String, cooldown: Long, important: Boolean, now: Long): Boolean {
        last.entries.removeIf { now - it.value > 300_000 }
        importantUntil.entries.removeIf { it.value <= now }
        if (!important && (importantUntil[player] ?: 0) > now) return false
        val key = player to kind
        if (last[key]?.let { now - it < cooldown } == true) return false
        last[key] = now
        if (important) importantUntil[player] = now + 4_000
        return true
    }
}

/** Frontier-only counterpart to SMP war lifecycle titles; player names remain literal text. */
object FrontierFeedback {
    private var settings = YamlConfiguration()
    private var gate = FrontierFeedbackGate()
    fun initialize(plugin: JavaPlugin) {
        val file = File(plugin.dataFolder, "frontier-feedback.yml")
        if (!file.exists()) plugin.saveResource("frontier-feedback.yml", false)
        settings = YamlConfiguration.loadConfiguration(file)
        gate = FrontierFeedbackGate()
    }

    fun show(player: Player, kind: String, heading: String, detail: String, danger: Boolean = false,
             title: Boolean = true, important: Boolean = false) {
        if (!player.isOnline || !settings.getBoolean("enabled", true)) return
        val path = "events.$kind"
        if (!settings.getBoolean("$path.enabled", true)) return
        val cooldown = settings.getLong("$path.cooldown-seconds", 5).coerceIn(0, 300) * 1000
        if (!gate.allow(player.uniqueId, kind, cooldown, important, System.currentTimeMillis())) return
        if (title && settings.getBoolean("titles", true)) player.showTitle(Title.title(
            FrontierTheme.heading(heading, danger).decorate(TextDecoration.BOLD),
            Component.text(detail, FrontierTheme.cream),
            Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(if (important) 3 else 2), Duration.ofMillis(750))))
        if (settings.getBoolean("sounds", true)) {
            val sound = settings.getString("$path.sound") ?: return
            player.playSound(player.location, sound,
                settings.getDouble("$path.volume", 0.5).coerceIn(0.0, 1.0).toFloat(),
                settings.getDouble("$path.pitch", 1.0).coerceIn(0.5, 2.0).toFloat())
        }
    }
}
