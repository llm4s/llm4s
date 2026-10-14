package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.graph.VersionedJson
import org.llm4s.llmconnect.model.{ ContentPart, ToolMessage, UserMessage }
import org.llm4s.media.{ MediaSource, MediaType }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** The loop's keys moved to version 2 when messages gained content parts (#1913); version 1 still restores. */
class ContentPartsMigrationSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val image = ContentPart
    .image(MediaSource.Url(URI.create("https://example.com/a.png")), MediaType.Png)
    .fold(e => fail(e.message), identity)

  "Messages.key" should "be at version 2, and read version-1 messages as one text part each" in {
    Messages.key.stateVersion.current shouldBe 2
    Messages.key.updateVersion.current shouldBe 2
    val v1 = ujson.Arr(
      ujson.Obj("id" -> "0.0/user", "message"    -> ujson.Obj("type" -> "user", "content" -> "hi")),
      ujson.Obj("id" -> "2.0/tool/c1", "message" -> ujson.Obj("type" -> "tool", "toolCallId" -> "c1", "content" -> "r"))
    )
    Messages.key.decode(VersionedJson(1, v1)).value shouldBe
      Vector(StoredMessage("0.0/user", UserMessage("hi")), StoredMessage("2.0/tool/c1", ToolMessage("r", "c1")))
    val update = ujson.read(
      upickle.default.write[MessageUpdate](MessageUpdate.Append(StoredMessage("0.0/user", UserMessage("hi"))))
    )
    Messages.key.decodeUpdate(VersionedJson(1, update)).value shouldBe
      MessageUpdate.Append(StoredMessage("0.0/user", UserMessage("hi")))
  }

  it should "round-trip messages with media parts at version 2" in {
    val stored = Vector(StoredMessage("0.0/user", UserMessage(Vector(ContentPart.Text("see"), image))))
    val json   = Messages.key.encode(stored)
    json.version shouldBe 2
    Messages.key.decode(json).value shouldBe stored
  }

  "tool-results" should "migrate a version-1 result's content to one text part" in {
    val results = ToolLoop.results
    results.stateVersion.current shouldBe 2
    results.updateVersion.current shouldBe 2
    val v1 =
      ujson.Obj("assistantMessageId" -> "7.0/assistant", "toolCallId" -> "c2", "content" -> "good", "isError" -> false)
    val expected = ToolResult("7.0/assistant", "c2", Vector(ContentPart.Text("good")), isError = false)
    results.decodeUpdate(VersionedJson(1, v1)).value shouldBe expected
    results.decode(VersionedJson(1, ujson.Arr(v1))).value shouldBe Vector(expected)
    val withImage = ToolResult("7.0/assistant", "c3", Vector(image), isError = false)
    results.decode(results.encode(Vector(withImage))).value shouldBe Vector(withImage)
  }
}
