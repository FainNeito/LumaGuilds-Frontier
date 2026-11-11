package net.lumalyte.lg.infrastructure.services

import github.scarsz.discordsrv.DiscordSRV
import java.util.UUID

/** Optional DiscordSRV adapter. No bot credentials, webhooks, or global announcements. */
object FrontierDiscordDelivery {
    fun send(player: UUID, text: String) {
        if (!DiscordSRV.isReady) return
        val provider = DiscordSRV.getPlugin()
        val discordId = provider.accountLinkManager.getDiscordId(player) ?: return
        provider.jda.retrieveUserById(discordId).submit()
            .thenCompose { it.openPrivateChannel().submit() }
            .thenCompose { channel ->
                // An unlink while opening the DM invalidates the pending notification.
                if (provider.accountLinkManager.getDiscordId(player) != discordId)
                    java.util.concurrent.CompletableFuture.completedFuture(null)
                else channel.sendMessage(text.take(1900)).allowedMentions(emptyList()).submit()
            }.exceptionally { null }
    }
}
