package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ToolHintsSpec extends AnyFlatSpec with Matchers {

  "ToolHints.apply" should "default to the conservative MCP hints" in {
    val hints = ToolHints()
    hints.readOnly shouldBe false
    hints.destructive shouldBe true
    hints.idempotent shouldBe false
    hints.openWorld shouldBe true
    ToolHints.default shouldBe hints
  }

  it should "keep each hint it is given" in {
    ToolHints(readOnly = true, destructive = false, idempotent = true, openWorld = false) shouldBe
      ToolHints.default.withReadOnly(true).withDestructive(false).withIdempotent(true).withOpenWorld(false)
  }

  "The ToolHints setters" should "each change only their own hint" in {
    val d = ToolHints.default
    d.withReadOnly(true) shouldBe ToolHints(readOnly = true)
    d.withDestructive(false) shouldBe ToolHints(destructive = false)
    d.withIdempotent(true) shouldBe ToolHints(idempotent = true)
    d.withOpenWorld(false) shouldBe ToolHints(openWorld = false)
  }
}
