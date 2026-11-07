package org.llm4s.samples.agent

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PermissionRulesExampleSpec extends AnyFunSuite with Matchers {
  test("the permission example allows, denies and approves the expected writes") {
    val result = PermissionRulesExample.run().fold(error => fail(error.formatted), identity)

    result.files should not contain "/etc/passwd"
    result.deniedResult should include("Denied: write /etc/passwd")
    result.files("/src/A.scala") shouldBe "object A"
    result.files("/deploy/app.yaml") shouldBe "app: demo"
  }
}
