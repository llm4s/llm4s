package org.llm4s.workspace

import org.llm4s.shared._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files
import scala.concurrent.duration._
import scala.util.Try
import org.llm4s.it.Tier
import org.llm4s.it.tags.Workspace

/**
 * Test suite for WebSocket-based ContainerisedWorkspace.
 *
 * This suite requires Docker plus `LLM4S_DOCKER_TESTS=true` and lives in the
 * dedicated integration-test module so it does not slow down default `sbt test`.
 */
@Workspace
class ContainerisedWorkspaceTest extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val tempDir                           = Files.createTempDirectory("websocket-workspace-test").toString
  private var workspace: ContainerisedWorkspace = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    if (isDockerAvailable) {
      workspace = new ContainerisedWorkspace(tempDir, ContainerisedWorkspaceTest.image, 8080)

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
    Tier.require(isDockerAvailable, "Docker not available or LLM4S_DOCKER_TESTS!=true")

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
    Tier.require(isDockerAvailable, "Docker not available or LLM4S_DOCKER_TESTS!=true")

    // Commands are argv, not shell text (#1756): `tail -f` runs until the timeout, which stands in for the
    // `sleep` the shell form used to chain.
    workspace.writeFile("heartbeat.txt", "Starting long command\n", Some("overwrite")).success shouldBe true

    val startTime = System.currentTimeMillis()
    val response  = workspace.executeCommand("tail -f heartbeat.txt", None, Some(3.seconds))
    val duration  = System.currentTimeMillis() - startTime

    response.exitCode shouldBe -1 // the runner's timeout
    response.stdout should include("Starting long command")
    duration should be >= 3000L
    duration should be < 15000L

    // The connection is still healthy afterwards.
    val after = workspace.executeCommand("echo Command completed", None, Some(10.seconds))
    after.exitCode shouldBe 0
    after.stdout should include("Command completed")
  }

  test("WebSocket workspace supports concurrent operations") {
    Tier.require(isDockerAvailable, "Docker not available or LLM4S_DOCKER_TESTS!=true")

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
    Tier.require(isDockerAvailable, "Docker not available or LLM4S_DOCKER_TESTS!=true")

    val chunks = new java.util.concurrent.ConcurrentLinkedQueue[StreamingOutputMessage]()
    val response = workspace.executeCommandWithStreaming(
      "echo 'Step 1' 'Step 2' 'Step 3'",
      None,
      Some(10.seconds),
      outputHandler = chunk => { chunks.add(chunk); () }
    )

    response.exitCode shouldBe 0
    response.stdout shouldBe "Step 1 Step 2 Step 3\n"
    import scala.jdk.CollectionConverters._
    chunks.asScala.filter(_.outputType == "stdout").map(_.content).mkString shouldBe "Step 1 Step 2 Step 3\n"
  }

  test("WebSocket workspace handles errors gracefully") {
    Tier.require(isDockerAvailable, "Docker not available or LLM4S_DOCKER_TESTS!=true")

    workspace.writeFile("errors.txt", "alpha\n", Some("overwrite")).success shouldBe true
    val response = workspace.executeCommand("grep zzz errors.txt", None, Some(5.seconds))
    response.exitCode shouldBe 1

    assertThrows[WorkspaceAgentException] {
      workspace.readFile("non-existent-file.txt")
    }

    assertThrows[WorkspaceAgentException] {
      workspace.exploreFiles("../../../invalid/path")
    }
  }

  test("WebSocket commands go through the command policy, not a shell (#1756)") {
    Tier.require(isDockerAvailable, "Docker not available or LLM4S_DOCKER_TESTS!=true")

    def refusedCode(
      command: String,
      environment: Option[Map[String, String]] = None
    ): String = {
      val e = intercept[WorkspaceAgentException] {
        workspace.executeCommand(command, None, Some(5.seconds), environment)
      }
      // The client reports a refusal as "<runner code>: <message>".
      e.error.takeWhile(_ != ':')
    }

    refusedCode("echo one ; touch chained.txt") shouldBe "FORBIDDEN_CHARACTERS"
    refusedCode("cat errors.txt | sh") shouldBe "FORBIDDEN_CHARACTERS"
    refusedCode("echo $(id)") shouldBe "FORBIDDEN_CHARACTERS"
    refusedCode("echo pwned > redirected.txt") shouldBe "FORBIDDEN_CHARACTERS"
    refusedCode("sh -c id") shouldBe "EXECUTABLE_NOT_ALLOWED"
    refusedCode("find . -delete") shouldBe "ARGUMENT_NOT_ALLOWED"
    refusedCode("cat /etc/passwd") shouldBe "PATH_ESCAPE_ATTEMPT"
    refusedCode("ls", Some(Map("LD_PRELOAD" -> "/tmp/evil.so"))) shouldBe "ENVIRONMENT_NOT_ALLOWED"

    workspace.exploreFiles(".", Some(false)).files.map(_.path) should contain noneOf ("chained.txt", "redirected.txt")
  }

  test("WebSocket commands get end-of-file on stdin instead of hanging") {
    Tier.require(isDockerAvailable, "Docker not available or LLM4S_DOCKER_TESTS!=true")

    val startTime = System.currentTimeMillis()
    val response  = workspace.executeCommand("cat", None, Some(20.seconds))
    response.exitCode shouldBe 0
    (System.currentTimeMillis() - startTime) should be < 10000L
  }
}

object ContainerisedWorkspaceTest {

  /**
   * The workspace-runner image to test against.
   *
   * The build passes the tag it would publish locally (see `it / Test / envVars` in build.sbt),
   * so this tracks the project version instead of the hardcoded `0.1.0-SNAPSHOT` that had gone
   * stale while nothing ran this suite.
   */
  def image: String =
    sys.env.getOrElse("LLM4S_WORKSPACE_IMAGE", "llm4s/workspace-runner:latest")

  def createTestWorkspace(workspaceDir: String): ContainerisedWorkspace =
    new ContainerisedWorkspace(workspaceDir, image, 8080)

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

      // Commands are argv, not shell text (#1756): `tail -f` on an empty file runs until the 8 s timeout.
      workspace.writeFile("long-operation.txt", "", Some("overwrite"))
      val response = workspace.executeCommand("tail -f long-operation.txt", None, Some(8.seconds))

      val duration = System.currentTimeMillis() - startTime

      println(s"Command completed in ${duration}ms")
      println(s"Exit code: ${response.exitCode}")
      println(s"Output: ${response.stdout}")

      if (response.exitCode == -1 && workspace.executeCommand("echo still-connected").exitCode == 0) {
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
