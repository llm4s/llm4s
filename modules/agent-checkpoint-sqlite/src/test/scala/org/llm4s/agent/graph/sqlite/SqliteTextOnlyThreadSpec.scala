package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.EncodedOperation
import org.llm4s.agent.graph.tool.ToolContent
import org.llm4s.agent.graph.sqlite.TextOnlyThreadFixture.*
import org.llm4s.llmconnect.model.*
import org.llm4s.media.{ MediaSource, MediaType }
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.time.Clock
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

/**
 * A SQLite thread written by `main` before messages had content parts (#1913) restores and continues: its
 * text-only messages at `messages@1` and its failed batch's pending `tool-results@1` write migrate, `recover` runs
 * only the tool that failed, and a new turn adds a message with an image.
 */
class SqliteTextOnlyThreadSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val chart = ContentPart
    .image(MediaSource.Url(URI.create("https://example.com/forecast.png")), MediaType.Png)
    .fold(e => fail(e.message), identity)

  "A text-only thread written before content parts" should "restore, recover its failed batch and continue" in {
    val store  = opened()
    val stored = store.latest(Thread).value.value
    // the file is what main wrote: version-1 messages and a version-1 pending tool result
    stored.checkpoint.snapshot.state("messages").version shouldBe 1
    stored.pendingWrites.flatMap(_.operations).collect { case EncodedOperation.Update(key, update) =>
      key -> update.version
    } shouldBe Vector("tool-results" -> 1)

    val goodRuns = AtomicInteger()
    val client = Scripted(
      completion(AssistantMessage("Sunny tomorrow too.")),
      completion(AssistantMessage(None, Seq(call("c4", "lookup")))),
      completion(AssistantMessage("Here is the forecast."))
    )
    val agent = TextOnlyThreadFixture.agent(
      client,
      store,
      AtomicBoolean(false),
      lookup = ToolContent.Parts(Vector(ContentPart.Text("forecast"), chart)),
      goodRuns = goodRuns
    )

    val recovered = agent.recover(Thread).value
    recovered.answer shouldBe Some("Sunny tomorrow too.")
    goodRuns.get shouldBe 0
    client.sent.head shouldBe Vector(
      SystemMessage("You are a fixture."),
      UserMessage("What is the weather?"),
      AssistantMessage(None, Seq(call("c1", "lookup"))),
      ToolMessage("sunny", "c1"),
      AssistantMessage("It is sunny."),
      UserMessage("And tomorrow?"),
      AssistantMessage(None, Seq(call("c2", "good"), call("c3", "flaky"))),
      ToolMessage("good result", "c2"),
      ToolMessage("flaky result", "c3")
    )

    val next = agent.run(Thread, "Show me the forecast.").value
    next.answer shouldBe Some("Here is the forecast.")
    client.sent.last.collect { case t: ToolMessage => t }.last.parts shouldBe Vector(
      ContentPart.Text("forecast"),
      chart
    )
    store.close()

    // the thread is now at version 2, and a new process reads its media back
    val reopened = SqliteFiles.opened(store.path, Clock.systemUTC())
    reopened.latest(Thread).value.value.checkpoint.snapshot.state("messages").version shouldBe 2
    val again = Scripted(completion(AssistantMessage("Still sunny.")))
    TextOnlyThreadFixture.agent(again, reopened, AtomicBoolean(false)).run(Thread, "Thanks.").value
    again.sent.head.collect { case t: ToolMessage => t }.last.parts shouldBe Vector(ContentPart.Text("forecast"), chart)
    reopened.close()
  }
}
