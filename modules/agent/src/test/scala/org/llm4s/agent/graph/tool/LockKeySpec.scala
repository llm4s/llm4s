package org.llm4s.agent.graph.tool

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LockKeySpec extends AnyFlatSpec with Matchers {

  "LockKey" should "split each part on '/' and drop empty segments" in {
    LockKey("workspace", "w1", "src/a.scala").segments shouldBe Vector("workspace", "w1", "src", "a.scala")
    LockKey("workspace/w1/src/a.scala") shouldBe LockKey("workspace", "w1", "src/a.scala")
    LockKey("/a//b/").segments shouldBe Vector("a", "b")
    (LockKey("workspace") / "w1/src").render shouldBe "workspace/w1/src"
  }

  "Two keys" should "conflict when one is a prefix of the other, segment by segment" in {
    val workspace = LockKey("workspace", "w1")
    val file      = LockKey("workspace", "w1", "src", "a.scala")
    workspace.conflictsWith(file) shouldBe true
    file.conflictsWith(workspace) shouldBe true
    file.conflictsWith(file) shouldBe true
    LockKey("a/b").conflictsWith(LockKey("a/bc")) shouldBe false
    LockKey("workspace/w1").conflictsWith(LockKey("workspace/w2")) shouldBe false
    LockKey("state-files").conflictsWith(LockKey("workspace/w1")) shouldBe false
  }

  "The root key" should "conflict with every key" in {
    LockKey("").segments shouldBe empty
    LockKey("").conflictsWith(LockKey("x/y")) shouldBe true
    LockKey("x").conflictsWith(LockKey("/")) shouldBe true
  }
}
