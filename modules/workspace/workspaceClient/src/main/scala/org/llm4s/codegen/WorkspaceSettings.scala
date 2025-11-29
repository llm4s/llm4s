package org.llm4s.codegen

final case class WorkspaceSettings(
  workspaceDir: String,
  imageName: String,
  hostPort: Int,
  traceLogPath: String
)

object WorkspaceSettings {
  private[codegen] val DefaultImage = "docker.io/library/workspace-runner:0.1.0-SNAPSHOT"
  private[codegen] val DefaultPort  = 8080
}
