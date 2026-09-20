package dev.sebastiano.jewel.tooling

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import dev.sebastiano.jewel.tooling.recording.InspectionFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class InstalledInspection(val agent: Path, val bridge: Path)

@Service(Service.Level.APP)
internal class InspectionSupport
@JvmOverloads
constructor(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val lock = Mutex()

    suspend fun install(): InstalledInspection =
        withContext(ioDispatcher) {
            lock.withLock {
                val agent = asset("inspection-agent.jar")
                val bridge = asset("bridge.jar")
                val digest =
                    MessageDigest.getInstance("SHA-256")
                        .apply {
                            update(agent)
                            update(bridge)
                        }
                        .digest()
                        .joinToString("") { "%02x".format(Locale.ROOT, it) }
                val directory =
                    Path.of(PathManager.getSystemPath(), "jewel-tooling", "inspection", digest)
                Files.createDirectories(directory)
                InspectionFiles.restrict(directory)
                installFile(directory.resolve("inspection-agent.jar"), agent)
                installFile(directory.resolve("bridge.jar"), bridge)
                InstalledInspection(
                    directory.resolve("inspection-agent.jar"),
                    directory.resolve("bridge.jar"),
                )
            }
        }

    private fun asset(name: String): ByteArray {
        val bytes =
            javaClass.getResourceAsStream("/agent/$name")?.use {
                it.readNBytes(MAX_ASSET_BYTES + 1)
            } ?: throw IOException("Inspection support is missing from this plugin")
        if (bytes.size > MAX_ASSET_BYTES) throw IOException("Inspection support exceeds its limit")
        return bytes
    }

    private fun installFile(path: Path, expected: ByteArray) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            InspectionFiles.verify(path)
            if (
                Files.size(path) != expected.size.toLong() ||
                    !Files.newInputStream(path, NOFOLLOW_LINKS)
                        .use { it.readNBytes(MAX_ASSET_BYTES + 1) }
                        .contentEquals(expected)
            )
                throw IOException("Cached inspection support does not match this plugin")
            return
        }
        val temporary = Files.createTempFile(path.parent, "asset-", ".tmp")
        try {
            InspectionFiles.restrict(temporary)
            Files.write(temporary, expected)
            Files.move(temporary, path, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private const val MAX_ASSET_BYTES = 64 * 1024 * 1024
    }
}
