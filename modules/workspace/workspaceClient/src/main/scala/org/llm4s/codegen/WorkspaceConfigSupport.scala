// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.codegen

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * PureConfig-based loader for workspace settings.
 *
 * This mirrors the behavior of WorkspaceSettings.load(), but is implemented
 * in terms of llm4s.* HOCON keys and the standard PureConfig flow.
 *
 * Keys (with precedence):
 *   - llm4s.workspace.dir        | WORKSPACE_DIR
 *   - llm4s.workspace.image      | WORKSPACE_IMAGE
 *   - llm4s.workspace.port       | WORKSPACE_PORT
 */
object WorkspaceConfigSupport {

  final private case class WorkspaceSection(
    dir: Option[String],
    image: Option[String],
    port: Option[Int]
  )

  final private case class WorkspaceRoot(workspace: Option[WorkspaceSection])

  implicit private val workspaceSectionReader: PureConfigReader[WorkspaceSection] =
    PureConfigReader.forProduct3("dir", "image", "port")(WorkspaceSection.apply)

  implicit private val workspaceRootReader: PureConfigReader[WorkspaceRoot] =
    PureConfigReader.forProduct1("workspace")(WorkspaceRoot.apply)

  /**
   * @param source PureConfig source; defaults to `ConfigSource.default`.
   */
  def load(source: ConfigSource = ConfigSource.default): Result[WorkspaceSettings] = {
    val rootEither = source.at("llm4s").load[WorkspaceRoot]

    rootEither.left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load llm4s workspace config via PureConfig: $msg")
      }
      .flatMap(buildSettings)
  }

  private def buildSettings(root: WorkspaceRoot): Result[WorkspaceSettings] = {
    val section = root.workspace.getOrElse(WorkspaceSection(None, None, None))

    val home       = System.getProperty("user.home")
    val defaultDir = s"$home/code-workspace"

    val dir   = section.dir.filter(_.trim.nonEmpty).getOrElse(defaultDir)
    val image = section.image.filter(_.trim.nonEmpty).getOrElse(WorkspaceSettings.DefaultImage)

    val port = section.port.getOrElse(WorkspaceSettings.DefaultPort)

    Right(WorkspaceSettings(dir, image, port))
  }
}
