package org.llm4s.llmconnect.smoke

import org.llm4s.it.Tier
import org.llm4s.it.tags.Local
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ CohereConfig, ContextWindowResolver, DeepSeekConfig, OpenAIConfig }
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  ResponseFormat,
  StreamedChunk,
  TokenUsage,
  ToolCall
}
import org.llm4s.llmconnect.provider.{ CohereClient, DeepSeekClient, OpenAIClient, OpenRouterClient }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.{ Args, BeforeAndAfterAll, BeforeAndAfterEach, EitherValues, OptionValues, Reporter }
import org.scalatest.events.{ Event, TestCanceled, TestFailed, TestSucceeded }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * Proves the shared smoke contract without any provider (issue #1212).
 *
 * A contract that nobody can run is believable only if its checks are shown to work, so this runs the REAL
 * [[SmokeChecks]] through REAL clients against a local fake server (`FakeOpenAIServer`):
 *
 *  - against a well-behaved server every capability holds, through each client;
 *  - against a server that misbehaves in one way, exactly the capabilities that way breaks fail - each with a
 *    message that names the capability - and every other capability still holds, so one broken thing is not
 *    reported as several;
 *  - mixed into a spec, [[ProviderSmokeContract]] turns that into tests that pass, fail, are skipped without a key
 *    and are cancelled as not applicable, and records each outcome in the matrix.
 *
 * Tier `@Local`: it needs nothing outside the process, so it runs in the default `sbt test`.
 */
@Local
@scala.annotation.nowarn("msg=Could not verify")
class SmokeContractOfflineSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val server = new FakeOpenAIServer()

  override protected def afterAll(): Unit = {
    server.close()
    super.afterAll()
  }

  override protected def afterEach(): Unit = {
    server.behaviour = FakeBehaviour()
    super.afterEach()
  }

  private def deepSeek(): LLMClient =
    DeepSeekClient(DeepSeekConfig.fromValues("fake-model", "test-key", server.baseUrl).value).value

  private def openRouter(): LLMClient =
    new OpenRouterClient(OpenAIConfig.fromValues("fake-model", "test-key", None, server.baseUrl).value)

  private def cohere(): LLMClient =
    CohereClient(CohereConfig.fromValues("fake-model", "test-key", server.baseUrl).value).value

  private def openAI(): LLMClient =
    OpenAIClient(OpenAIConfig.fromValues("fake-model", "test-key", None, s"${server.baseUrl}/v1").value).value

  /**
   * The real clients the contract runs through, with how each reports reasoning: DeepSeek returns the thinking text
   * (`reasoning_content`), OpenAI only counts reasoning tokens, and the others are not exercised for reasoning here.
   */
  private val clients: Seq[(String, () => LLMClient, Option[Boolean])] = Seq(
    ("DeepSeekClient (OpenAICompatibleClient)", () => deepSeek(), Some(true)),
    ("OpenRouterClient (OpenAICompatibleClient)", () => openRouter(), None),
    ("CohereClient (OpenAICompatibleClient, `developer` role)", () => cohere(), None),
    ("OpenAIClient (openai-java SDK)", () => openAI(), Some(false))
  )

  /** Every capability except `Reasoning`, which needs a setup. */
  private val checked: Seq[Capability] = Capability.values.toSeq.filterNot(_ == Capability.Reasoning)

  private def reasoningSetup(client: LLMClient, expectsText: Boolean): ReasoningSetup =
    ReasoningSetup(client, CompletionOptions(temperature = 0.0, maxTokens = Some(64)), expectsText)

  private def failedMessage(outcome: Outcome): Option[String] = outcome match {
    case Outcome.Failed(message) => Some(message)
    case _                       => None
  }

  // ---- 1. a well-behaved server: every check passes, through every client ----

  clients.foreach { case (name, make, reasoning) =>
    "The contract" should s"hold every capability against a well-behaved server through $name" in {
      val client = make()
      checked.foreach { capability =>
        withClue(s"${capability.label} through $name: ") {
          SmokeChecks.run(capability, client) shouldBe Outcome.Held
        }
      }
      reasoning.foreach { expectsText =>
        withClue(s"reasoning through $name: ") {
          SmokeChecks.reasoning(reasoningSetup(client, expectsText)) shouldBe Outcome.Held
        }
      }
      client.close()
    }
  }

  // ---- 2. a server that misbehaves in one way: exactly those checks fail, naming the capability ----

  private val misbehaviours: Seq[(String, FakeBehaviour, Set[Capability])] = Seq(
    ("ignores the system message", FakeBehaviour(honourSystem = false), Set(Capability.SystemPrompt)),
    (
      "wraps the system message's one word in prose",
      FakeBehaviour(systemReplyExact = false),
      Set(Capability.SystemPrompt)
    ),
    ("forgets the earlier turns", FakeBehaviour(keepHistory = false), Set(Capability.MultiTurn)),
    (
      "drops the assistant turns from the history",
      FakeBehaviour(keepAssistantTurns = false),
      Set(Capability.MultiTurn)
    ),
    (
      "ignores the tool result it is sent back",
      FakeBehaviour(useToolResult = false),
      Set(Capability.ToolCalling)
    ),
    (
      "cuts the tool-call arguments off mid-object",
      FakeBehaviour(toolArgumentsValid = false),
      Set(Capability.ToolCalling, Capability.StreamedToolCalling)
    ),
    (
      "adds a property the tool does not declare to the tool-call arguments",
      FakeBehaviour(toolArgumentsInSchema = false),
      Set(Capability.ToolCalling, Capability.StreamedToolCalling)
    ),
    (
      "streams prose where a tool call belongs",
      FakeBehaviour(streamToolCalls = false),
      Set(Capability.StreamedToolCalling)
    ),
    (
      "reports no usage, on plain and streamed calls",
      FakeBehaviour(includeUsage = false),
      Set(Capability.Usage, Capability.StreamedUsage)
    ),
    (
      "ignores response_format and answers in prose",
      FakeBehaviour(honourResponseFormat = false),
      Set(Capability.StructuredOutput)
    ),
    (
      "answers a schema request with valid JSON that has the wrong values",
      FakeBehaviour(structuredHasRequestedValues = false),
      Set(Capability.StructuredOutput)
    ),
    (
      "answers a schema request with JSON of the wrong types",
      FakeBehaviour(structuredMatchesSchema = false),
      Set(Capability.StructuredOutput)
    ),
    (
      "answers a schema request with a property the schema does not declare",
      FakeBehaviour(structuredOnlyDeclared = false),
      Set(Capability.StructuredOutput)
    ),
    (
      "answers a schema request with the JSON wrapped in prose",
      FakeBehaviour(structuredBare = false),
      Set(Capability.StructuredOutput)
    )
  )

  misbehaviours.foreach { case (description, behaviour, broken) =>
    it should s"fail exactly ${broken.map(_.label).toSeq.sorted.mkString(" and ")} for a server that $description" in {
      server.behaviour = behaviour
      val client = deepSeek()
      checked.foreach { capability =>
        val outcome = SmokeChecks.run(capability, client)
        if (broken.contains(capability))
          withClue(s"${capability.label} should fail for a server that $description: ") {
            failedMessage(outcome).value should startWith(s"[${capability.label}]")
          }
        else
          withClue(s"${capability.label} should be unaffected by a server that $description: ") {
            outcome shouldBe Outcome.Held
          }
      }
      client.close()
    }
  }

  it should "hold streamed usage for a client that asks for it when the server only reports it to clients that ask" in {
    server.behaviour = FakeBehaviour(streamUsageNeedsOptIn = true)
    val client = deepSeek()
    SmokeChecks.run(Capability.StreamedUsage, client) shouldBe Outcome.Held
    client.close()
  }

  it should "fail streamed usage for a client that never asks, against a server that only reports it to clients that ask" in {
    server.behaviour = FakeBehaviour(streamUsageNeedsOptIn = true)
    // OpenRouterClient does not send stream_options.include_usage (OpenRouter reports usage unprompted), so against
    // an opt-in server it must be reported, not passed.
    val client = openRouter()
    failedMessage(SmokeChecks.run(Capability.StreamedUsage, client)).value should startWith("[streamed usage]")
    client.close()
  }

  it should "fail Reasoning when the provider returns no thinking text but the spec expects it" in {
    server.behaviour = FakeBehaviour(reasoningContent = false)
    val client  = deepSeek()
    val outcome = SmokeChecks.reasoning(reasoningSetup(client, expectsText = true))
    failedMessage(outcome).value should startWith("[reasoning]")
    client.close()
  }

  it should "fail Reasoning when a spec expecting only a token count gets neither thinking text nor tokens" in {
    server.behaviour = FakeBehaviour(reasoningContent = false)
    val client = deepSeek()
    // The fake reports no thinking tokens either, so with nothing to count this must fail: a check that passes
    // on neither kind of evidence would be vacuous.
    failedMessage(SmokeChecks.reasoning(reasoningSetup(client, expectsText = false))).value should startWith(
      "[reasoning]"
    )
    client.close()
  }

  it should "report a failed call, not throw, when the server is gone" in {
    val gone = new FakeOpenAIServer()
    val base = gone.baseUrl
    gone.close()
    val client = DeepSeekClient(DeepSeekConfig.fromValues("fake-model", "test-key", base).value).value
    failedMessage(SmokeChecks.run(Capability.SystemPrompt, client)).value should startWith("[system message]")
    client.close()
  }

  // ---- 2b. shapes a real client never produces, from stub clients ----
  //
  // The real clients fix some things themselves (they give a streamed continuation its call's id, and they compute
  // a usage total), so the fake server cannot make those checks fail. A stub client can, which shows the checks
  // would catch a client that regressed.

  private def completion(
    content: String,
    toolCalls: List[ToolCall] = Nil,
    usage: Option[TokenUsage] = None
  ): Completion =
    Completion(
      id = "stub",
      created = 0L,
      content = content,
      model = "stub",
      message = AssistantMessage(contentOpt = Some(content), toolCalls = toolCalls),
      toolCalls = toolCalls,
      usage = usage
    )

  private def stub(
    onComplete: (Conversation, CompletionOptions) => Result[Completion] = (_, _) => Right(completion("stub")),
    onStream: (Conversation, CompletionOptions, StreamedChunk => Unit) => Result[Completion] = (_, _, _) =>
      Right(completion("stub"))
  ): LLMClient = new LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      onComplete(conversation, options)
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = onStream(conversation, options, onChunk)
    override def getContextWindow(): Int     = 8192
    override def getReserveCompletion(): Int = 1024
  }

  private val vaultCall = ujson.Obj("topic" -> "vault")

  it should "fail a streamed tool call whose chunk arrives without its call's id" in {
    val client = stub(onStream = (_, _, onChunk) => {
      onChunk(StreamedChunk(id = "s", content = None, toolCall = Some(ToolCall("", "get_secret_code", vaultCall))))
      Right(completion("", List(ToolCall("call_1", "get_secret_code", vaultCall))))
    })
    failedMessage(SmokeChecks.run(Capability.StreamedToolCalling, client)).value should (
      startWith("[streamed tool call]").and(include("without their call's id"))
    )
  }

  it should "hold a streamed tool call whose chunks all carry an id" in {
    val client = stub(onStream = (_, _, onChunk) => {
      onChunk(
        StreamedChunk(id = "s", content = None, toolCall = Some(ToolCall("call_1", "get_secret_code", vaultCall)))
      )
      Right(completion("", List(ToolCall("call_1", "get_secret_code", vaultCall))))
    })
    SmokeChecks.run(Capability.StreamedToolCalling, client) shouldBe Outcome.Held
  }

  it should "fail a streamed tool call whose chunks do not reassemble, even when the completion is right" in {
    // A client that builds the right completion but hands `onChunk` fragments a consumer cannot put back together
    // (here, `":"` parsed to the bare string `:`).
    val client = stub(onStream = (_, _, onChunk) => {
      Seq[ujson.Value](ujson.Str("{\"topic"), ujson.Str(":"), ujson.Str("\"vault\"}")).foreach { fragment =>
        onChunk(
          StreamedChunk(id = "s", content = None, toolCall = Some(ToolCall("call_1", "get_secret_code", fragment)))
        )
      }
      Right(completion("", List(ToolCall("call_1", "get_secret_code", vaultCall))))
    })
    failedMessage(SmokeChecks.run(Capability.StreamedToolCalling, client)).value should (
      startWith("[streamed tool call]").and(include("reassembled from the streamed chunks"))
    )
  }

  it should "fail a tool call that names the wrong tool, has an empty id, or has arguments that do not fit" in {
    def toolClient(call: ToolCall): LLMClient = stub(onComplete = (_, _) => Right(completion("", List(call))))
    failedMessage(
      SmokeChecks.run(Capability.ToolCalling, toolClient(ToolCall("call_1", "other_tool", vaultCall)))
    ).value should include("instead of 'get_secret_code'")
    failedMessage(
      SmokeChecks.run(Capability.ToolCalling, toolClient(ToolCall("", "get_secret_code", vaultCall)))
    ).value should include("empty id")
    failedMessage(
      SmokeChecks.run(Capability.ToolCalling, toolClient(ToolCall("call_1", "get_secret_code", ujson.Obj("nope" -> 1))))
    ).value should include("does not declare nope")
    failedMessage(
      SmokeChecks.run(Capability.ToolCalling, toolClient(ToolCall("call_1", "get_secret_code", ujson.Obj())))
    ).value should include("do not fit the tool's schema")
    failedMessage(
      SmokeChecks.run(Capability.ToolCalling, toolClient(ToolCall("call_1", "get_secret_code", ujson.Arr())))
    ).value should include("not a JSON object")
    failedMessage(
      SmokeChecks.run(Capability.ToolCalling, stub(onComplete = (_, _) => Right(completion("I will not use it"))))
    ).value should include("did not call the tool")
  }

  it should "leave the structured-output shape to the response format: the prompt does not ask for JSON" in {
    (SmokeChecks.StructuredPrompt.toLowerCase should not).include("json")
  }

  it should "send the schema as the response format and judge the raw reply against it" in {
    var sent: Option[ResponseFormat] = None
    def structuredClient(reply: String): LLMClient = stub(onComplete = (_, options) => {
      sent = options.responseFormat
      Right(completion(reply))
    })
    def structured(reply: String): Outcome = SmokeChecks.run(Capability.StructuredOutput, structuredClient(reply))

    structured("""{"color":"blue","count":3}""") shouldBe Outcome.Held
    sent match {
      case Some(ResponseFormat.JsonSchema(schema, _, _)) =>
        schema("properties").obj.keySet shouldBe Set("color", "count")
      case other => fail(s"the check sent $other, not the schema")
    }
    structured("```json\n{\"color\":\"blue\",\"count\":3}\n```") shouldBe Outcome.Held
    failedMessage(structured("""{"color":"blue","count":3,"extra":true}""")).value should
      include("does not declare extra")
    failedMessage(structured("""{"color":"blue"}""")).value should include("requires count")
    failedMessage(structured("""Sure: {"color":"blue","count":3}""")).value should include("not a JSON document")
    failedMessage(structured("""[{"color":"blue","count":3}]""")).value should include("not an object")
    failedMessage(structured("""{"color":"blue","count":"3"}""")).value should include("schema's types")
  }

  it should "judge usage by positivity and by total >= prompt + completion" in {
    def usageClient(usage: Option[TokenUsage]): LLMClient =
      stub(onComplete = (_, _) => Right(completion("Hi", usage = usage)))
    def usageOutcome(usage: Option[TokenUsage]): Outcome = SmokeChecks.run(Capability.Usage, usageClient(usage))
    failedMessage(
      usageOutcome(Some(TokenUsage(promptTokens = 10, completionTokens = 10, totalTokens = 5)))
    ).value should
      include("below prompt + completion")
    failedMessage(usageOutcome(Some(TokenUsage(promptTokens = 0, completionTokens = 0, totalTokens = 0)))).value should
      include("not positive")
    failedMessage(usageOutcome(None)).value should include("no usage")
    usageOutcome(Some(TokenUsage(promptTokens = 10, completionTokens = 10, totalTokens = 20))) shouldBe Outcome.Held
    // A total above the sum is allowed: providers add thinking tokens to it.
    usageOutcome(Some(TokenUsage(promptTokens = 10, completionTokens = 10, totalTokens = 25))) shouldBe Outcome.Held
  }

  it should "accept reasoning evidenced by a token count alone when the spec expects no text, and an empty answer fails" in {
    def reasoningClient(content: String, usage: Option[TokenUsage]): LLMClient =
      stub(onComplete = (_, _) => Right(completion(content, usage = usage)))
    val options = CompletionOptions()
    val counted = Some(TokenUsage(promptTokens = 5, completionTokens = 5, totalTokens = 10, thinkingTokens = Some(4)))
    SmokeChecks.reasoning(ReasoningSetup(reasoningClient("Hi", counted), options, expectsThinkingText = false)) shouldBe
      Outcome.Held
    val uncounted = Some(TokenUsage(promptTokens = 5, completionTokens = 5, totalTokens = 10, thinkingTokens = Some(0)))
    failedMessage(
      SmokeChecks.reasoning(ReasoningSetup(reasoningClient("Hi", uncounted), options, expectsThinkingText = false))
    ).value should include("reasoning")
    failedMessage(
      SmokeChecks.reasoning(ReasoningSetup(reasoningClient("", counted), options, expectsThinkingText = false))
    ).value should include("answer was empty")
  }

  // ---- 3. the contract mixed into a spec: pass, fail, skip, not applicable, and the matrix ----

  private def runSpec(spec: org.scalatest.Suite): Seq[Event] = {
    val events = ListBuffer.empty[Event]
    val reporter = new Reporter {
      override def apply(event: Event): Unit = { events += event; () }
    }
    spec.run(None, Args(reporter))
    events.toSeq
  }

  /** The events of the one test that registers `capability` ("... hold the <label> capability", exactly). */
  private def named(events: Seq[Event], capability: Capability): Seq[Event] = {
    val suffix = s"hold the ${capability.label} capability"
    events.filter {
      case e: TestSucceeded => e.testName.endsWith(suffix)
      case e: TestFailed    => e.testName.endsWith(suffix)
      case e: TestCanceled  => e.testName.endsWith(suffix)
      case _                => false
    }
  }

  /** A spec with the contract mixed in, recording into `into` (a matrix of its own) and printing nothing. */
  private def wiredSpec(
    label: String,
    providedKey: Option[String],
    build: String => Result[LLMClient],
    into: SmokeMatrix,
    notApplicableCapabilities: Map[Capability, String] = Map.empty
  ): org.scalatest.Suite = {
    class Wired extends AnyFlatSpec with Matchers with ProviderSmokeContract {
      override protected def matrix: SmokeMatrix                               = into
      override protected def announceMatrix: Boolean                           = false
      override protected def providerLabel: String                             = label
      override protected def apiKeyEnvVar: String                              = "OFFLINE_FAKE_KEY"
      override protected def contractKey: Option[String]                       = providedKey
      override protected def contractClient(apiKey: String): Result[LLMClient] = build(apiKey)
      override protected def notApplicable: Map[Capability, String]            = notApplicableCapabilities
      registerCapabilityContract()
    }
    new Wired
  }

  "ProviderSmokeContract" should "turn every capability into a passing test against a well-behaved server" in {
    val matrix = new SmokeMatrix
    val events = runSpec(wiredSpec("pass", Some("k"), _ => Right(deepSeek()), matrix))
    events.collect { case e: TestFailed => e.message } shouldBe empty
    checked.foreach { capability =>
      withClue(s"${capability.label}: ") {
        named(events, capability).collect { case e: TestSucceeded => e } should have size 1
        matrix.snapshot(("pass", capability)) shouldBe Outcome.Held
      }
    }
  }

  it should "make Reasoning not applicable by default, cancelled with the reason and shown as n/a" in {
    val matrix = new SmokeMatrix
    val events = runSpec(wiredSpec("default-na", Some("k"), _ => Right(deepSeek()), matrix))
    named(events, Capability.Reasoning).collect { case e: TestCanceled => e.message } match {
      case Seq(message) => message should include("no reasoning-capable model")
      case other        => fail(s"expected one cancelled Reasoning test, got $other")
    }
    matrix.snapshot(("default-na", Capability.Reasoning)) shouldBe a[Outcome.NotApplicable]
  }

  it should "cancel a capability the spec declares not applicable, with its reason, without running it" in {
    server.behaviour = FakeBehaviour(honourResponseFormat = false)
    val matrix = new SmokeMatrix
    val events = runSpec(
      wiredSpec(
        "declared-na",
        Some("k"),
        _ => Right(deepSeek()),
        matrix,
        Map(Capability.StructuredOutput -> "no JSON-schema support")
      )
    )
    // The server would fail it; declaring it not applicable must keep it from running at all.
    events.collect { case e: TestFailed => e.message } shouldBe empty
    named(events, Capability.StructuredOutput).collect { case e: TestCanceled => e.message } match {
      case Seq(message) => message should include("no JSON-schema support")
      case other        => fail(s"expected one cancelled test, got $other")
    }
    matrix.snapshot(("declared-na", Capability.StructuredOutput)) shouldBe
      Outcome.NotApplicable("no JSON-schema support")
  }

  it should "fail the one capability a server breaks, naming it, and record it in the matrix" in {
    server.behaviour = FakeBehaviour(honourSystem = false)
    val matrix   = new SmokeMatrix
    val events   = runSpec(wiredSpec("fail", Some("k"), _ => Right(deepSeek()), matrix))
    val failures = events.collect { case e: TestFailed => e }
    failures should have size 1
    failures.head.message should startWith("[system message]")
    matrix.snapshot(("fail", Capability.SystemPrompt)) shouldBe a[Outcome.Failed]
    matrix.snapshot(("fail", Capability.Usage)) shouldBe Outcome.Held
  }

  it should "skip every applicable capability without a key (or fail it under LLM4S_IT_STRICT) and say skipped" in {
    val matrix = new SmokeMatrix
    val events = runSpec(wiredSpec("keyless", None, _ => Right(deepSeek()), matrix))
    events.collect { case e: TestSucceeded => e } shouldBe empty
    checked.foreach { capability =>
      val result = named(events, capability)
      if (Tier.strict) result.collect { case e: TestFailed => e } should have size 1
      else result.collect { case e: TestCanceled => e } should have size 1
      matrix.snapshot(("keyless", capability)) shouldBe Outcome.Skipped("OFFLINE_FAKE_KEY not set")
    }
    matrix.renderProvider("keyless") should include("skipped")
  }

  it should "fail, not throw, when the client cannot be built" in {
    val events = runSpec(
      wiredSpec(
        "nobuild",
        Some("k"),
        _ => Left(org.llm4s.error.ConfigurationError("bad config")),
        new SmokeMatrix
      )
    )
    val failures = events.collect { case e: TestFailed => e.message }
    failures should have size checked.size
    failures.foreach(_ should include("could not build the client: bad config"))
  }

  it should "leave the shared matrix, which is the one that is printed, untouched" in {
    val used = Set("pass", "default-na", "declared-na", "fail", "keyless", "nobuild")
    SmokeMatrix.shared.snapshot.keys.map(_._1).filter(used.contains) shouldBe empty
  }

  // ---- 4. the matrix rendering ----

  "SmokeMatrix.render" should "show held, FAILED, n/a and skipped, with a note for every FAILED and n/a cell" in {
    val cells: Map[(String, Capability), Outcome] = Map(
      ("alpha", Capability.SystemPrompt)    -> Outcome.Held,
      ("alpha", Capability.ToolCalling)     -> Outcome.Failed("[tool call] it called nothing"),
      ("alpha", Capability.Reasoning)       -> Outcome.NotApplicable("no reasoning model"),
      ("beta", Capability.SystemPrompt)     -> Outcome.Skipped("BETA_KEY not set"),
      ("beta", Capability.StructuredOutput) -> Outcome.Held
    )
    val text  = SmokeMatrix.render(cells)
    val lines = text.linesIterator.toSeq
    lines.head should (include("provider").and(include("system message")).and(include("reasoning")))
    // One row per provider, with the provider's own cells in the capability's column.
    val alpha = lines.find(_.startsWith("alpha")).value
    alpha should (include("held").and(include("FAILED")).and(include("n/a")))
    val beta = lines.find(_.startsWith("beta")).value
    beta should (include("skipped").and(include("held")))
    (beta should not).include("FAILED")
    text should include("FAILED  alpha / tool call: [tool call] it called nothing")
    text should include("n/a     alpha / reasoning: no reasoning model")
    // A skipped cell needs no note: the key is the same for every capability of that provider.
    (text should not).include("BETA_KEY")
  }

  it should "say so when nothing was recorded" in {
    SmokeMatrix.render(Map.empty) shouldBe "(no capability outcomes recorded)"
  }
}
