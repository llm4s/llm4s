package org.llm4s.codegen

import org.llm4s.error.ConfigurationError
import org.llm4s.shared.WorkspaceSandboxConfig
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
 *   - llm4s.workspace.traceLogPath | WORKSPACE_TRACE_LOG
 */
object WorkspaceConfigSupport {

  final private case class WorkspaceSection(
    dir: Option[String],
    image: Option[String],
    port: Option[Int],
    traceLogPath: Option[String]
  )

  final private case class WorkspaceRoot(workspace: Option[WorkspaceSection])

  implicit private val workspaceSectionReader: PureConfigReader[WorkspaceSection] =
    PureConfigReader.forProduct4("dir", "image", "port", "traceLogPath")(WorkspaceSection.apply)

  implicit private val workspaceRootReader: PureConfigReader[WorkspaceRoot] =
    PureConfigReader.forProduct1("workspace")(WorkspaceRoot.apply)

  /**
   * Load sandbox config from llm4s.workspace.sandbox.profile or use default.
   * Validates config; returns Left on validation failure.
   */
  def loadSandboxConfig(): Result[WorkspaceSandboxConfig] = {
    val profileOpt = ConfigSource.default.at("llm4s.workspace.sandbox.profile").load[String].toOption

    val baseConfig: Result[WorkspaceSandboxConfig] =
      profileOpt match {
        // No profile configured -> default to permissive (backwards compatible)
        case None =>
          Right(WorkspaceSandboxConfig.Permissive)

        // Profile string provided -> must parse to a known profile
        case Some(value) =>
          WorkspaceSandboxConfig
            .fromProfileName(value)
            .left
            .map(msg => ConfigurationError(msg, Nil))
      }

    baseConfig.flatMap { cfg =>
      WorkspaceSandboxConfig
        .validate(cfg)
        .left
        .map(msg => ConfigurationError(msg, Nil))
        .map(_ => cfg)
    }
  }

  def load(): Result[WorkspaceSettings] = {
    val rootEither = ConfigSource.default.at("llm4s").load[WorkspaceRoot]

    rootEither.left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load llm4s workspace config via PureConfig: $msg")
      }
      .flatMap(buildSettings)
  }

  private def buildSettings(root: WorkspaceRoot): Result[WorkspaceSettings] = {
    val section = root.workspace.getOrElse(WorkspaceSection(None, None, None, None))

    val home       = System.getProperty("user.home")
    val defaultDir = s"$home/code-workspace"

    val dir   = section.dir.filter(_.trim.nonEmpty).getOrElse(defaultDir)
    val image = section.image.filter(_.trim.nonEmpty).getOrElse(WorkspaceSettings.DefaultImage)

    val port = section.port.getOrElse(WorkspaceSettings.DefaultPort)

    val defaultTrace = s"$dir/log/codegen-trace.md"
    val trace = section.traceLogPath
      .filter(_.trim.nonEmpty)
      .getOrElse(defaultTrace)

    Right(WorkspaceSettings(dir, image, port, trace))
  }
}
