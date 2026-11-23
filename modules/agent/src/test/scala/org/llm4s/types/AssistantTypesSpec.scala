package org.llm4s.types

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

/** The newtypes of the interactive assistant, which moved here from `llm4s-core` with it (#2820). */
class AssistantTypesSpec extends AnyFlatSpec with Matchers {

  "FilePath" should "extract extension" in {
    FilePath("/path/to/file.txt").extension shouldBe Some("txt")
    FilePath("/path/to/file.scala").extension shouldBe Some("scala")
    FilePath("/path/to/file").extension shouldBe None
    FilePath("/path/to/.hidden").extension shouldBe Some("hidden")
  }

  it should "convert to string via toString" in {
    FilePath("/path/to/file.txt").toString shouldBe "/path/to/file.txt"
  }

  "SessionId, FilePath and DirectoryPath" should "be written as their value and read back" in {
    write(SessionId("s-1")) shouldBe "\"s-1\""
    read[SessionId]("\"s-1\"") shouldBe SessionId("s-1")
    read[FilePath](write(FilePath("/a/b.txt"))) shouldBe FilePath("/a/b.txt")
    read[DirectoryPath](write(DirectoryPath("/a"))) shouldBe DirectoryPath("/a")
    SessionId("s-1").toString shouldBe "s-1"
    DirectoryPath("/a").toString shouldBe "/a"
  }
}
