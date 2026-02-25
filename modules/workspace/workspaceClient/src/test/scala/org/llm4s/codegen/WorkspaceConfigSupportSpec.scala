package org.llm4s.codegen

import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

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
      ws.traceLogPath shouldBe s"$defaultDir/log/codegen-trace.md"
    }
  }
}
