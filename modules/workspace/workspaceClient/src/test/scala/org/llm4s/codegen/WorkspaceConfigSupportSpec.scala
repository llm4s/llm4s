package org.llm4s.codegen

import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

class WorkspaceConfigSupportSpec extends AnyWordSpec with Matchers {

  "WorkspaceConfigSupport.load" should {
    "use defaults when no workspace config is provided" in {
      val home                           = System.getProperty("user.home")
      val defaultDir                     = s"$home/code-workspace"
      val res: Result[WorkspaceSettings] = WorkspaceConfigSupport.load()

      val ws = res.fold(e => fail(s"Failed: ${e.formatted}"), identity)
      ws.workspaceDir shouldBe defaultDir
      ws.imageName shouldBe WorkspaceSettings.DefaultImage
      ws.hostPort shouldBe WorkspaceSettings.DefaultPort
    }

    // The block moved from core's reference.conf to this module's (#2806): its variables still reach it.
    "read WORKSPACE_DIR, WORKSPACE_IMAGE and WORKSPACE_PORT through this module's reference.conf" in {
      val variables = """WORKSPACE_DIR = "/srv/ws", WORKSPACE_IMAGE = "my/image:1", WORKSPACE_PORT = 9123"""
      // Only this module's reference.conf: core's no longer binds the block.
      val reference = ConfigSource
        .string(variables)
        .withFallback(ConfigSource.resources("reference.conf"))

      reference.at("llm4s.workspace.port").value().isRight shouldBe true
      val ws = WorkspaceConfigSupport
        .load(reference)
        .fold(e => fail(s"Failed: ${e.formatted}"), identity)
      ws.workspaceDir shouldBe "/srv/ws"
      ws.imageName shouldBe "my/image:1"
      ws.hostPort shouldBe 9123
    }
  }
}
