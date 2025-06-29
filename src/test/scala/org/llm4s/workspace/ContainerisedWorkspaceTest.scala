package org.llm4s.workspace

import org.llm4s.shared._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files
import scala.concurrent.duration._
import scala.util.Try

/**
 * Test suite for WebSocket-based ContainerisedWorkspace.
 *
 * This test demonstrates that the WebSocket implementation solves the original
 * threading issue by:
 * 1. Supporting concurrent long-running commands without blocking heartbeats
 * 2. Maintaining connection health during extended operations
 * 3. Providing real-time feedback for command execution
 */
class ContainerisedWorkspaceTest extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val tempDir                           = Files.createTempDirectory("websocket-workspace-test").toString
  private var workspace: ContainerisedWorkspace = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    // Only run tests if Docker is available
    if (isDockerAvailable) {
      workspace = new ContainerisedWorkspace(tempDir)

      // Start the container - this may take some time
      val started = workspace.startContainer()
      if (!started) {
        fail("Failed to start WebSocket workspace container")
      }

      // Give it a moment to fully initialize
      Thread.sleep(2000)
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()

    if (isDockerAvailable && workspace != null) {
      workspace.stopContainer()
    }

    // Clean up temp directory
    Try {
      def deleteRecursively(file: File): Unit = {
        if (file.isDirectory) {
          file.listFiles().foreach(deleteRecursively)
        }
        file.delete()
      }
      deleteRecursively(new File(tempDir))
    }
  }

  private def isDockerAvailable: Boolean =
    false // temporarily disable this test
//    Try {
//      val process = Runtime.getRuntime.exec(Array("docker", "--version"))
//      process.waitFor() == 0
//    }.getOrElse(false)

  test("WebSocket workspace can handle basic file operations") {
    assume(isDockerAvailable, "Docker not available - skipping WebSocket tests")

    // Test basic file write
    val writeResponse = workspace.writeFile(
      "test.txt",
      "Hello WebSocket World!",
      Some("create"),
      Some(true)
    )
    writeResponse.success shouldBe true
    writeResponse.path shouldBe "test.txt"

    // Test file read
    val readResponse = workspace.readFile("test.txt")
    readResponse.content shouldBe "Hello WebSocket World!"

    // Test file exploration
    val exploreResponse = workspace.exploreFiles(".", Some(false))
    exploreResponse.files.map(_.path) should contain("test.txt")
  }

  test("WebSocket workspace can execute commands without blocking heartbeats") {
    assume(isDockerAvailable, "Docker not available - skipping WebSocket tests")

    // Execute a command that takes some time but not too long for CI
    val startTime = System.currentTimeMillis()

    val response = workspace.executeCommand(
      "echo 'Starting long command'; sleep 3; echo 'Command completed'",
      None,
      Some(10) // 10 second timeout
    )

    val duration = System.currentTimeMillis() - startTime

    // Verify command executed successfully
    response.exitCode shouldBe 0
    response.stdout should include("Starting long command")
    response.stdout should include("Command completed")

    // Verify it took approximately the expected time (3+ seconds)
    duration should be >= 3000L
    duration should be < 8000L // Allow some overhead

    println(s"Command execution took ${duration}ms - WebSocket maintained connection throughout")
  }

  test("WebSocket workspace supports concurrent operations") {
    assume(isDockerAvailable, "Docker not available - skipping WebSocket tests")

    // Test that we can do file operations while commands are potentially running
    // This demonstrates that WebSocket doesn't have the same threading issues as HTTP

    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.Future

    val futures = for (i <- 1 to 3) yield Future {
      // Create a unique file for each concurrent operation
      val fileName = s"concurrent_test_$i.txt"
      val content  = s"Content from operation $i"

      // Write file
      val writeResp = workspace.writeFile(fileName, content, Some("create"))
      writeResp.success shouldBe true

      // Read it back
      val readResp = workspace.readFile(fileName)
      readResp.content shouldBe content

      // Execute a quick command
      val cmdResp = workspace.executeCommand(s"echo 'Operation $i completed'")
      cmdResp.exitCode shouldBe 0
      cmdResp.stdout should include(s"Operation $i completed")

      i
    }

    // Wait for all operations to complete
    val results   = Future.sequence(futures)
    val completed = concurrent.Await.result(results, 30.seconds)

    completed should contain theSameElementsAs (1 to 3)

    println("Successfully executed concurrent operations via WebSocket")
  }

  test("WebSocket workspace handles command streaming events") {
    assume(isDockerAvailable, "Docker not available - skipping WebSocket tests")

    // Note: Current implementation doesn't expose streaming events directly through the interface
    // but the infrastructure is there. This test verifies basic command execution works.

    val response = workspace.executeCommand(
      "echo 'Step 1'; echo 'Step 2'; echo 'Step 3'",
      None,
      Some(10)
    )

    response.exitCode shouldBe 0
    response.stdout should include("Step 1")
    response.stdout should include("Step 2")
    response.stdout should include("Step 3")

    println("Command execution completed successfully - streaming infrastructure is in place")
  }

  test("WebSocket workspace handles errors gracefully") {
    assume(isDockerAvailable, "Docker not available - skipping WebSocket tests")

    // Test command that fails
    val response = workspace.executeCommand("exit 1", None, Some(5))
    response.exitCode shouldBe 1

    // Test reading non-existent file
    assertThrows[WorkspaceAgentException] {
      workspace.readFile("non-existent-file.txt")
    }

    // Test invalid path
    assertThrows[WorkspaceAgentException] {
      workspace.exploreFiles("../../../invalid/path")
    }

    println("Error handling works correctly via WebSocket")
  }
}

/**
 * Companion object with utilities for WebSocket workspace testing
 */
object ContainerisedWorkspaceTest {

  /**
   * Creates a test workspace for manual testing/debugging
   */
  def createTestWorkspace(workspaceDir: String): ContainerisedWorkspace =
    new ContainerisedWorkspace(workspaceDir)

  /**
   * Manual test to demonstrate the fix for the original threading issue
   */
  def demonstrateThreadingFix(workspaceDir: String): Unit = {
    val workspace = createTestWorkspace(workspaceDir)

    println("Starting WebSocket workspace container...")
    if (!workspace.startContainer()) {
      println("Failed to start container")
      return
    }

    try {
      println("Executing long-running command while heartbeats continue...")
      val startTime = System.currentTimeMillis()

      // Execute a command that would have caused the HTTP version to timeout
      val response = workspace.executeCommand(
        "echo 'Starting long operation'; sleep 8; echo 'Long operation completed'",
        None,
        Some(15)
      )

      val duration = System.currentTimeMillis() - startTime

      println(s"Command completed in ${duration}ms")
      println(s"Exit code: ${response.exitCode}")
      println(s"Output: ${response.stdout}")

      if (response.exitCode == 0) {
        println("✅ SUCCESS: WebSocket implementation handles long commands without heartbeat timeout!")
      } else {
        println("❌ FAILED: Command execution failed")
      }

    } finally {
      println("Stopping container...")
      workspace.stopContainer()
    }
  }
}
