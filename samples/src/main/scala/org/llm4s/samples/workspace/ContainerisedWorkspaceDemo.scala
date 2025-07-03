package org.llm4s.samples.workspace

import org.llm4s.shared.ReplaceOperation
import org.llm4s.workspace.ContainerisedWorkspace
import org.slf4j.LoggerFactory

/**
 * Demonstrates the basic usage of the Workspace class
 */
object ContainerisedWorkspaceDemo extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  // Sample workspace directory on the host machine
  val workspaceDir = System.getProperty("user.home") + "/workspace-demo"
  logger.info(s"Using workspace directory: $workspaceDir")

  val workspace = new ContainerisedWorkspace(workspaceDir)

  try
    // Start the workspace container
    if (workspace.startContainer()) {
      logger.info("Container started successfully")

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
    } else {
      logger.error("Failed to start the workspace container")
    }
  catch {
    case e: Exception =>
      logger.error(s"Error during workspace demo: ${e.getMessage}", e)
  } finally
    // Always clean up the container
    if (workspace.stopContainer()) {
      logger.info("Container stopped successfully")
    } else {
      logger.error("Failed to stop the container")
    }
}
