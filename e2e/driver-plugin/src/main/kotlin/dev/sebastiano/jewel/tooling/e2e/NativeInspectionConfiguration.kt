package dev.sebastiano.jewel.tooling.e2e

import com.google.gson.JsonParser
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Creates only a test configuration; the production plugin clones it before launch. */
internal object NativeInspectionConfiguration {
    @Suppress(
        "LongMethod",
        "NestedBlockDepth",
    ) // Build a disposable target from the installed IDE manifest.
    fun create(project: Project, output: Path): RunnerAndConfigurationSettings {
        val home = Path.of(PathManager.getHomePath())
        val info =
            listOf(home.resolve("Resources/product-info.json"), home.resolve("product-info.json"))
                .first(Files::isRegularFile)
        val launch =
            JsonParser.parseString(Files.readString(info))
                .asJsonObject
                .getAsJsonArray("launch")[0]
                .asJsonObject
        val jars =
            launch.getAsJsonArray("bootClassPathJarNames").map {
                home.resolve("lib").resolve(it.asString)
            }
        check(jars.all(Files::isRegularFile))
        val target = Files.createDirectories(Path.of(System.getProperty("jewel.test.targetHome")))
        val plugins = Files.createDirectories(target.resolve("plugins"))
        for (property in listOf("jewel.test.driverZip", "jewel.test.fixtureZip")) {
            ZipFile(System.getProperty(property)).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val path = plugins.resolve(entry.name).normalize()
                    check(path.startsWith(plugins))
                    if (entry.isDirectory) Files.createDirectories(path)
                    else {
                        Files.createDirectories(path.parent)
                        zip.getInputStream(entry).use { Files.copy(it, path) }
                    }
                }
            }
        }
        val targetProject = Files.createDirectories(target.resolve("project"))
        val module = ModuleManager.getInstance(project).modules.first()
        WriteCommandAction.runWriteCommandAction(project) {
            val sdk =
                ProjectJdkTable.getInstance().findJdk("Inspection JBR")
                    ?: JavaSdk.getInstance()
                        .createJdk("Inspection JBR", System.getProperty("java.home"))
                        .also { ProjectJdkTable.getInstance().addJdk(it) }
            ProjectRootManager.getInstance(project).projectSdk = sdk
            ModuleRootModificationUtil.setSdkInherited(module)
            ModuleRootModificationUtil.addModuleLibrary(
                module,
                "IDE launch",
                jars.map { VfsUtil.getUrlForLibraryRoot(it.toFile()) },
                emptyList(),
            )
        }
        val parameters =
            launch
                .getAsJsonArray("additionalJvmArguments")
                .map {
                    it.asString
                        .replace("\$APP_PACKAGE/Contents", home.toString())
                        .replace("\$IDE_HOME", home.toString())
                }
                .toMutableList()
        parameters +=
            listOf(
                "-Xmx2g",
                "-Didea.home.path=$home",
                "-Didea.config.path=${target.resolve("config")}",
                "-Didea.system.path=${target.resolve("system")}",
                "-Didea.log.path=${target.resolve("log")}",
                "-Didea.plugins.path=$plugins",
                "-Djewel.test.agentTarget=true",
                "-Djewel.test.commands=$output",
                "-Djb.consents.confirmation.enabled=false",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Djb.privacy.policy.ai.assistant.text=<!--999.999-->",
                "-Dmarketplace.eula.reviewed.and.accepted=true",
                "-Dwriterside.eula.reviewed.and.accepted=true",
                "-Dide.newUsersOnboarding=false",
                "-Dintellij.startup.wizard=false",
                "-Didea.trust.all.projects=true",
                "-Djetbrainsd.discovery.enabled=false",
                "-Djetbrainsd.uri.handling.enabled=false",
            )
        val manager = RunManager.getInstance(project)
        return manager
            .createConfiguration(
                "Bazel-built IntelliJ target",
                ApplicationConfigurationType.getInstance().configurationFactories.single(),
            )
            .also { settings ->
                val configuration = settings.configuration as ApplicationConfiguration
                configuration.setModule(module)
                configuration.mainClassName = launch.get("mainClass").asString
                configuration.vmParameters = ParametersListUtil.join(parameters)
                configuration.programParameters =
                    ParametersListUtil.join(listOf(targetProject.toString()))
                configuration.workingDirectory = target.toString()
                configuration.beforeRunTasks = emptyList()
                manager.addConfiguration(settings)
                manager.selectedConfiguration = settings
            }
    }
}
