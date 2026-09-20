package dev.sebastiano.jewel.tooling

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.target.getEffectiveTargetName
import com.intellij.util.execution.ParametersListUtil
import dev.sebastiano.jewel.tooling.recording.InspectionFiles
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration

internal class GradleInspectionSupport : InspectionGradleSupport {
    override fun supports(configuration: RunConfiguration): Boolean =
        configuration is GradleRunConfiguration

    override fun prepare(
        configuration: RunConfiguration,
        agent: Path,
        directory: Path,
        nonce: String,
    ) {
        val config = configuration as GradleRunConfiguration
        require(config.getEffectiveTargetName(config.project) == null)
        val tasks = config.settings.taskNames
        require(tasks.isNotEmpty() && tasks.all { it.matches(Regex("[:A-Za-z0-9_.-]+")) })
        val script = directory.resolve("launch.init.gradle")
        Files.writeString(script, GradleInspectionScript.create(tasks, agent, directory, nonce))
        InspectionFiles.restrict(script)
        val arguments =
            ParametersListUtil.parse(config.settings.scriptParameters.orEmpty()).toMutableList()
        require(arguments.none { it == "--configuration-cache" || it == "--isolated-projects" })
        arguments += listOf("--no-configuration-cache", "--init-script", script.toString())
        config.settings.scriptParameters = ParametersListUtil.join(arguments)
    }
}

internal object GradleInspectionScript {
    fun create(tasks: List<String>, agent: Path, directory: Path, nonce: String): String {
        val selected = tasks.joinToString(", ", "[", "]") { quote(it) }
        val argument = quote("-javaagent:$agent=${directory.resolve(InspectionFiles.CONFIG)}")
        val marker = quote(directory.resolve(InspectionFiles.TASK_STARTED).toString())
        val markerContent = quote("version=1\nnonce=$nonce\n")
        val missing = quote(JewelToolingBundle.message("launch.gradle.task"))
        // Gradle applies --init-script to included plugin classpath builds as well. Skip graphs
        // that do not contain the selected task; those are not the application launch.
        return """
      import org.gradle.api.tasks.JavaExec
      import org.gradle.process.CommandLineArgumentProvider
      import java.nio.file.Files
      import java.nio.file.Path
      import java.nio.file.StandardCopyOption
      import java.nio.file.attribute.PosixFilePermissions

      gradle.taskGraph.whenReady { graph ->
        def requested = $selected
        def matches = { task, selector ->
          selector.startsWith(':') ? task.path == selector :
            (selector.contains(':') ? task.path == ':' + selector : task.name == selector)
        }
        def matching = graph.allTasks.findAll { task -> requested.any { matches(task, it) } }
        if (matching.isEmpty()) return
        def selected = matching.findAll { it instanceof JavaExec }
        if (selected.size() != 1) throw new GradleException($missing)
        def task = selected[0]
        if (task.hasProperty('splitMode') && task.splitMode.getOrElse(false))
          throw new GradleException(${quote(JewelToolingBundle.message("launch.gradle.split"))})
        task.jvmArgumentProviders.add({ [$argument] } as CommandLineArgumentProvider)
        task.doFirst {
          if (task.javaLauncher.get().metadata.languageVersion.asInt() < 21)
            throw new GradleException(${quote(JewelToolingBundle.message("launch.jvm.unsupported"))})
          def marker = Path.of($marker)
          def temporary = marker.resolveSibling(marker.fileName.toString() + '.tmp')
          if (Files.getFileStore(marker.parent).supportsFileAttributeView('posix')) {
            Files.createFile(
              temporary,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString('rw-------')))
          } else {
            Files.createFile(temporary)
            def acl = Files.getFileAttributeView(temporary, java.nio.file.attribute.AclFileAttributeView)
            if (acl == null) {
              throw new GradleException(${quote(JewelToolingBundle.message("launch.private.permissions"))})
            }
            def owner = Files.getOwner(marker.parent)
            if (acl.owner != owner) {
              throw new GradleException(${quote(JewelToolingBundle.message("launch.private.owner"))})
            }
            acl.acl = [java.nio.file.attribute.AclEntry.newBuilder()
              .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner)
              .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission)).build()]
          }
          Files.writeString(temporary, $markerContent)
          Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE)
        }
      }
    """
            .trimIndent()
    }

    private fun quote(value: String): String =
        "'" +
            value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r") +
            "'"
}
