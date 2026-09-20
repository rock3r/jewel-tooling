package dev.sebastiano.jewel.tooling.recording

import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InspectionFilesTest {
    @Test
    fun privateRoundTripRejectsReplacement() {
        val parent = Files.createTempDirectory("inspection-files-test")
        val directory = InspectionFiles.createDirectory(parent)
        try {
            val values =
                mapOf("nonce" to InspectionFiles.nonce(), "name" to "A project with spaces")
            val path = directory.resolve(InspectionFiles.CONFIG)
            InspectionFiles.write(path, values)
            assertEquals(values, InspectionFiles.read(path))
            assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
                InspectionFiles.write(path, values)
            }
        } finally {
            InspectionFiles.cleanup(directory)
            Files.delete(parent)
        }
    }

    @Test
    fun rejectsSymlinkAndOversizedCapability() {
        val parent = Files.createTempDirectory("inspection-files-test")
        val directory = InspectionFiles.createDirectory(parent)
        val target = directory.resolve(InspectionFiles.CONFIG)
        val link = directory.resolve(InspectionFiles.READY)
        try {
            InspectionFiles.write(target, mapOf("nonce" to InspectionFiles.nonce()))
            Files.createSymbolicLink(link, target)
            assertThrows(IOException::class.java) { InspectionFiles.read(link) }
            Files.delete(link)
            Files.write(target, ByteArray(InspectionFiles.MAX_BYTES + 1))
            assertThrows(IOException::class.java) { InspectionFiles.read(target) }
        } finally {
            InspectionFiles.cleanup(directory)
            Files.delete(parent)
        }
    }

    @Test
    fun rejectsWorldReadableFile() {
        val parent = Files.createTempDirectory("inspection-files-test")
        val directory = InspectionFiles.createDirectory(parent)
        try {
            val path = directory.resolve(InspectionFiles.CONFIG)
            InspectionFiles.write(path, mapOf("nonce" to InspectionFiles.nonce()))
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
                assertThrows(IOException::class.java) { InspectionFiles.read(path) }
            }
        } finally {
            InspectionFiles.cleanup(directory)
            Files.delete(parent)
        }
    }
}
