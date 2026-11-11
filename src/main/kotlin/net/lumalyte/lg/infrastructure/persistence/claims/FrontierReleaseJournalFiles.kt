package net.lumalyte.lg.infrastructure.persistence.claims

import net.lumalyte.lg.application.services.FrontierOwner
import net.lumalyte.lg.application.services.FrontierOwnerType
import net.lumalyte.lg.application.services.FrontierReleaseJournal
import net.lumalyte.lg.application.services.FrontierReleasePlan
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.WRITE
import java.util.Properties
import java.util.UUID
import java.io.ByteArrayOutputStream

/** One immutable pending record per release. An unsupported atomic move fails before deletion. */
class FrontierReleaseJournalFiles(private val directory: Path) : FrontierReleaseJournal {
    override fun save(plan: FrontierReleasePlan) {
        Files.createDirectories(directory)
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("id", plan.id.toString())
            setProperty("ownerType", plan.owner.type.name)
            setProperty("ownerId", plan.owner.id.toString())
            setProperty("claims", plan.claimIds.joinToString(","))
        }
        val bytes = ByteArrayOutputStream().use { output ->
            properties.store(output, "Pending Frontier claim release")
            output.toByteArray()
        }
        val temporary = Files.createTempFile(directory, "release-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, path(plan.id), ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun pending(): List<FrontierReleasePlan> {
        if (!Files.exists(directory)) return emptyList()
        return Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".release") }.map { file ->
                val properties = Properties().apply { Files.newInputStream(file).use { load(it) } }
                check(properties.getProperty("version") == "1") { "Unsupported release record: $file" }
                val id = UUID.fromString(properties.getProperty("id"))
                check(file.fileName.toString() == "$id.release") { "Mismatched release record: $file" }
                FrontierReleasePlan(id,
                    FrontierOwner(FrontierOwnerType.valueOf(properties.getProperty("ownerType")),
                        UUID.fromString(properties.getProperty("ownerId"))),
                    properties.getProperty("claims").split(',').filter { it.isNotEmpty() }
                        .map(UUID::fromString).toSet())
            }.toList()
        }
    }

    override fun complete(id: UUID) {
        Files.deleteIfExists(path(id))
    }

    private fun path(id: UUID): Path = directory.resolve("$id.release")
}
