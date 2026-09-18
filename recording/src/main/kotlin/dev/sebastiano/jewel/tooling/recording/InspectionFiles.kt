package dev.sebastiano.jewel.tooling.recording

import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.EnumSet
import java.util.Properties
import org.jetbrains.annotations.ApiStatus

/** Stores one launch's capabilities in an owner-only directory. */
@ApiStatus.Experimental
object InspectionFiles {
  const val CONFIG = "launch.properties"
  const val READY = "ready.properties"
  const val TASK_STARTED = "task-started.properties"
  const val MAX_BYTES = 8192
  private const val NONCE_BYTES = 32
  private const val MAX_PROPERTIES = 16
  private val directoryMode = PosixFilePermissions.fromString("rwx------")
  private val fileMode = PosixFilePermissions.fromString("rw-------")

  fun nonce(): String =
    ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

  fun createDirectory(parent: Path): Path {
    Files.createDirectories(parent)
    require(!Files.isSymbolicLink(parent))
    val posix =
      Files.getFileAttributeView(parent, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
    val result =
      if (posix != null) {
        Files.createTempDirectory(
          parent,
          "launch-",
          PosixFilePermissions.asFileAttribute(directoryMode),
        )
      } else {
        Files.createTempDirectory(parent, "launch-").also(::restrict)
      }
    verify(result, directory = true)
    return result
  }

  fun restrict(path: Path) {
    require(!Files.isSymbolicLink(path))
    val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
    if (posix != null) {
      posix.setPermissions(if (Files.isDirectory(path, NOFOLLOW_LINKS)) directoryMode else fileMode)
    } else {
      val acl =
        Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
          ?: throw IOException("Private file permissions are unavailable")
      val owner =
        path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(
          System.getProperty("user.name")
        )
      if (acl.owner != owner) throw IOException("Unexpected file owner")
      acl.acl =
        listOf(
          AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
            .build()
        )
    }
    verify(path, Files.isDirectory(path, NOFOLLOW_LINKS))
  }

  @Suppress("ThrowsCount") // Reject unsafe ownership and file modes before reading.
  fun verify(path: Path, directory: Boolean = false) {
    if (
      Files.isSymbolicLink(path) ||
        !(if (directory) Files.isDirectory(path, NOFOLLOW_LINKS)
        else Files.isRegularFile(path, NOFOLLOW_LINKS))
    ) {
      throw IOException("Unexpected inspection file type")
    }
    val owner =
      path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(
        System.getProperty("user.name")
      )
    if (Files.getOwner(path, NOFOLLOW_LINKS) != owner)
      throw IOException("Unexpected inspection file owner")
    val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
    if (posix != null) {
      if (posix.readAttributes().permissions() != if (directory) directoryMode else fileMode)
        throw IOException("Inspection file permissions are not private")
    } else {
      val acl =
        Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
          ?: throw IOException("Private file permissions are unavailable")
      if (acl.acl.any { it.type() == AclEntryType.ALLOW && it.principal() != owner })
        throw IOException("Inspection file permissions are not private")
    }
  }

  fun write(path: Path, values: Map<String, String>) {
    verify(path.parent, directory = true)
    val properties =
      Properties().apply { values.forEach { (key, value) -> setProperty(key, value) } }
    val bytes =
      StringWriter().also { properties.store(it, null) }.toString().toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_BYTES)
    val posix =
      Files.getFileAttributeView(path.parent, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
    val attributes =
      if (posix != null) arrayOf(PosixFilePermissions.asFileAttribute(fileMode)) else emptyArray()
    val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
    if (Files.exists(path, NOFOLLOW_LINKS))
      throw java.nio.file.FileAlreadyExistsException(path.toString())
    Files.newByteChannel(
        temporary,
        setOf<java.nio.file.OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
        *attributes,
      )
      .use { channel ->
        if (posix == null) restrict(temporary)
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
      }
    Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    verify(path)
  }

  fun read(path: Path): Map<String, String> {
    verify(path.parent, directory = true)
    verify(path)
    val bytes =
      Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 1) }
    if (bytes.size > MAX_BYTES) throw IOException("Inspection file exceeds its limit")
    val properties = Properties().apply { load(StringReader(bytes.toString(Charsets.UTF_8))) }
    if (properties.size > MAX_PROPERTIES) throw IOException("Too many inspection properties")
    return properties.stringPropertyNames().associateWith(properties::getProperty)
  }

  fun cleanup(directory: Path) {
    verify(directory, directory = true)
    for (name in listOf(CONFIG, READY, TASK_STARTED, "launch.init.gradle")) {
      Files.deleteIfExists(directory.resolve(name))
      Files.deleteIfExists(directory.resolve("$name.tmp"))
    }
    Files.deleteIfExists(directory)
  }
}
