package org.llm4s.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.error.{ APIError, ValidationError }
import upickle.default._

import scala.collection.mutable.ArrayBuffer

/**
 * Comprehensive tests for the Agent, which runs on the graph runtime.
 * Uses a mock LLMClient to test agent behavior without actual LLM calls.
 */
class AgentSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // Mock LLMClient for testing
  // ==========================================================================

  /**
   * Mock LLMClient that returns pre-configured responses.
   * Can be configured to return specific completions or errors.
   */
  class MockLLMClient(
    responses: Seq[Result[Completion]] = Seq.empty,
    contextWindow: Int = 4096,
    reserveCompletion: Int = 1024
  ) extends LLMClient {

    private var callIndex = 0
    private val _calls    = ArrayBuffer[(Conversation, CompletionOptions)]()

    def calls: Seq[(Conversation, CompletionOptions)] = _calls.toSeq
    def callCount: Int                                = _calls.size

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      _calls += ((conversation, options))
      val result = if (callIndex < responses.size) {
        responses(callIndex)
      } else if (responses.nonEmpty) {
        responses.last // Repeat last response if we run out
      } else {
        // Default response - simple text completion
        Right(createCompletion("Default response"))
      }
      callIndex += 1
      result
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      // For streaming, return same as complete but emit chunks
      val result = complete(conversation, options)
      result.foreach { completion =>
        // Emit content as a single chunk
        if (completion.content.nonEmpty) {
          onChunk(
            StreamedChunk(
              id = completion.id,
              content = Some(completion.content)
            )
          )
        }
      }
      result
    }

    override def getContextWindow(): Int     = contextWindow
    override def getReserveCompletion(): Int = reserveCompletion
  }

  // ==========================================================================
  // Helper methods
  // ==========================================================================

  private def createCompletion(
    content: String,
    toolCalls: Seq[ToolCall] = Seq.empty
  ): Completion = {
    val message = AssistantMessage(content, toolCalls)
    Completion(
      id = s"test-completion-${System.nanoTime()}",
      created = System.currentTimeMillis(),
      content = content,
      model = "test-model",
      message = message,
      toolCalls = toolCalls.toList,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
    )
  }

  private def createToolCall(name: String, arguments: String, id: String = "call_123"): ToolCall =
    ToolCall(id = id, name = name, arguments = ujson.read(arguments))

  // Result type for test tool
  case class CalculatorResult(result: Double)
  object CalculatorResult {
    implicit val rw: ReadWriter[CalculatorResult] = macroRW
  }

  private def createCalculatorTool(): Result[ToolFunction[Map[String, Any], CalculatorResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Calculator parameters")
      .withRequiredField("a", Schema.number("First number"))
      .withRequiredField("b", Schema.number("Second number"))
      .withRequiredField("operation", Schema.string("Operation: add, subtract, multiply, divide"))

    ToolBuilder[Map[String, Any], CalculatorResult](
      "calculator",
      "Performs basic arithmetic",
      schema
    ).withHandler { extractor =>
      for {
        a  <- extractor.getDouble("a")
        b  <- extractor.getDouble("b")
        op <- extractor.getString("operation")
      } yield {
        val result = op match {
          case "add"      => a + b
          case "subtract" => a - b
          case "multiply" => a * b
          case "divide"   => if (b != 0) a / b else Double.NaN
          case _          => Double.NaN
        }
        CalculatorResult(result)
      }
    }.buildSafe()
  }

  private val calculatorTool = createCalculatorTool()
  private val testTools      = calculatorTool.map(tool => new ToolRegistry(Seq(tool)))

  // ==========================================================================
  // What the model is given
  // ==========================================================================

  "Agent.run" should "start the conversation with the query as the first user message" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      thread <- agent.run("What is 2+2?", tools)
    } yield {
      thread.messages.head shouldBe a[UserMessage]
      thread.messages.head.content shouldBe "What is 2+2?"
      // the conversation the model saw: the system message, then the query
      val sent = mockClient.calls.head._1.messages
      sent.map(_.role) shouldBe Seq(MessageRole.System, MessageRole.User)
      sent.last.content shouldBe "What is 2+2?"
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "give the model a system message, held on the thread rather than among its messages" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      thread <- agent.run("Test query", tools)
    } yield {
      thread.systemMessage.map(_.content).getOrElse(fail("Expected a system message")) should include(
        "helpful assistant"
      )
      thread.messages.exists(_.role == MessageRole.System) shouldBe false
      mockClient.calls.head._1.messages.head.content should include("helpful assistant")
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "append the system prompt addition" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      thread <- agent.run("Test query", tools, systemPromptAddition = Some("Always respond in JSON format."))
    } yield {
      thread.systemMessage.map(_.content).getOrElse(fail("Expected a system message")) should include("JSON format")
      mockClient.calls.head._1.messages.head.content should include("JSON format")
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "offer its tools to the model through the completion options" in {
    val mockClient = new MockLLMClient(Seq(Right(createCompletion("Done"))))
    val agent      = new Agent(mockClient)

    val result = for {
      tools <- testTools
      _     <- agent.run("Test", tools)
    } yield {
      mockClient.callCount shouldBe 1
      val (_, options) = mockClient.calls.head
      options.tools should have size 1
      options.tools.head.name shouldBe "calculator"
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "offer a handoff tool for each handoff, next to the registry's tools" in {
    val handoff    = Handoff("target", new Agent(new MockLLMClient()), Some("For complex math"))
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val result = for {
      tools <- testTools
      _     <- agent.run("Test", tools, handoffs = Seq(handoff))
    } yield {
      val offered = mockClient.calls.head._2.tools.map(_.name)
      offered.size shouldBe 2
      offered should contain("calculator")
      offered.exists(_.startsWith("handoff_to_")) shouldBe true
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "forward the completion options and keep them on the thread" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val options = CompletionOptions(temperature = 0.5, maxTokens = Some(100))
    val result = for {
      tools  <- testTools
      thread <- agent.run("Test", tools, completionOptions = options)
    } yield {
      thread.completionOptions.temperature shouldBe 0.5
      thread.completionOptions.maxTokens shouldBe Some(100)
      val (_, sent) = mockClient.calls.head
      sent.temperature shouldBe 0.5
      sent.maxTokens shouldBe Some(100)
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "return the error of a model that fails, as it was returned" in {
    val error      = APIError("provider", "API key invalid", None, None)
    val mockClient = new MockLLMClient(Seq(Left(error)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      thread <- agent.run("Test", tools)
    } yield thread
    result shouldBe Left(error)
  }

  it should "hand a tool failure back to the model and carry on" in {
    // a call to a tool that does not exist
    val toolCall   = createToolCall("nonexistent_tool", """{"param": "value"}""")
    val completion = createCompletion("Calling tool.", Seq(toolCall))
    val mockClient = new MockLLMClient(Seq(Right(completion), Right(createCompletion("Recovered"))))
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      thread <- agent.run("Test", tools)
    } yield {
      val toolMessages = thread.messages.collect { case m: ToolMessage => m }
      toolMessages should have size 1
      toolMessages.head.content should include("error")
      toolMessages.head.content should include("nonexistent_tool")
      thread.status shouldBe ThreadStatus.Completed
      thread.answer shouldBe Some("Recovered")
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  // ==========================================================================
  // Run Tests (full execution)
  // ==========================================================================

  it should "execute until completion" in {
    val completion = createCompletion("The answer is 4.")
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools =>
        agent
          .run("What is 2+2?", tools)
          .fold(
            e => fail(s"Agent run failed: ${e.formatted}"),
            thread => {
              thread.status shouldBe ThreadStatus.Completed
              thread.messages.last.content shouldBe "The answer is 4."
              thread.answer shouldBe Some("The answer is 4.")
            }
          )
    )
  }

  it should "execute tools and continue to completion" in {
    val toolCall      = createToolCall("calculator", """{"a": 2, "b": 2, "operation": "add"}""")
    val firstResponse = createCompletion("Let me calculate.", Seq(toolCall))
    val finalResponse = createCompletion("The answer is 4.")

    val mockClient = new MockLLMClient(Seq(Right(firstResponse), Right(finalResponse)))
    val agent      = new Agent(mockClient)

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools =>
        agent
          .run("What is 2+2?", tools)
          .fold(
            e => fail(s"Agent run failed: ${e.formatted}"),
            thread => {
              thread.status shouldBe ThreadStatus.Completed
              mockClient.callCount shouldBe 2
              val toolMessages = thread.messages.collect { case m: ToolMessage => m }
              toolMessages should have size 1
              toolMessages.head.content should include("4") // Result of 2+2
            }
          )
    )
  }

  it should "respect the maxSteps limit" in {
    // Agent that always requests tool calls - never completes naturally
    val toolCall   = createToolCall("calculator", """{"a": 1, "b": 1, "operation": "add"}""")
    val response   = createCompletion("Calculating...", Seq(toolCall))
    val mockClient = new MockLLMClient(Seq(Right(response)))
    val agent      = new Agent(mockClient)

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools =>
        agent
          .run("Loop forever", tools, maxSteps = Some(2))
          .fold(
            e => fail(s"Agent run failed: ${e.formatted}"),
            finalThread => {
              finalThread.status shouldBe a[ThreadStatus.Failed]
              finalThread.status.asInstanceOf[ThreadStatus.Failed].error should include("step limit")
              // maxSteps is a number of model calls: two were made, the third was refused
              mockClient.callCount shouldBe 2
            }
          )
    )
  }

  it should "validate input with guardrails" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    // Create a simple length check guardrail
    val lengthGuardrail = new InputGuardrail {
      def name: String = "length-check"
      def validate(input: String): Result[String] =
        if (input.length < 3) Left(ValidationError("input", "Input too short"))
        else Right(input)
    }

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools =>
        agent
          .run("Hi", tools, inputGuardrails = Seq(lengthGuardrail))
          .fold(
            error => {
              error shouldBe a[ValidationError]
              mockClient.callCount shouldBe 0
            },
            _ => fail("Expected error but got success")
          )
    )
  }

  it should "validate output with guardrails" in {
    val completion = createCompletion("bad response")
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    // Create a guardrail that rejects "bad" responses
    val badWordGuardrail = new OutputGuardrail {
      def name: String = "bad-word-check"
      def validate(output: String): Result[String] =
        if (output.contains("bad")) Left(ValidationError("output", "Contains forbidden word"))
        else Right(output)
    }

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools =>
        agent
          .run("Test", tools, outputGuardrails = Seq(badWordGuardrail))
          .fold(
            error => error shouldBe a[ValidationError],
            _ => fail("Expected error but got success")
          )
    )
  }

  it should "apply the value an output guardrail returns as the answer" in {
    val mockClient = new MockLLMClient(Seq(Right(createCompletion("hello"))))
    val agent      = new Agent(mockClient)
    val shouting = new OutputGuardrail {
      def name: String                             = "shout"
      def validate(output: String): Result[String] = Right(output.toUpperCase)
    }

    val result = for {
      tools  <- testTools
      thread <- agent.run("Test", tools, outputGuardrails = Seq(shouting))
    } yield thread.answer shouldBe Some("HELLO")
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  // ==========================================================================
  // ContinueConversation Tests
  // ==========================================================================

  "Agent.continueConversation" should "continue from a completed thread" in {
    val response1  = createCompletion("First response")
    val response2  = createCompletion("Second response")
    val mockClient = new MockLLMClient(Seq(Right(response1), Right(response2)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools   <- testTools
      thread1 <- agent.run("First query", tools)
      _ = thread1.status shouldBe ThreadStatus.Completed
      thread2 <- agent.continueConversation(thread1, "Follow-up query", tools)
    } yield {
      thread2.status shouldBe ThreadStatus.Completed

      // Should have both conversations
      val messages = thread2.messages
      messages.count(_.isInstanceOf[UserMessage]) shouldBe 2
      // the model saw the first exchange when it answered the second
      (mockClient.calls(1)._1.messages.map(_.content) should contain).allOf("First query", "First response")
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "keep the system message and completion options of the thread it continues" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val result = for {
      tools <- testTools
      thread1 <- agent.run(
        "First",
        tools,
        systemPromptAddition = Some("Always respond in JSON format."),
        completionOptions = CompletionOptions(temperature = 0.3)
      )
      _ <- agent.continueConversation(thread1, "Second", tools)
    } yield {
      val (conversation, options) = mockClient.calls(1)
      conversation.messages.head.content should include("JSON format")
      options.temperature shouldBe 0.3
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "continue a thread loaded from its JSON, in another Agent" in {
    val first        = new Agent(new MockLLMClient(Seq(Right(createCompletion("First response")))))
    val secondClient = new MockLLMClient(Seq(Right(createCompletion("Second response"))))

    val result = for {
      tools   <- testTools
      thread1 <- first.run("First query", tools)
      loaded  <- AgentThread.fromJson(AgentThread.toJson(thread1))
      thread2 <- new Agent(secondClient).continueConversation(loaded, "Follow-up", tools)
    } yield {
      thread2.answer shouldBe Some("Second response")
      (secondClient.calls.head._1.messages.map(_.content) should contain).allOf("First query", "First response")
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "reject continuation from a thread that is not finished, without calling the model" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)
    val suspended = AgentThread(
      "t-1",
      messages = Seq(UserMessage("Test")),
      status = ThreadStatus.Suspended(Vector(SuspendedOn("1.0", "approval", ujson.Null)))
    )

    val result = for {
      tools <- testTools
      _     <- agent.continueConversation(suspended, "Follow-up", tools)
    } yield ()

    result.left.map(_ shouldBe a[ValidationError]).left.getOrElse(fail("Expected error but got success"))
    mockClient.callCount shouldBe 0
  }

  it should "allow continuation from a failed thread" in {
    val response   = createCompletion("Recovery response")
    val mockClient = new MockLLMClient(Seq(Right(response)))
    val agent      = new Agent(mockClient)
    val failed =
      AgentThread("t-1", messages = Seq(UserMessage("Test")), status = ThreadStatus.Failed("Previous error"))

    val result = for {
      tools   <- testTools
      thread2 <- agent.continueConversation(failed, "Let's try again", tools)
    } yield thread2.status shouldBe ThreadStatus.Completed
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "continue a conversation cut off by the step limit" in {
    // the first model call asks for a tool; the limit refuses the second
    val toolCall = createToolCall("calculator", """{"a": 1, "b": 1, "operation": "add"}""")
    val mockClient = new MockLLMClient(
      Seq(Right(createCompletion("Calculating...", Seq(toolCall))), Right(createCompletion("Fresh start")))
    )
    val agent = new Agent(mockClient)

    val result = for {
      tools   <- testTools
      limited <- agent.run("Loop", tools, maxSteps = Some(1))
      _ = limited.status shouldBe a[ThreadStatus.Failed]
      next <- agent.continueConversation(limited, "Try something else", tools)
    } yield next.answer shouldBe Some("Fresh start")
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  // ==========================================================================
  // RunMultiTurn Tests
  // ==========================================================================

  "Agent.runMultiTurn" should "execute multiple turns sequentially" in {
    val responses = Seq(
      Right(createCompletion("Response 1")),
      Right(createCompletion("Response 2")),
      Right(createCompletion("Response 3"))
    )
    val mockClient = new MockLLMClient(responses)
    val agent      = new Agent(mockClient)

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools =>
        agent
          .runMultiTurn(
            initialQuery = "Query 1",
            followUpQueries = Seq("Query 2", "Query 3"),
            tools = tools
          )
          .fold(
            e => fail(s"runMultiTurn failed: ${e.formatted}"),
            thread => {
              mockClient.callCount shouldBe 3

              // Final thread should have all user messages
              val userMessages = thread.messages.collect { case m: UserMessage => m }
              userMessages.map(_.content) shouldBe Seq("Query 1", "Query 2", "Query 3")
            }
          )
    )
  }

  it should "stop on first error" in {
    val error = APIError("provider", "Rate limited", None, None)
    val responses = Seq(
      Right(createCompletion("Response 1")),
      Left(error)
    )
    val mockClient = new MockLLMClient(responses)
    val agent      = new Agent(mockClient)

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools => {
        val result = agent.runMultiTurn(
          initialQuery = "Query 1",
          followUpQueries = Seq("Query 2", "Query 3"),
          tools = tools
        )

        result.isLeft shouldBe true
        mockClient.callCount shouldBe 2 // Stopped after error
      }
    )
  }

  // ==========================================================================
  // Markdown rendering
  // ==========================================================================

  "Agent.formatThreadAsMarkdown" should "format a thread as markdown" in {
    val completion = createCompletion("The answer is 42.")
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools =>
        agent
          .run("What is the meaning of life?", tools)
          .fold(
            e => fail(s"Agent run failed: ${e.formatted}"),
            thread => {
              val markdown = agent.formatThreadAsMarkdown(thread)

              markdown should include("# Agent Execution Trace")
              markdown should include("Initial Query")
              markdown should include("What is the meaning of life?")
              markdown should include("The answer is 42.")
              markdown should include("Completed")
            }
          )
    )
  }

  it should "include tool calls in markdown" in {
    val toolCall      = createToolCall("calculator", """{"a": 10, "b": 5, "operation": "divide"}""")
    val firstResponse = createCompletion("Dividing...", Seq(toolCall))
    val finalResponse = createCompletion("Result is 2.")

    val mockClient = new MockLLMClient(Seq(Right(firstResponse), Right(finalResponse)))
    val agent      = new Agent(mockClient)

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools =>
        agent
          .run("What is 10/5?", tools)
          .fold(
            e => fail(s"Agent run failed: ${e.formatted}"),
            thread => {
              val markdown = agent.formatThreadAsMarkdown(thread)

              markdown should include("Tool Calls")
              markdown should include("calculator")
              markdown should include("Tool Response")
            }
          )
    )
  }

  // ==========================================================================
  // Debug Mode Tests
  // ==========================================================================

  "Agent debug mode" should "execute successfully with debug=true" in {
    val completion = createCompletion("Debug response")
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      thread <- agent.run("Test debug mode", tools, context = AgentContext(debug = true))
    } yield thread

    result.fold(
      e => fail(s"Test failed: ${e.formatted}"),
      thread => thread.status shouldBe ThreadStatus.Completed
    )
  }

  // ==========================================================================
  // Handoff Tests
  // ==========================================================================

  "Agent with handoffs" should "hand off to the target when the model calls the handoff tool" in {
    val targetClient = new MockLLMClient(Seq(Right(createCompletion("The specialist's answer"))))
    val targetAgent  = new Agent(targetClient)
    val handoff      = Handoff("target", targetAgent, Some("For specialist help"))

    // a tool call that triggers the handoff, by the handoff's tool name
    val handoffToolCall = ToolCall(
      id = "call_handoff",
      name = handoff.handoffId,
      arguments = ujson.read("""{"reason": "Need specialist"}""")
    )
    val completion = createCompletion("Handing off...", Seq(handoffToolCall))

    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      thread <- agent.run("Complex query", tools, handoffs = Seq(handoff))
    } yield {
      // the source agent made one call and was not asked again; the target answered
      mockClient.callCount shouldBe 1
      targetClient.callCount shouldBe 1
      thread.answer shouldBe Some("The specialist's answer")
      thread.status shouldBe ThreadStatus.Completed
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  // ==========================================================================
  // Context Window Tests
  // ==========================================================================

  "MockLLMClient" should "return configured context window" in {
    val mockClient = new MockLLMClient(contextWindow = 8192, reserveCompletion = 2048)

    mockClient.getContextWindow() shouldBe 8192
    mockClient.getReserveCompletion() shouldBe 2048
  }

  it should "calculate context budget correctly" in {
    import org.llm4s.types.HeadroomPercent

    val mockClient = new MockLLMClient(contextWindow = 10000, reserveCompletion = 2000)

    // Budget = (10000 - 2000) * 0.92 = 7360 (with 8% headroom - HeadroomPercent.Standard is 0.08)
    mockClient.getContextBudget(HeadroomPercent.Standard) shouldBe 7360
  }

  // ==========================================================================
  // Step limit tests
  // ==========================================================================

  "Agent step limit" should "fail with ThreadStatus.Failed when maxSteps is exhausted" in {
    // A client that always returns a tool call forces the agent to keep looping
    val infiniteToolClient = new MockLLMClient(
      Seq.fill(10)(
        Right(createCompletion("", Seq(createToolCall("calculator", """{"a":1,"b":2,"operation":"add"}"""))))
      )
    )
    val agent = new Agent(infiniteToolClient)
    val result = for {
      tools  <- testTools
      thread <- agent.run("test", tools, maxSteps = Some(2))
    } yield thread

    result match {
      case Right(thread) =>
        thread.status shouldBe a[ThreadStatus.Failed]
        thread.status.asInstanceOf[ThreadStatus.Failed].error should include("Maximum step limit reached")
        thread.messages.lastOption.collect { case a: AssistantMessage => a.toolCalls }.getOrElse(Nil) shouldBe empty
      case Left(err) => fail(s"Expected Right but got Left: ${err.formatted}")
    }
  }

  it should "complete successfully when fewer than maxSteps model calls are needed" in {
    // Two-turn run: first call returns a tool call, second returns text
    val twoTurnClient = new MockLLMClient(
      Seq(
        Right(createCompletion("", Seq(createToolCall("calculator", """{"a":1,"b":2,"operation":"add"}""")))),
        Right(createCompletion("The answer is 3"))
      )
    )
    val agent = new Agent(twoTurnClient)
    val result = for {
      tools  <- testTools
      thread <- agent.run("test", tools, maxSteps = Some(5))
    } yield thread

    result match {
      case Right(thread) => thread.status shouldBe ThreadStatus.Completed
      case Left(err)     => fail(s"Expected Right but got Left: ${err.formatted}")
    }
  }

  it should "complete when exactly maxSteps model calls are used" in {
    val twoTurnClient = new MockLLMClient(
      Seq(
        Right(createCompletion("", Seq(createToolCall("calculator", """{"a":1,"b":2,"operation":"add"}""")))),
        Right(createCompletion("The answer is 3"))
      )
    )
    val result = for {
      tools  <- testTools
      thread <- new Agent(twoTurnClient).run("test", tools, maxSteps = Some(2))
    } yield thread

    result.map(_.status) shouldBe Right(ThreadStatus.Completed)
  }

  it should "fail before calling the model at all when maxSteps is zero" in {
    val mockClient = new MockLLMClient(Seq(Right(createCompletion("never asked"))))

    val result = new Agent(mockClient).run("test", ToolRegistry.empty, maxSteps = Some(0))

    result.map(_.status) shouldBe Right(ThreadStatus.Failed(Agent.StepLimitMessage))
    mockClient.callCount shouldBe 0
  }

  it should "complete a handoff made in the last allowed model call" in {
    val target  = new MockLLMClient(Seq(Right(createCompletion("From the target"))))
    val handoff = Handoff("target", new Agent(target))
    val call    = ToolCall("h1", handoff.handoffId, ujson.read("""{"reason": "needs it"}"""))
    val client  = new MockLLMClient(Seq(Right(createCompletion("", Seq(call)))))

    val result = new Agent(client).run("test", ToolRegistry.empty, handoffs = Seq(handoff), maxSteps = Some(1))

    result.map(_.answer) shouldBe Right(Some("From the target"))
  }

  // ==========================================================================
  // LLM error handling tests
  // ==========================================================================

  "Agent LLM error handling" should "return Left when LLM call fails on first step" in {
    val error      = org.llm4s.error.APIError("provider-x", "Internal server error")
    val failClient = new FailingLLMClient(error)
    val agent      = new Agent(failClient)
    val result = for {
      tools  <- testTools
      thread <- agent.run("test", tools, maxSteps = Some(5))
    } yield thread

    result shouldBe a[Left[_, _]]
  }

  it should "return Left when LLM call fails mid-run after successful steps" in {
    // First call succeeds with a tool call; second call fails
    val mixedClient = new MockLLMClient(
      Seq(
        Right(createCompletion("", Seq(createToolCall("calculator", """{"a":1,"b":2,"operation":"add"}""")))),
        Left(org.llm4s.error.APIError("provider-x", "Service unavailable"))
      )
    )
    val agent = new Agent(mixedClient)
    val result = for {
      tools  <- testTools
      thread <- agent.run("test", tools, maxSteps = Some(5))
    } yield thread

    result shouldBe a[Left[_, _]]
  }
}
