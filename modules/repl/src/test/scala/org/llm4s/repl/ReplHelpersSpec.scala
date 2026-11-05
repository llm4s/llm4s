package org.llm4s.repl

import org.llm4s.error.{ AuthenticationError, TroubleshootingLinks }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.schema.SchemaOf
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.io.{ ByteArrayOutputStream, PrintStream }
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

final case class Invoice(vendor: String, amount: Double, currency: String) derives SchemaOf, ReadWriter

/** The REPL helpers against `Session.offline`: what they return, and what they print (golden output). */
class ReplHelpersSpec extends AnyFlatSpec with Matchers {

  /** A session printing into a buffer, whose clock advances 5 ms each time it is read. */
  final private class Fixture(base: Session) {
    val buffer        = new ByteArrayOutputStream()
    private val ticks = new AtomicLong(0L)
    given session: Session =
      base.withOutput(new PrintStream(buffer, true, "UTF-8")).withClock(() => ticks.addAndGet(5.millis.toNanos))
    def printed: String = buffer.toString(StandardCharsets.UTF_8).replace("\r\n", "\n")
  }

  private val weather =
    ToolBuilder[Map[String, Any], ujson.Value](
      "get_weather",
      "The weather in a city",
      Schema.`object`[Map[String, Any]]("Arguments").withProperty(Schema.property("city", Schema.string("The city")))
    ).withHandler(args => args.getString("city").map(city => ujson.Obj("city" -> city, "temperature" -> 21)))
      .buildSafe()
      .fold(error => fail(error.message), identity)

  "ask" should "stream the answer, return it and record the exchange" in {
    val f = new Fixture(Session.offline(Reply.text("Covariance lets a List[Cat] be a List[Animal].")))
    import f.given

    val answer = ask("Explain covariance")

    answer shouldBe Outcome.Done("Covariance lets a List[Cat] be a List[Animal].")
    answer.toString shouldBe "Covariance lets a List[Cat] be a List[Animal]."
    f.printed shouldBe "Covariance lets a List[Cat] be a List[Animal].\n"
    history.toString shouldBe
      """user: Explain covariance
        |assistant: Covariance lets a List[Cat] be a List[Animal].""".stripMargin
  }

  it should "print the streamed pieces in the order they arrive" in {
    val pieces = ArrayBuffer.empty[String]
    val out = new PrintStream(new ByteArrayOutputStream()) {
      override def print(s: String): Unit = pieces += s
      override def println(): Unit        = pieces += "\n"
    }
    given Session =
      Session.offline(ScriptedLLMClient.sequence(Reply.text("abcdefgh")).withChunkSize(3)).withOutput(out)

    ask("letters") shouldBe Outcome.Done("abcdefgh")
    pieces.toList shouldBe List("abc", "def", "gh", "\n")
  }

  it should "send the conversation so far with the next question" in {
    val client = ScriptedLLMClient.sequence(Reply.text("Paris."), Reply.text("About 2 million."))
    val f      = new Fixture(Session.offline(client))
    import f.given

    ask("Capital of France?")
    ask("Its population?")

    client.calls(1).conversation.messages.map(_.content) shouldBe Seq("Capital of France?", "Paris.", "Its population?")
    history.size shouldBe 4
    history.conversation.messages.last.content shouldBe "About 2 million."
  }

  it should "start every conversation with the session's system prompt, which is not in the history" in {
    val client = ScriptedLLMClient.sequence(Reply.text("Ahoy."))
    val f      = new Fixture(Session.offline(client).withSystemPrompt("Talk like a pirate."))
    import f.given

    ask("Hello")

    client.calls.head.conversation.messages.head shouldBe SystemMessage("Talk like a pirate.")
    history.messages.map(_.role) shouldBe Seq(MessageRole.User, MessageRole.Assistant)
  }

  it should "send the session's completion options" in {
    val client = ScriptedLLMClient.sequence(Reply.text("ok"))
    val f      = new Fixture(Session.offline(client).withOptions(CompletionOptions().withMaxTokens(42)))
    import f.given

    ask("Hi")

    client.calls.head.options.maxTokens shouldBe Some(42)
  }

  it should "print the answer of a client that does not stream text" in {
    val f = new Fixture(Session.of(new FixedClient(completion("Not streamed."))))
    import f.given

    ask("Hi") shouldBe Outcome.Done("Not streamed.")
    f.printed shouldBe "Not streamed.\n"
  }

  it should "print a failure's kind and hint, without a stack trace, and keep the history unchanged" in {
    val f = new Fixture(Session.offline(Reply.failure(AuthenticationError("openai", "Invalid API key"))))
    import f.given

    val answer = ask("Hi")

    answer.isFailure shouldBe true
    answer.failure.map(_.getClass.getSimpleName) shouldBe Some("AuthenticationError")
    val line = f.printed
    line should startWith("error: AuthenticationError")
    line should include("Invalid API key")
    line should include(TroubleshootingLinks.AuthenticationAction)
    line should include(TroubleshootingLinks.PageUrl)
    (line should not).include("\tat ")
    (line should not).include("Exception")
    answer.toString shouldBe line.stripLineEnd
    history.size shouldBe 0
    lastUsage shouldBe None
  }

