package org.llm4s.samples.chat.tui

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }

class ChatTuiToolSpec extends AnyFlatSpec with Matchers {

  private def withTempRoot[A](f: Path => A): A = {
    val root = Files.createTempDirectory("chat-tui-tool-spec")
    try f(root)
    finally {
      // Best-effort cleanup; not critical for the assertion semantics.
      val _ = root.toFile.delete()
    }
  }

  "safeResolve" should "accept a path inside the workspace" in withTempRoot { root =>
    val file = Files.createFile(root.resolve("hello.txt"))
    ChatTuiTool.safeResolve(root, "hello.txt") shouldBe Right(file)
  }

  it should "reject empty paths" in withTempRoot { root =>
    ChatTuiTool.safeResolve(root, "") match {
      case Left(msg) => msg should include("non-empty")
      case Right(_)  => fail("expected Left for empty path")
    }
  }

  it should "reject parent-traversal escapes" in withTempRoot { root =>
    ChatTuiTool.safeResolve(root, "../../etc/passwd") match {
      case Left(msg) => msg should include("outside the workspace")
      case Right(_)  => fail("expected Left for traversal escape")
    }
  }

  it should "reject absolute paths that aren't under the workspace" in withTempRoot { root =>
    ChatTuiTool.safeResolve(root, "/etc/passwd") match {
      case Left(msg) => msg should include("outside the workspace")
      case Right(_)  => fail("expected Left for absolute escape")
    }
  }

  "tool" should "build a registered ToolFunction" in withTempRoot { root =>
    val maybeTool = ChatTuiTool.tool(root)
    maybeTool match {
      case Right(tool) =>
        tool.name shouldBe ChatTuiTool.Name
        tool.description should include("64 KB")
      case Left(err) => fail(s"tool() returned Left: ${err.formatted}")
    }
  }
}
