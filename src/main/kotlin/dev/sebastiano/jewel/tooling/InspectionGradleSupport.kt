package dev.sebastiano.jewel.tooling

import com.intellij.execution.configurations.RunConfiguration
import java.nio.file.Path

internal interface InspectionGradleSupport {
  fun supports(configuration: RunConfiguration): Boolean

  fun prepare(configuration: RunConfiguration, agent: Path, directory: Path, nonce: String)
}
