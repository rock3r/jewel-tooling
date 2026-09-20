package dev.sebastiano.jewel.tooling

import dev.sebastiano.jewel.tooling.mcp.bootstrap.Discovery
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import java.util.UUID

internal object McpConfigFiles {
    const val LIMIT = 1024 * 1024

    fun read(path: Path, limit: Int = LIMIT): ByteArray? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        owned(path, false)
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }
        if (bytes.size > limit) throw McpSetupFailure("tooLarge")
        return bytes
    }

    fun text(bytes: ByteArray?): String =
        bytes
            ?.let {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(it))
                    .toString()
            }
            .orEmpty()

    fun directory(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            owned(path, true)
            return
        }
        directory(path.parent ?: throw McpSetupFailure("unsafePath"))
        val attributes = Files.getFileAttributeView(path.parent, PosixFileAttributeView::class.java)
        if (attributes != null)
            Files.createDirectory(
                path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        else {
            Files.createDirectory(path)
            secureAcl(path)
        }
    }

    fun owned(path: Path, directory: Boolean) {
        if (
            Files.isSymbolicLink(path) ||
                (if (directory) !Files.isDirectory(path, NOFOLLOW_LINKS)
                else !Files.isRegularFile(path, NOFOLLOW_LINKS))
        ) {
            throw McpSetupFailure("unsafePath")
        }
        val owner =
            path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(
                System.getProperty("user.name")
            )
        if (Files.getOwner(path, NOFOLLOW_LINKS) != owner) throw McpSetupFailure("unsafePath")
    }

    fun privateWrite(path: Path, bytes: ByteArray) {
        val temporary = path.resolveSibling(".jewel-${UUID.randomUUID()}.tmp")
        privateFile(temporary)
        try {
            Files.write(temporary, bytes, WRITE, NOFOLLOW_LINKS)
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun privateFile(path: Path) {
        if (Files.getFileAttributeView(path.parent, PosixFileAttributeView::class.java) != null) {
            Files.newByteChannel(
                    path,
                    setOf(CREATE_NEW, WRITE),
                    PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")
                    ),
                )
                .close()
        } else {
            Files.createFile(path)
            secureAcl(path)
        }
    }

    private fun secureAcl(path: Path) {
        val view =
            Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
                ?: throw McpSetupFailure("permissions")
        view.acl =
            listOf(
                AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path, NOFOLLOW_LINKS))
                    .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                    .build()
            )
    }
}

internal class McpConfigUpdate(
    val path: Path,
    private val privateRoot: Path,
    private val limit: Int = McpConfigFiles.LIMIT,
) : AutoCloseable {
    private val channel: FileChannel
    private val lock: FileLock
    val before: ByteArray?
    val text: String

    init {
        McpConfigFiles.directory(path.parent)
        val key = Discovery.digest(path.toAbsolutePath().normalize().toString().toByteArray())
        val lockPath = path.resolveSibling(".jewel-tooling-${key.take(16)}.lock")
        if (!Files.exists(lockPath, NOFOLLOW_LINKS)) {
            try {
                McpConfigFiles.privateFile(lockPath)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                /* Another IDE created the shared lock. */
            }
        }
        Discovery.checkPrivate(lockPath, false)
        channel = FileChannel.open(lockPath, WRITE, NOFOLLOW_LINKS)
        var initialized = false
        try {
            lock = acquireLock(channel)
            before = McpConfigFiles.read(path, limit)
            text = McpConfigFiles.text(before)
            initialized = true
        } finally {
            if (!initialized) channel.close()
        }
    }

    fun commit(candidate: String): Boolean {
        val bytes = candidate.toByteArray()
        if (bytes.size > limit) throw McpSetupFailure("tooLarge")
        if (bytes.contentEquals(before)) return false
        verifyContents(before)
        if (before != null) {
            McpConfigFiles.directory(privateRoot)
            val backup =
                privateRoot.resolve(Discovery.digest(path.toString().toByteArray()) + ".backup")
            if (Files.exists(backup, NOFOLLOW_LINKS)) Discovery.checkPrivate(backup, false)
            McpConfigFiles.privateWrite(backup, before)
        }
        verifyContents(before)
        McpConfigFiles.privateWrite(path, bytes)
        verifyContents(bytes)
        return true
    }

    private fun verifyContents(expected: ByteArray?) {
        if (!expected.contentEquals(McpConfigFiles.read(path, limit)))
            throw McpSetupFailure("changed")
    }

    private fun acquireLock(channel: FileChannel): FileLock =
        try {
            channel.tryLock() ?: throw McpSetupFailure("busy")
        } catch (_: OverlappingFileLockException) {
            throw McpSetupFailure("busy")
        }

    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }
}
