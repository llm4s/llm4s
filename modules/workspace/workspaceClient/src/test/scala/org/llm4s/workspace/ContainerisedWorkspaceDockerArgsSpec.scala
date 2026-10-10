package org.llm4s.workspace

import org.llm4s.codegen.CodeGenExample
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** How `ContainerisedWorkspace` starts its container, and how `extraAllowedCommands` reaches the runner (#1756). */
class ContainerisedWorkspaceDockerArgsSpec extends AnyFlatSpec with Matchers {

  private def args(extra: Set[String]) =
    ContainerisedWorkspace.dockerRunArgs("ws-test", 18080, 8080, "/tmp/ws", "llm4s/workspace-runner:dev", extra)

  "ContainerisedWorkspace.dockerRunArgs" should "start the container with no extra environment by default" in {
    args(Set.empty) shouldBe Right(
      Seq(
        "docker",
        "run",
        "-d",
        "--name",
        "ws-test",
        "-p",
        "18080:8080",
        "-v",
        "/tmp/ws:/workspace",
        "llm4s/workspace-runner:dev"
      )
    )
  }

  it should "pass extra allowed commands as WORKSPACE_EXTRA_COMMANDS, sorted, before the image" in {
    val built = args(Set("sbt", "java")).fold(fail(_), identity)
    built.sliding(2).toSeq should contain(Seq("-e", "WORKSPACE_EXTRA_COMMANDS=java,sbt"))
    built.last shouldBe "llm4s/workspace-runner:dev"
  }

  it should "refuse a name the runner would refuse, so no container starts with it" in {
    args(Set("sbt", "sh")) shouldBe Left("'sh' runs other programs, so it cannot be added to the allowlist")
    args(Set("/usr/bin/sbt")) shouldBe Left("'/usr/bin/sbt' is not a bare program name")
    args(Set("sbt;id")).isLeft shouldBe true
  }

  "CodeGenExample" should "add sbt to the runner's allowlist and ask for one program per command" in {
    CodeGenExample.ExtraAllowedCommands shouldBe Set("sbt")
    args(CodeGenExample.ExtraAllowedCommands).fold(fail(_), identity) should contain("WORKSPACE_EXTRA_COMMANDS=sbt")
    CodeGenExample.Task should include("'sbt compile'")
    CodeGenExample.Task should include("'sbt run'")
    CodeGenExample.Task should include("without a shell")
  }
}
