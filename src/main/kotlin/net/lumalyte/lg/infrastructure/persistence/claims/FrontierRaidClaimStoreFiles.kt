package net.lumalyte.lg.infrastructure.persistence.claims

import net.lumalyte.lg.application.services.FrontierRaidClaim
import net.lumalyte.lg.application.services.FrontierRaidClaimStore
import net.lumalyte.lg.domain.values.FrontierChunk
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.UUID

/** Per-attacker atomic records survive a crash without manufacturing permanent territory. */
class FrontierRaidClaimStoreFiles(private val directory: Path) : FrontierRaidClaimStore {
    private fun path(attacker: UUID) = directory.resolve("$attacker.raidclaim")

    override fun all(): List<FrontierRaidClaim> {
        if (!Files.exists(directory)) return emptyList()
        return Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".raidclaim") }.map(::read).toList()
        }
    }

    override fun save(claim: FrontierRaidClaim) {
        Files.createDirectories(directory)
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("attacker", claim.attacker.toString())
            setProperty("defender", claim.defender.toString())
            setProperty("world", claim.chunk.worldId.toString())
            setProperty("x", claim.chunk.x.toString())
            setProperty("z", claim.chunk.z.toString())
            setProperty("created", claim.createdAt.toString())
            setProperty("windowEnd", claim.windowEnd.toString())
            setProperty("graceEnd", claim.graceEnd.toString())
            claim.bannerId?.let { setProperty("banner", it.toString()) }
            claim.bannerEnd?.let { setProperty("bannerEnd", it.toString()) }
        }
        val bytes = ByteArrayOutputStream().use { output -> properties.store(output, "Frontier raid claim"); output.toByteArray() }
        val temp = Files.createTempFile(directory, "raidclaim-", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, path(claim.attacker), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun delete(attacker: UUID) { Files.deleteIfExists(path(attacker)) }

    private fun read(file: Path): FrontierRaidClaim {
        val props = Properties().apply { Files.newInputStream(file).use { load(it) } }
        require(props.getProperty("version") == "1") { "Unsupported raid claim $file" }
        val attacker = UUID.fromString(props.getProperty("attacker"))
        require(file.fileName == path(attacker).fileName) { "Mismatched raid claim owner $file" }
        val banner = props.getProperty("banner")?.let(UUID::fromString)
        val end = props.getProperty("bannerEnd")?.toLong()
        require((banner == null) == (end == null)) { "Partially bound raid banner $file" }
        val created = props.getProperty("created").toLong()
        val windowEnd = props.getProperty("windowEnd").toLong()
        val graceEnd = props.getProperty("graceEnd").toLong()
        require(created < graceEnd && graceEnd <= windowEnd && (end == null || end <= windowEnd))
        return FrontierRaidClaim(attacker, UUID.fromString(props.getProperty("defender")),
            FrontierChunk(UUID.fromString(props.getProperty("world")), props.getProperty("x").toInt(), props.getProperty("z").toInt()),
            created, windowEnd, graceEnd, banner, end)
    }
}
