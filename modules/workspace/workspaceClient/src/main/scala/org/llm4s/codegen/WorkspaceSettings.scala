package org.llm4s.codegen

/**
 * Configuration settings for workspace environment.
 *
 * Defines the directory, Docker image, and port configuration
 * for the containerized code workspace.
 *
 * @param workspaceDir directory path containing the codebase to work with
 * @param imageName Docker image name for the workspace container
 * @param hostPort host port for workspace communication
 *
 * @see [[WorkspaceConfigSupport]] for loading settings from configuration
 */
final case class WorkspaceSettings(
  workspaceDir: String,
  imageName: String,
  hostPort: Int
)

/**
 * Companion object with default workspace settings.
 */
object WorkspaceSettings {

  /** Default Docker image for the workspace container */
  private[codegen] val DefaultImage = "docker.io/library/workspace-runner:0.1.0-SNAPSHOT"

  /** Default host port for workspace communication */
  private[codegen] val DefaultPort = 8080
}
