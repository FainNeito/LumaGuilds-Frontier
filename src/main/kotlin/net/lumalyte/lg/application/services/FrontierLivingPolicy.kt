package net.lumalyte.lg.application.services

/** Clock values are epoch milliseconds; online defenders always suspend expiry. */
object FrontierLivingPolicy {
    fun expired(lastActivity: Long?, initializedAt: Long, now: Long, timeout: Long, online: Boolean): Boolean {
        val baseline = maxOf(lastActivity ?: initializedAt, initializedAt)
        return !online && timeout > 0 && now >= baseline && now - baseline >= timeout
    }

    fun capDamage(damage: Double, cap: Double): Double =
        if (cap.isFinite() && cap > 0) minOf(damage, cap) else damage

    fun nearby(sameWorld: Boolean, distanceSquared: Double, radius: Double): Boolean =
        sameWorld && radius.isFinite() && radius >= 0 && distanceSquared >= 0 && distanceSquared <= radius * radius
}