  it should "end a half-streamed answer's line before printing the failure" in {
    val client = new FixedClient(completion("unused")) {
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = {
        onChunk(StreamedChunk("1", Some("partial")))
        Left(AuthenticationError("openai", "revoked"))
      }
    }
    val f = new Fixture(Session.of(client))
    import f.given

    ask("Hi")

    f.printed.linesIterator.toList.head shouldBe "partial"
    f.printed.linesIterator.toList(1) should startWith("error: AuthenticationError")
  }

  it should "run tools, showing each call and result inline" in {
    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("get_weather", """{"city":"Paris"}"""),
      Reply.text("It is 21 degrees in Paris.")
    )
    val f = new Fixture(Session.offline(client))
    import f.given

    ask("Weather in Paris?", tools = new ToolRegistry(Seq(weather))) shouldBe Outcome.Done("It is 21 degrees in Paris.")

    f.printed shouldBe
      """-> get_weather({"city":"Paris"})
        |<- {"city":"Paris","temperature":21}
        |It is 21 degrees in Paris.
        |""".stripMargin
    history.toString shouldBe
      """user: Weather in Paris?
        |assistant: -> get_weather({"city":"Paris"})
        |tool: <- {"city":"Paris","temperature":21}
        |assistant: It is 21 degrees in Paris.""".stripMargin
    client.calls.head.options.tools.map(_.name) shouldBe Seq("get_weather")
  }

  it should "show a failing tool call and send the error back to the model" in {
    val client = ScriptedLLMClient.sequence(Reply.toolCall("no_such_tool"), Reply.text("Sorry."))
    val f      = new Fixture(Session.offline(client))
    import f.given

    ask("Do it", tools = new ToolRegistry(Seq(weather))) shouldBe Outcome.Done("Sorry.")

    f.printed.linesIterator.toList(1) should startWith("<- error: Tool call 'no_such_tool'")
    client.calls(1).conversation.messages.last.content should include("\"error\"")
  }

  it should "stop when the model keeps calling tools past the session's limit" in {
    val f = new Fixture(
      Session
        .offline(Reply.toolCall("get_weather", """{"city":"Oslo"}"""), Reply.toolCall("get_weather"))
        .withMaxToolSteps(1)
    )
    import f.given

    val answer = ask("Weather?", tools = new ToolRegistry(Seq(weather)))

    answer.isFailure shouldBe true
    f.printed should include("still calling tools after 1 model calls")
    history.size shouldBe 0
  }

  it should "show, not run, a tool call when no tools were given" in {
    val f = new Fixture(Session.offline(Reply.toolCall("get_weather", """{"city":"Rome"}""")))
    import f.given

    ask("Weather?") shouldBe Outcome.Done("")
    f.printed shouldBe "-> get_weather({\"city\":\"Rome\"})\n"
  }

  "extract" should "return the typed value and leave the history alone" in {
    val client = ScriptedLLMClient.sequence(Reply.text("""{"vendor":"ACME","amount":12.5,"currency":"EUR"}"""))
    val f      = new Fixture(Session.offline(client))
    import f.given

    val invoice = extract[Invoice]("ACME, 12.50 EUR, two widgets")

    invoice shouldBe Outcome.Done(Invoice("ACME", 12.5, "EUR"))
    invoice.toString shouldBe "Invoice(ACME,12.5,EUR)"
    f.printed shouldBe ""
    history.size shouldBe 0
    client.calls.head.conversation.messages.map(_.content) shouldBe Seq(
      Engine.ExtractPrompt,
      "ACME, 12.50 EUR, two widgets"
    )
    lastUsage.map(_.model) shouldBe Some(Reply.DefaultModel)
  }

  it should "print a reply that does not match the type" in {
    val f = new Fixture(Session.offline(Reply.text("""{"vendor":"ACME"}""")))
    import f.given

    val invoice = extract[Invoice]("ACME")

    invoice.isFailure shouldBe true
    invoice.toOption shouldBe None
    f.printed should startWith("error: ValidationError")
  }

  "history" should "print the transcript with show, and say when there is none" in {
    val f = new Fixture(Session.offline(Reply.text("Hello!")))
    import f.given

    history.toString shouldBe "(no messages yet)"
    ask("Hi")
    f.buffer.reset()
    history.show
    f.printed shouldBe "user: Hi\nassistant: Hello!\n"
  }

  "lastUsage" should "give the model, tokens, latency and cost of the last call" in {
    val f = new Fixture(
      Session.offline(Reply.text("one").withUsage(TokenUsage(12, 8, 20)), Reply.text("two").withModel("other-model"))
    )
    import f.given

    lastUsage shouldBe None
    ask("first")
    lastUsage.map(_.toString) shouldBe Some("scripted-model: 12 prompt + 8 completion = 20 tokens, 5 ms, cost unknown")
    ask("second")
    lastUsage.map(_.toString) shouldBe Some("other-model: tokens not reported, 5 ms, cost unknown")
  }

  it should "show the estimated cost and the thinking and cache counts the provider reports" in {
    val usage =
      TokenUsage(100, 50, 150, thinkingTokens = Some(10), cachedTokens = Some(40), cacheCreationTokens = Some(5))
    val f = new Fixture(Session.of(new FixedClient(completion("ok").withUsage(usage).withEstimatedCost(0.00012))))
    import f.given

    ask("Hi")

    val stats = lastUsage.getOrElse(fail("no stats"))
    stats.latency shouldBe 5.millis
    stats.estimatedCost shouldBe Some(0.00012)
    stats.usage shouldBe Some(usage)
    stats.toString shouldBe
      "test-model: 100 prompt + 50 completion = 150 tokens (10 thinking, 40 cached, 5 written to cache), 5 ms, $0.000120"
  }

  "strict" should "return a Result and print no failure" in {
    val f = new Fixture(
      Session.offline(
        Reply.text("Fine."),
        Reply.failure(AuthenticationError("openai", "Invalid API key")),
        Reply.text("""{"vendor":"ACME","amount":1.0,"currency":"USD"}""")
      )
    )
    import f.given

    strict.ask("How are you?") shouldBe Right("Fine.")
    strict.ask("Again?").left.map(_.getClass.getSimpleName) shouldBe Left("AuthenticationError")
    strict.extract[Invoice]("ACME, 1 USD") shouldBe Right(Invoice("ACME", 1.0, "USD"))
    f.printed shouldBe "Fine.\n"
  }

  "Outcome" should "give its value or a default" in {
    Outcome.Done(1).getOrElse(0) shouldBe 1
    Outcome.Done(1).toOption shouldBe Some(1)
    Outcome.Done(1).failure shouldBe None
    val failed: Outcome[Int] = Outcome.Failed(AuthenticationError("openai", "no"))
    failed.getOrElse(0) shouldBe 0
    failed.toResult.isLeft shouldBe true
  }

  "Session" should "describe itself, and forget the conversation on reset" in {
    val client = ScriptedLLMClient.sequence(Reply.text("one"))
    val f      = new Fixture(Session.offline(Reply.text("one")))
    import f.given

    summon[Session].toString shouldBe "Session(offline, 1 scripted reply, 0 messages)"
    ask("Hi")
    summon[Session].toString shouldBe "Session(offline, 1 scripted reply, 2 messages)"
    summon[Session].reset()
    history.size shouldBe 0
    lastUsage shouldBe None
    Session
      .offline(Reply.text("a"), Reply.text("b"))
      .toString shouldBe "Session(offline, 2 scripted replies, 0 messages)"
    Session.offline(client).description shouldBe "offline"
    Session.offline(client).failure shouldBe None
    summon[Session].maxToolSteps shouldBe Session.DefaultMaxToolSteps
    summon[Session].withMaxToolSteps(0).maxToolSteps shouldBe 1
  }

  it should "forward the client's limits and close it" in {
    val client  = new FixedClient(completion("ok"))
    val session = Session.of(client)
    val wrapped = session.client.fold(error => fail(error.message), identity)

    wrapped.getContextWindow() shouldBe 1000
    wrapped.getReserveCompletion() shouldBe 100
    wrapped.validate() shouldBe Right(())
    wrapped.providerId shouldBe None
    wrapped.complete(Conversation(Seq(UserMessage("Hi"))), CompletionOptions()).map(_.content) shouldBe Right("ok")
    session.close()
    client.closed shouldBe true
  }

  it should "print the configuration error of a session it could not connect" in {
    val f = new Fixture(Session.named("no-such-section"))
    import f.given

    summon[Session].failure should not be empty
    summon[Session].toString should include("not connected")
    ask("Hi").isFailure shouldBe true
    extract[Invoice]("ACME").isFailure shouldBe true
    f.printed.linesIterator.count(_.startsWith("error: ")) shouldBe 2
    (f.printed should not).include("\tat ")
  }

  "Session.fromConfig" should "give a session even when the configuration does not load" in {
    val session = Session.fromConfig()
    session.description should startWith("default provider")
  }

  private def completion(text: String): Completion =
    Completion("c1", Instant.EPOCH, text, "test-model", AssistantMessage(text))

  private class FixedClient(answer: Completion) extends LLMClient {
    @volatile var closed                                                                              = false
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = Right(answer)
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = Right(answer)
    override def getContextWindow(): Int     = 1000
    override def getReserveCompletion(): Int = 100
    override def close(): Unit               = closed = true
  }
}
