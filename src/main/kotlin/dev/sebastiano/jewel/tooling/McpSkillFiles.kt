package dev.sebastiano.jewel.tooling

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal object McpSkillFiles {
    const val RECEIPT = ".jewel-tooling-skill.json"
    const val RECEIPT_LIMIT = 16384

    fun destination(raw: Path, os: String = System.getProperty("os.name")): Path {
        validateName(raw)
        val path =
            if (
                os.startsWith("Mac") &&
                    raw.nameCount > 0 &&
                    raw.getName(0).toString() in setOf("var", "tmp")
            )
                Path.of("/private").resolve(raw.subpath(0, raw.nameCount))
            else raw
        rejectLinks(path)
        validateAnchor(path)

        return path
    }

    private fun validateName(raw: Path) {
        if (!raw.isAbsolute || raw != raw.normalize()) throw McpSetupFailure("unsafePath")
        if (
            raw.fileName.toString() != "SKILL.md" ||
                raw.parent?.fileName?.toString() != McpSkillBundle.ID
        )
            throw McpSetupFailure("unsafePath")
    }

    private fun rejectLinks(path: Path) {
        var current = path.root
        for (component in path) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) throw McpSetupFailure("unsafePath")
        }
    }

    private fun validateAnchor(path: Path) {
        var anchor = path.parent
        while (!Files.exists(anchor, NOFOLLOW_LINKS)) anchor =
            anchor.parent ?: throw McpSetupFailure("unsafePath")
        if (anchor == anchor.root || anchor == Path.of("/private/tmp") || anchor == Path.of("/tmp"))
            throw McpSetupFailure("unsafePath")
        McpConfigFiles.owned(anchor, true)
    }
}

internal data class McpSkillGeneration(val version: McpSkillVersion, val digest: String) {
    fun json() =
        JsonObject().apply {
            addProperty("version", version.text)
            addProperty("digest", digest)
        }
}

internal data class McpSkillReceipt(
    val installed: McpSkillGeneration?,
    val pending: McpSkillGeneration?,
) {
    fun text(): String =
        JsonObject()
            .apply {
                addProperty("schema", 1)
                addProperty("skillId", McpSkillBundle.ID)
                add("installed", installed?.json() ?: JsonNull.INSTANCE)
                add("pending", pending?.json() ?: JsonNull.INSTANCE)
            }
            .toString()

    companion object {
        fun parse(bytes: ByteArray?): McpSkillReceipt? {
            if (bytes == null) return null
            val json = McpJsonConfig(McpConfigFiles.text(bytes)).objectValue()
            require(json.keySet() == setOf("schema", "skillId", "installed", "pending"))
            require(
                json["schema"].toString() == "1" && json["skillId"].asString == McpSkillBundle.ID
            )
            val installed = generation(json, "installed")
            val pending = generation(json, "pending")
            require(installed != null || pending != null)
            return McpSkillReceipt(installed, pending)
        }

        private fun generation(json: JsonObject, name: String): McpSkillGeneration? {
            if (json[name].isJsonNull) return null
            val value = json[name].asJsonObject
            require(value.keySet() == setOf("version", "digest"))
            val digest = value["digest"].asString
            require(Regex("[a-f0-9]{64}").matches(digest))
            return McpSkillGeneration(McpSkillVersion(value["version"].asString), digest)
        }
    }
}
