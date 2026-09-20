package dev.sebastiano.jewel.tooling

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.InvalidVirtualFileAccessException
import com.intellij.openapi.vfs.VirtualFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Owned by one function inspection; no VFS, PSI, or byte arrays survive that inspection. */
internal class BinaryMetadataReader {
    private data class Key(
        val file: VirtualFile,
        val stamp: Long,
        val name: String,
        val parameters: Int,
    )

    private val results = mutableMapOf<Key, CompilerStabilityMetadata.Result>()
    private var bytesRemaining = TOTAL_BYTE_LIMIT

    fun read(
        file: VirtualFile,
        internalName: String,
        typeParameters: Int,
    ): CompilerStabilityMetadata.Result {
        ProgressManager.checkCanceled()
        if (ApplicationManager.getApplication().isDispatchThread)
            return CompilerStabilityMetadata.Result.Unsupported
        return try {
            readValidFile(file, internalName, typeParameters)
        } catch (_: IOException) {
            CompilerStabilityMetadata.Result.Unsupported
        } catch (_: InvalidVirtualFileAccessException) {
            CompilerStabilityMetadata.Result.Unsupported
        }
    }

    private fun readValidFile(
        file: VirtualFile,
        name: String,
        parameters: Int,
    ): CompilerStabilityMetadata.Result {
        if (!file.isValid) return CompilerStabilityMetadata.Result.Unsupported
        val key = Key(file, file.modificationStamp, name, parameters)
        return results[key]
            ?: if (results.size >= CLASS_LIMIT || bytesRemaining <= 0) {
                CompilerStabilityMetadata.Result.Unsupported
            } else {
                // Failed reads count toward the same per-inspection class budget.
                results[key] = CompilerStabilityMetadata.Result.Unsupported
                decodeFile(file, name, parameters).also { results[key] = it }
            }
    }

    private fun decodeFile(
        file: VirtualFile,
        name: String,
        parameters: Int,
    ): CompilerStabilityMetadata.Result {
        if (file.length > CompilerStabilityMetadata.MAX_BYTES || file.length > bytesRemaining)
            return CompilerStabilityMetadata.Result.Unsupported
        val bytes = file.inputStream.use { readBytes(it) }
        ProgressManager.checkCanceled()
        return if (bytes == null) CompilerStabilityMetadata.Result.Unsupported
        else CompilerStabilityMetadata.decode(bytes, name, parameters)
    }

    private fun readBytes(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        while (true) {
            ProgressManager.checkCanceled()
            val allowed =
                minOf(
                    buffer.size,
                    CompilerStabilityMetadata.MAX_BYTES - output.size() + 1,
                    bytesRemaining + 1,
                )
            val count = input.read(buffer, 0, allowed)
            if (count < 0) return output.toByteArray()
            bytesRemaining -= count
            if (
                count == 0 ||
                    bytesRemaining < 0 ||
                    output.size() + count > CompilerStabilityMetadata.MAX_BYTES
            )
                return null
            output.write(buffer, 0, count)
        }
    }

    private companion object {
        const val CLASS_LIMIT = 32
        const val TOTAL_BYTE_LIMIT = 4 * CompilerStabilityMetadata.MAX_BYTES
        const val READ_CHUNK = 8192
    }
}
