package org.llm4s.samples.workspace

import org.llm4s.shared.ReplaceOperation
import org.llm4s.workspace.ContainerisedWorkspace
import org.slf4j.LoggerFactory
import scala.util.{ Try, Using }
import org.llm4s.types.TryOps

/**
 * Demonstrates the basic usage of the Workspace class
 */
object ContainerisedWorkspaceDemo {
  private val logger = LoggerFactory.getLogger(getClass)

  // Define a resource that manages the workspace container lifecycle
  def withWorkspaceContainer[T](workspace: ContainerisedWorkspace)(f: ContainerisedWorkspace => T): Try[T] =
    if (workspace.startContainer()) {
      logger.info("Container started successfully")
      Try(f(workspace))
    } else {
      throw new RuntimeException("Failed to start the workspace container")
    }

  private def usage(): Unit =
    println(
      """
        |Usage:
        |  sbt "samples/runMain org.llm4s.samples.workspace.ContainerisedWorkspaceDemo --image <repo/name:tag> [--port <port>] [--workspace <path>]"
        |  sbt "samples/runMain org.llm4s.samples.workspace.ContainerisedWorkspaceDemo --tag <tag> [--repo <repo/name>] [--port <port>] [--workspace <path>]"
        |
        |Options:
        |  --image       Full Docker image reference, e.g. "llm4s/workspace-runner:1.2.3" (takes precedence)
        |  --tag         Image tag only, e.g. "1.2.3" (requires/assumes a repo)
        |  --repo        Image repository/name, e.g. "llm4s/workspace-runner" (default with --tag: llm4s/workspace-runner)
        |  --port        Host port mapped to container 8080 (default: 8080)
        |  --workspace   Host path to mount at /workspace (default: ~/workspace-demo)
        |
        |Examples:
        |  sbt "samples/runMain org.llm4s.samples.workspace.ContainerisedWorkspaceDemo --image llm4s/workspace-runner:0.1.11-abc-SNAPSHOT --port 18080"
        |  sbt "samples/runMain org.llm4s.samples.workspace.ContainerisedWorkspaceDemo --tag 0.1.11-abc-SNAPSHOT --repo llm4s/workspace-runner"
        |
        |Notes:
        |  - Docker repository names must be lowercase.
        |  - Tags should avoid '+' characters (use '-' for Docker).
        |""".stripMargin
    )

  private def parseArgs(args: Array[String]): Map[String, String] = {
    val m = scala.collection.mutable.Map.empty[String, String]
    var i = 0
    while (i < args.length) {
      val a = args(i)
      if (a == "-h" || a == "--help") { m += ("help" -> "true"); i += 1 }
      else if (a.startsWith("--") && a.contains("=")) {
        val idx = a.indexOf('='); m += (a.substring(2, idx) -> a.substring(idx + 1)); i += 1
      } else if (a.startsWith("--") && i + 1 < args.length && !args(i + 1).startsWith("--")) {
        m += (a.substring(2) -> args(i + 1)); i += 2
      } else i += 1
    }
    m.toMap
  }

  def main(args: Array[String]): Unit = {
    val parsed = parseArgs(args)
    if (parsed.contains("help")) { usage(); return }

    val defaultWorkspace = System.getProperty("user.home") + "/workspace-demo"
    val workspaceDir     = parsed.getOrElse("workspace", defaultWorkspace)
    val imageOpt         = parsed.get("image")
    val tagOpt           = parsed.get("tag")
    val repo             = parsed.getOrElse("repo", "llm4s/workspace-runner")
    val hostPort         = parsed.get("port").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(8080)

    val imageName =
      if (imageOpt.isDefined) imageOpt.get
      else if (tagOpt.isDefined) s"$repo:${tagOpt.get}"
      else {
        println("Error: provide either --image <repo/name:tag> or --tag <tag> (optionally --repo <repo/name>).\n")
        usage(); return
      }

    val workspace = new ContainerisedWorkspace(workspaceDir, imageName, hostPort)
    logger.info(s"Using workspace directory: $workspaceDir")
    logger.info(s"Runner image: $imageName; host port: $hostPort")

    val result = Using.resource(workspace) { ws =>
      withWorkspaceContainer(ws) { _ =>
        // List the workspace directory
        val dirContents = workspace.exploreFiles("/workspace")
        logger.info(s"Initial directory contents: ${dirContents.files.map(_.path).mkString(", ")}")

        // Execute a simple command
        val echoResult = workspace.executeCommand("echo 'Hello from workspace'")
        logger.info(s"Echo command result (exit code ${echoResult.exitCode}): ${echoResult.stdout}")

        // Create a new file
        val touchResult = workspace.executeCommand("touch /workspace/test_file.txt")
        logger.info(s"Touch command exit code: ${touchResult.exitCode}")

        // Create a new file
        val sbtVersionResult = workspace.executeCommand("sbt --version")
        logger.info(s"SBT Version Result exit code: ${sbtVersionResult.exitCode}")
        logger.info(s"SBT Version Result exit code: ${sbtVersionResult.stdout}")

        // List the directory again to see the new file
        val updatedContents = workspace.exploreFiles("/workspace")
        logger.info(s"Updated directory contents: ${updatedContents.files.map(_.path).mkString(", ")}")

        // Write content to the file using the interface
        val writeResult = workspace.writeFile("/workspace/test_file.txt", "This is a test file")
        logger.info(s"Write to file success: ${writeResult.success}")

        // Read the file content using the interface
        val readResult = workspace.readFile("/workspace/test_file.txt")
        logger.info(s"File content: ${readResult.content}")

        // Modify the file using file operations
        val modifyResult = workspace.modifyFile(
          "/workspace/test_file.txt",
          List(
            ReplaceOperation(startLine = 0, endLine = 4, newContent = "This")
          )
        )
        logger.info(s"File modification success: ${modifyResult.success}")

        // Read the modified file
        val modifiedContent = workspace.readFile("/workspace/test_file.txt")
        logger.info(s"Modified file content: ${modifiedContent.content}")

        // Search for content in files
        val searchResult = workspace.searchFiles(
          List("/workspace"),
          "new line",
          "literal",
          recursive = Some(true)
        )
        logger.info(s"Search results: ${searchResult.matches.length} matches found")
        searchResult.matches.foreach(match_ => logger.info(s"Match in ${match_.path}: ${match_.matchText}"))

        // Get workspace info
        val workspaceInfo = workspace.getWorkspaceInfo()
        logger.info(s"Workspace info: ${workspaceInfo.root}")
      }
    } { ws =>
      // Cleanup: always try to stop the container
      if (ws.stopContainer()) {
        logger.info("Container stopped successfully")
      } else {
        logger.error("Failed to stop the container")
      }
    }

    result.toResult.fold(
      error => logger.error(s"Error during workspace demo: ${error.message}"),
      _ => logger.info("Workspace demo completed successfully")
    )
  }
}
