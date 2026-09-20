package dev.sebastiano.jewel.tooling

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import java.io.File

internal fun kotlinStdlibJar(parent: Disposable): File {
    val source = File(System.getProperty("jewel.tooling.stdlib"))
    val copy = FileUtil.createTempFile("kotlin-stdlib", ".jar", true)
    Disposer.register(parent) { FileUtil.delete(copy) }
    copy.writeBytes(source.readBytes())
    return copy
}
