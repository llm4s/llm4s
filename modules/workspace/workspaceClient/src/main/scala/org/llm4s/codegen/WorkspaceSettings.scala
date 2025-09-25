package org.llm4s.codegen

import org.llm4s.config.ConfigReader
import org.llm4s.types.Result

final case class WorkspaceSettings(
  workspaceDir: String,
  imageName: String,
  hostPort: Int,
  traceLogPath: String
)

object WorkspaceSettings {
  private val DefaultImage = "docker.io/library/workspace-runner:0.1.0-SNAPSHOT"
  private val DefaultPort  = 8080

  def load(): Result[WorkspaceSettings] =
    ConfigReader.LLMConfig().map { cfg =>
      val home         = System.getProperty("user.home")
      val defaultDir   = s"$home/code-workspace"
      val workspaceDir = cfg.getPath("llm4s.workspace.dir").orElse(cfg.get("WORKSPACE_DIR")).getOrElse(defaultDir)
      val imageName    = cfg.getPath("llm4s.workspace.image").orElse(cfg.get("WORKSPACE_IMAGE")).getOrElse(DefaultImage)
      val port = cfg
        .getPath("llm4s.workspace.port")
        .orElse(cfg.get("WORKSPACE_PORT"))
        .flatMap(s => scala.util.Try(s.trim.toInt).toOption)
        .getOrElse(DefaultPort)

      val defaultTrace = s"$workspaceDir/log/codegen-trace.md"
      val traceLogPath = cfg
        .getPath("llm4s.workspace.traceLogPath")
        .orElse(cfg.get("WORKSPACE_TRACE_LOG"))
        .getOrElse(defaultTrace)

      WorkspaceSettings(workspaceDir, imageName, port, traceLogPath)
    }
}
