package org.llm4s.samples.workspace

import org.llm4s.runner.WorkspaceAgentInterfaceImpl
import org.llm4s.shared.WorkspaceSandboxConfig
import org.slf4j.LoggerFactory
import scala.util.Try

/**
 * Minimal runnable example of a locked-down workspace sandbox.
 *
 * Uses [[WorkspaceSandboxConfig.LockedDown]]:
 * - Read-only file ops (explore, read, search) are allowed
 * - Shell execution is disabled (executeCommand throws)
 * - Strict resource limits
 *
 * Run without Docker (uses local filesystem):
 * {{{
 *   sbt "workspaceSamples/runMain org.llm4s.samples.workspace.LockedDownSandboxDemo"
 * }}}
 *
 * To run the containerized runner with locked sandbox:
 * 1. sbt workspaceRunner/docker:publishLocal
 * 2. docker images llm4s/workspace-runner --format "{{.Tag}}"   # get the tag (e.g. 0.1.0-SNAPSHOT)
 * 3. docker run --rm -e WORKSPACE_SANDBOX_PROFILE=locked -v /path/to/workspace:/workspace -p 8080:8080 llm4s/workspace-runner:TAG
 *    (Replace TAG with the tag from step 2; do not use literal "tag" or "<tag>".)
 */
object LockedDownSandboxDemo {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val workspaceDir = args.headOption.getOrElse(System.getProperty("user.dir"))
    val sandbox      = WorkspaceSandboxConfig.LockedDown
    val isWindows    = System.getProperty("os.name").contains("Windows")

    WorkspaceSandboxConfig.validate(sandbox) match {
      case Left(err) =>
        logger.error(s"Sandbox validation failed: $err")
        System.exit(1)
      case Right(_) =>
        logger.info("Sandbox config validated OK")
    }

    val interface = new WorkspaceAgentInterfaceImpl(workspaceDir, isWindows, Some(sandbox))
    logger.info(s"Using workspace: $workspaceDir (locked-down: shellAllowed=${sandbox.shellAllowed})")

    // File ops work
    val info = interface.getWorkspaceInfo()
    logger.info(s"Workspace root: ${info.root}, limits: ${info.limits}")

    val explore = Try(interface.exploreFiles(".", recursive = Some(false)))
    explore.fold(
      ex => logger.warn(s"Explore failed (expected if dir missing): ${ex.getMessage}"),
      resp => logger.info(s"Explore OK: ${resp.files.size} entries")
    )

    // Shell is disabled
    val exec = Try(interface.executeCommand("echo hello"))
    exec.fold(
      ex => logger.info(s"ExecuteCommand rejected (expected): ${ex.getMessage}"),
      _ => logger.warn("ExecuteCommand succeeded - shell should be disabled!")
    )

    if (exec.isFailure) {
      logger.info("Locked-down sandbox demo: file ops allowed, shell disabled as configured")
    } else {
      logger.warn("Unexpected: shell was allowed in locked-down mode")
      System.exit(1)
    }
  }
}
