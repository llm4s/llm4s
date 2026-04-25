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
 * This suite requires Docker plus `LLM4S_DOCKER_TESTS=true` and lives in the
 * dedicated integration-test module so it does not slow down default `sbt test`.
 */
class ContainerisedWorkspaceTest extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val tempDir                           = Files.createTempDirectory("websocket-workspace-test").toString
  private var workspace: ContainerisedWorkspace = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    if (isDockerAvailable) {
      workspace = new ContainerisedWorkspace(tempDir, "docker.io/library/workspace-runner:0.1.0-SNAPSHOT", 8080)

      val started = workspace.startContainer()
      if (!started) {
        fail("Failed to start WebSocket workspace container")
      }

      Thread.sleep(2000)
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()

    if (isDockerAvailable && workspace != null) {
      workspace.stopContainer()
    }

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

  private val EnableDockerEnvVar = "LLM4S_DOCKER_TESTS"

  private def isDockerAvailable: Boolean =
    sys.env.get(EnableDockerEnvVar).exists(_.equalsIgnoreCase("true")) &&
      Try {
        val process = Runtime.getRuntime.exec(Array("docker", "--version"))
        process.waitFor() == 0
      }.getOrElse(false)

  test("WebSocket workspace can handle basic file operations") {
    assume(isDockerAvailable, "Docker not available - skipping WebSocket tests")

    val writeResponse = workspace.writeFile(
      "test.txt",
      "Hello WebSocket World!",
      Some("create"),
      Some(true)
    )
    writeResponse.success shouldBe true
    writeResponse.path shouldBe "test.txt"

    val readResponse = workspace.readFile("test.txt")
    readResponse.content shouldBe "Hello WebSocket World!"

    val exploreResponse = workspace.exploreFiles(".", Some(false))
    exploreResponse.files.map(_.path) should contain("test.txt")
  }

  test("WebSocket workspace can execute commands without blocking heartbeats") {
    assume(isDockerAvailable, "Docker not available - skipping WebSocket tests")

    val startTime = System.currentTimeMillis()

    val response = workspace.executeCommand(
      "echo 'Starting long command'; sleep 3; echo 'Command completed'",
      None,
      Some(10)
    )

    val duration = System.currentTimeMillis() - startTime

    response.exitCode shouldBe 0
    response.stdout should include("Starting long command")
    response.stdout should include("Command completed")
    duration should be >= 3000L
    duration should be < 8000L
  }

  test("WebSocket workspace supports concurrent operations") {
    assume(isDockerAvailable, "Docker not available - skipping WebSocket tests")

    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.Future

    val futures = for (i <- 1 to 3) yield Future {
      val fileName = s"concurrent_test_$i.txt"
      val content  = s"Content from operation $i"

      val writeResp = workspace.writeFile(fileName, content, Some("create"))
      writeResp.success shouldBe true

      val readResp = workspace.readFile(fileName)
      readResp.content shouldBe content

      val cmdResp = workspace.executeCommand(s"echo 'Operation $i completed'")
      cmdResp.exitCode shouldBe 0
      cmdResp.stdout should include(s"Operation $i completed")

      i
    }

    val results   = Future.sequence(futures)
    val completed = concurrent.Await.result(results, 30.seconds)

    completed should contain theSameElementsAs (1 to 3)
  }

  test("WebSocket workspace handles command streaming events") {
    assume(isDockerAvailable, "Docker not available - skipping WebSocket tests")

    val response = workspace.executeCommand(
      "echo 'Step 1'; echo 'Step 2'; echo 'Step 3'",
      None,
      Some(10)
    )

    response.exitCode shouldBe 0
    response.stdout should include("Step 1")
    response.stdout should include("Step 2")
    response.stdout should include("Step 3")
  }

  test("WebSocket workspace handles errors gracefully") {
    assume(isDockerAvailable, "Docker not available - skipping WebSocket tests")

    val response = workspace.executeCommand("exit 1", None, Some(5))
    response.exitCode shouldBe 1

    assertThrows[WorkspaceAgentException] {
      workspace.readFile("non-existent-file.txt")
    }

    assertThrows[WorkspaceAgentException] {
      workspace.exploreFiles("../../../invalid/path")
    }
  }
}

object ContainerisedWorkspaceTest {

  def createTestWorkspace(workspaceDir: String): ContainerisedWorkspace =
    new ContainerisedWorkspace(workspaceDir, "docker.io/library/workspace-runner:0.1.0-SNAPSHOT", 8080)

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
        println("SUCCESS: WebSocket implementation handles long commands without heartbeat timeout!")
      } else {
        println("FAILED: Command execution failed")
      }

    } finally {
      println("Stopping container...")
      workspace.stopContainer()
    }
  }
}
