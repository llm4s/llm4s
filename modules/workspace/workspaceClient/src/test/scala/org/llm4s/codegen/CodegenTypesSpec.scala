package org.llm4s.codegen

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for codegen package types.
 */
class CodegenTypesSpec extends AnyFlatSpec with Matchers {

  // ============ WorkspaceSettings ============

  "WorkspaceSettings" should "store all configuration fields" in {
    val settings = WorkspaceSettings(
      workspaceDir = "/home/user/code",
      imageName = "my-image:latest",
      hostPort = 9090
    )

    settings.workspaceDir shouldBe "/home/user/code"
    settings.imageName shouldBe "my-image:latest"
    settings.hostPort shouldBe 9090
  }

  it should "have default image name" in {
    WorkspaceSettings.DefaultImage shouldBe "docker.io/library/workspace-runner:0.1.0-SNAPSHOT"
  }

  it should "have default port" in {
    WorkspaceSettings.DefaultPort shouldBe 8080
  }

  it should "support equality" in {
    val s1 = WorkspaceSettings("/dir", "image", 8080)
    val s2 = WorkspaceSettings("/dir", "image", 8080)
    val s3 = WorkspaceSettings("/other", "image", 8080)

    s1 shouldBe s2
    s1 should not be s3
  }

  it should "support copy with modifications" in {
    val original = WorkspaceSettings("/dir", "image", 8080)
    val modified = original.copy(hostPort = 9000)

    modified.workspaceDir shouldBe "/dir"
    modified.hostPort shouldBe 9000
  }

  // ============ CodeTask ============

  "CodeTask" should "create with description only" in {
    val task = CodeTask("Implement a sorting algorithm")

    task.description shouldBe "Implement a sorting algorithm"
    task.maxSteps shouldBe None
    task.sourceDirectory shouldBe None
  }

  it should "create with all options" in {
    val task = CodeTask(
      description = "Refactor the authentication module",
      maxSteps = Some(10),
      sourceDirectory = Some("/src/auth")
    )

    task.description shouldBe "Refactor the authentication module"
    task.maxSteps shouldBe Some(10)
    task.sourceDirectory shouldBe Some("/src/auth")
  }

  it should "support equality" in {
    val t1 = CodeTask("task", Some(5), Some("/src"))
    val t2 = CodeTask("task", Some(5), Some("/src"))
    val t3 = CodeTask("different", Some(5), Some("/src"))

    t1 shouldBe t2
    t1 should not be t3
  }

  // ============ CodeTaskResult ============

  "CodeTaskResult" should "represent successful completion" in {
    val result = CodeTaskResult(
      success = true,
      message = "Task completed successfully",
      logs = Seq("Step 1 done", "Step 2 done")
    )

    result.success shouldBe true
    result.message shouldBe "Task completed successfully"
    result.logs should have size 2
  }

  it should "represent failure" in {
    val result = CodeTaskResult(
      success = false,
      message = "Failed to compile",
      logs = Seq("Error: missing semicolon")
    )

    result.success shouldBe false
    result.message shouldBe "Failed to compile"
  }

  it should "have default empty logs" in {
    val result = CodeTaskResult(success = true, message = "Done")

    result.logs shouldBe empty
  }

  it should "support equality" in {
    val r1 = CodeTaskResult(success = true, message = "OK", logs = Seq("log"))
    val r2 = CodeTaskResult(success = true, message = "OK", logs = Seq("log"))
    val r3 = CodeTaskResult(success = false, message = "OK", logs = Seq("log"))

    r1 shouldBe r2
    r1 should not be r3
  }
}
