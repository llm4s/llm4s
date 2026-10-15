package org.llm4s.agent.graph.toolloop

import org.llm4s.llmconnect.model.{ ContentPart, ToolMessage, UserMessage }
import org.llm4s.media.{ MediaSource, MediaType }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The thread's messages and a batch's tool results hold at most Messages.MaxInlineBytes of inline media in total. */
class InlineMediaBoundSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val part = ContentPart
    .image(MediaSource.Inline(IArray.fill[Byte](ContentPart.MaxInlineBytes)(1)), MediaType.Png)
    .fold(e => fail(e.message), identity)

  private def total(history: Vector[StoredMessage]): Long =
    history
      .map(_.message)
      .collect {
        case u: UserMessage => ContentPart.inlineBytes(u.parts)
        case t: ToolMessage => ContentPart.inlineBytes(t.parts)
      }
      .sum

  "The messages key" should "admit media while the thread is within its bound, and evict it from a message past the bound, never from earlier ones" in {
    Messages.MaxInlineBytes shouldBe 4L * ContentPart.MaxInlineBytes
    val history = (1 to 6).foldLeft(Vector.empty[StoredMessage]) { (h, i) =>
      Messages.key
        .applyUpdate(
          h,
          MessageUpdate.Append(StoredMessage(s"m$i", UserMessage(Vector(ContentPart.Text(s"turn $i"), part))))
        )
        .value
    }
    total(history) should be <= Messages.MaxInlineBytes
    history.take(4).map(_.message.asInstanceOf[UserMessage].parts.last) shouldBe Vector.fill(4)(part)
    history.drop(4).map(_.message.asInstanceOf[UserMessage].parts.last).foreach { evicted =>
      evicted shouldBe a[ContentPart.Text]
      evicted.asInstanceOf[ContentPart.Text].text should include("image (inline, image/png) removed")
    }
    history.map(_.message.content.linesIterator.next()) shouldBe (1 to 6).map(i => s"turn $i").toVector
  }

  it should "bound one message of many parts, and leave a thread within its bound untouched" in {
    val big = Messages.key
      .applyUpdate(Vector.empty, MessageUpdate.Append(StoredMessage("m", UserMessage(Vector.fill(6)(part)))))
      .value
    total(big) should be <= Messages.MaxInlineBytes
    val small = StoredMessage("m", UserMessage(Vector(part)))
    (Messages.admitted(Vector.empty, small) should be).theSameInstanceAs(small)
  }

  "tool-results" should "evict inline media from the results of a batch that would together be over the bound" in {
    val recorded = (1 to 6).foldLeft(Vector.empty[ToolResult]) { (rs, i) =>
      ToolLoop.results.applyUpdate(rs, ToolResult("a", s"c$i", Vector(part), isError = false)).value
    }
    recorded.map(r => ContentPart.inlineBytes(r.parts)).sum should be <= Messages.MaxInlineBytes
    recorded.take(4).map(_.parts) shouldBe Vector.fill(4)(Vector(part))
    recorded.drop(4).foreach(_.parts.head shouldBe a[ContentPart.Text])
  }

  "A blocked turn's retraction" should "leave the history before it as it was, media included" in {
    val before = (1 to 4).foldLeft(Vector.empty[StoredMessage]) { (h, i) =>
      Messages.key.applyUpdate(h, MessageUpdate.Append(StoredMessage(s"m$i", UserMessage(Vector(part))))).value
    }
    val turn = Messages.key
      .applyUpdate(
        before,
        MessageUpdate.Append(StoredMessage("t/user", UserMessage(Vector(ContentPart.Text("x"), part))))
      )
      .value
    turn.take(4) shouldBe before
    Messages.key.applyUpdate(turn, MessageUpdate.RemoveTurn("t/user")).value shouldBe before
  }

  it should "bound a replaced message too, against the rest of the history" in {
    val full = (1 to 4).foldLeft(Vector.empty[StoredMessage]) { (h, i) =>
      Messages.key.applyUpdate(h, MessageUpdate.Append(StoredMessage(s"m$i", UserMessage(Vector(part))))).value
    }
    val replaced = Messages.key
      .applyUpdate(full, MessageUpdate.Replace("m1", StoredMessage("m1", UserMessage(Vector(part, part)))))
      .value
    total(replaced) should be <= Messages.MaxInlineBytes
    replaced.drop(1) shouldBe full.drop(1)
  }
}
