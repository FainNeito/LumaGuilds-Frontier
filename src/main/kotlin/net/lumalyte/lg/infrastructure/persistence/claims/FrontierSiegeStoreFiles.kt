package net.lumalyte.lg.infrastructure.persistence.claims

import net.lumalyte.lg.application.services.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Properties
import java.util.UUID

class FrontierSiegeStoreFiles(private val directory: Path) : FrontierSiegeStore {
    private fun path(owner: FrontierOwner) = directory.resolve("${owner.type}-${owner.id}.siege")

    override fun get(owner: FrontierOwner): FrontierSiegeRecord? =
        path(owner).takeIf(Files::exists)?.let(::read)

    override fun all(): List<FrontierSiegeRecord> {
        if (!Files.exists(directory)) return emptyList()
        return Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".siege") }.map(::read).toList()
        }
    }

    override fun save(record: FrontierSiegeRecord) {
        Files.createDirectories(directory)
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("ownerType", record.owner.type.name)
            setProperty("ownerId", record.owner.id.toString())
            setProperty("objective", record.objective)
            setProperty("windowEnd", record.windowEnd.toString())
            setProperty("hits", record.hits.toString())
            setProperty("lastHit", record.lastHit.toString())
            record.completedAt?.let { setProperty("completedAt", it.toString()) }
            record.release?.let {
                setProperty("releaseId", it.id.toString())
                setProperty("claims", it.claimIds.joinToString(","))
            }
        }
        val bytes = ByteArrayOutputStream().use { output -> properties.store(output, "Frontier siege"); output.toByteArray() }
        val temporary = Files.createTempFile(directory, "siege-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, path(record.owner), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun read(file: Path): FrontierSiegeRecord {
        val p = Properties().apply { Files.newInputStream(file).use { load(it) } }
        check(p.getProperty("version") == "1") { "Unsupported siege record: $file" }
        val owner = FrontierOwner(FrontierOwnerType.valueOf(p.getProperty("ownerType")), UUID.fromString(p.getProperty("ownerId")))
        check(file.fileName == path(owner).fileName) { "Mismatched siege owner: $file" }
        val release = p.getProperty("releaseId")?.let {
            FrontierReleasePlan(UUID.fromString(it), owner,
                p.getProperty("claims").split(',').filter(String::isNotEmpty).map(UUID::fromString).toSet())
        }
        val hits = p.getProperty("hits").toInt()
        require(hits > 0)
        val completed = p.getProperty("completedAt")?.let(Instant::parse)
        check(completed == null || release != null)
        return FrontierSiegeRecord(owner, p.getProperty("objective"), Instant.parse(p.getProperty("windowEnd")),
            hits, Instant.parse(p.getProperty("lastHit")), release, completed)
    }
}
