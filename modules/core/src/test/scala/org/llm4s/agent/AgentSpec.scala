package org.llm4s.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.streaming.AgentEvent
import org.llm4s.error.{ APIError, ValidationError }
import upickle.default._

import scala.collection.mutable.ArrayBuffer

/**
 * Comprehensive tests for Agent class.
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
  // Initialize Tests
  // ==========================================================================

  "Agent.initialize" should "create initial state with query" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val result = for {
      tools <- testTools
      state <- agent.initializeSafe("What is 2+2?", tools)
    } yield {
      state.initialQuery shouldBe Some("What is 2+2?")
      state.status shouldBe AgentStatus.InProgress
      state.conversation.messages should have size 1
      state.conversation.messages.head shouldBe a[UserMessage]
      state.conversation.messages.head.content shouldBe "What is 2+2?"
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "include system message" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val result = for {
      tools <- testTools
      state <- agent.initializeSafe("Test query", tools)
    } yield state.systemMessage match {
      case Some(msg) => msg.content should include("helpful assistant")
      case None      => fail("Expected systemMessage to be defined")
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "append system prompt addition" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val result = for {
      tools <- testTools
      state <- agent.initializeSafe(
        "Test query",
        tools,
        systemPromptAddition = Some("Always respond in JSON format.")
      )
    } yield state.systemMessage match {
      case Some(msg) => msg.content should include("JSON format")
      case None      => fail("Expected systemMessage to be defined")
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "include tools in state" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val result = for {
      tools <- testTools
      state <- agent.initializeSafe("Test", tools)
    } yield {
      state.tools.tools should have size 1
      state.tools.tools.head.name shouldBe "calculator"
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "include handoff tools when handoffs are provided" in {
    val targetClient = new MockLLMClient()
    val targetAgent  = new Agent(targetClient)
    val handoff      = Handoff(targetAgent, Some("For complex math"))

    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val result = for {
      tools <- testTools
      state <- agent.initializeSafe("Test", tools, handoffs = Seq(handoff))
    } yield {
      // Should have original tool + handoff tool
      state.tools.tools.size shouldBe 2
      state.tools.tools.exists(_.name.startsWith("handoff_to_agent_")) shouldBe true
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "store completion options" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val options = CompletionOptions(temperature = 0.5, maxTokens = Some(100))
    val result = for {
      tools <- testTools
      state <- agent.initializeSafe("Test", tools, completionOptions = options)
    } yield {
      state.completionOptions.temperature shouldBe 0.5
      state.completionOptions.maxTokens shouldBe Some(100)
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  // ==========================================================================
  // RunStep Tests - InProgress State
  // ==========================================================================

  "Agent.runStep" should "transition InProgress to Complete when no tool calls" in {
    val completion = createCompletion("The answer is 4.")
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      state1 <- agent.initializeSafe("What is 2+2?", tools)
      state2 <- agent.runStep(state1)
    } yield {
      state2.status shouldBe AgentStatus.Complete
      state2.conversation.messages.last.content shouldBe "The answer is 4."
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "transition InProgress to WaitingForTools when tool calls present" in {
    val toolCall   = createToolCall("calculator", """{"a": 2, "b": 2, "operation": "add"}""")
    val completion = createCompletion("Let me calculate that.", Seq(toolCall))
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      state1 <- agent.initializeSafe("What is 2+2?", tools)
      state2 <- agent.runStep(state1)
    } yield state2.status shouldBe AgentStatus.WaitingForTools
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "return error on LLM failure" in {
    val error      = APIError("provider", "API key invalid", None, None)
    val mockClient = new MockLLMClient(Seq(Left(error)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      state1 <- agent.initializeSafe("Test", tools)
    } yield agent
      .runStep(state1)
      .fold(
        error => error shouldBe a[APIError],
        _ => fail("Expected error but got success")
      )
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "pass tools to LLM via completion options" in {
    val completion = createCompletion("Done")
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      state1 <- agent.initializeSafe("Test", tools)
      _      <- agent.runStep(state1)
    } yield {
      mockClient.callCount shouldBe 1
      val (_, options) = mockClient.calls.head
      options.tools should have size 1
      options.tools.head.name shouldBe "calculator"
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  // ==========================================================================
  // RunStep Tests - WaitingForTools State
  // ==========================================================================

  "Agent.runStep (WaitingForTools)" should "execute tools and transition to InProgress" in {
    val toolCall   = createToolCall("calculator", """{"a": 2, "b": 2, "operation": "add"}""")
    val completion = createCompletion("Let me calculate.", Seq(toolCall))
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      state1 <- agent.initializeSafe("What is 2+2?", tools)
      state2 <- agent.runStep(state1)
      _ = state2.status shouldBe AgentStatus.WaitingForTools
      state3 <- agent.runStep(state2)
    } yield {
      state3.status shouldBe AgentStatus.InProgress

      // Should have tool message in conversation
      val toolMessages = state3.conversation.messages.collect { case m: ToolMessage => m }
      toolMessages should have size 1
      toolMessages.head.content should include("4") // Result of 2+2
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "handle tool execution errors gracefully" in {
    // Create a tool call for a non-existent tool
    val toolCall   = createToolCall("nonexistent_tool", """{"param": "value"}""")
    val completion = createCompletion("Calling tool.", Seq(toolCall))
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      state1 <- agent.initializeSafe("Test", tools)
      state2 <- agent.runStep(state1)
      state3 <- agent.runStep(state2)
    } yield {
      // Tool error should be in the tool message
      val toolMessages = state3.conversation.messages.collect { case m: ToolMessage => m }
      toolMessages should have size 1
      toolMessages.head.content should include("isError")
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  // ==========================================================================
  // RunStep Tests - Terminal States
  // ==========================================================================

  "Agent.runStep (Complete)" should "remain in Complete state" in {
    val completion = createCompletion("Done")
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      state1 <- agent.initializeSafe("Test", tools)
      state2 <- agent.runStep(state1)
      _ = state2.status shouldBe AgentStatus.Complete
      // Running step again should not change state
      state3 <- agent.runStep(state2)
    } yield state3.status shouldBe AgentStatus.Complete
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  "Agent.runStep (Failed)" should "remain in Failed state" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val result = for {
      tools <- testTools
      state <- agent.initializeSafe("Test", tools)
      failedState = state.withStatus(AgentStatus.Failed("Test error"))
      result <- agent.runStep(failedState)
    } yield result.status shouldBe AgentStatus.Failed("Test error")
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  // ==========================================================================
  // Run Tests (full execution)
  // ==========================================================================

  "Agent.run" should "execute until completion" in {
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
            state => {
              state.status shouldBe AgentStatus.Complete
              state.conversation.messages.last.content shouldBe "The answer is 4."
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
            state => {
              state.status shouldBe AgentStatus.Complete
              mockClient.callCount shouldBe 2
            }
          )
    )
  }

  it should "respect maxSteps limit" in {
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
            finalState => {
              finalState.status shouldBe a[AgentStatus.Failed]
              finalState.status.asInstanceOf[AgentStatus.Failed].error should include("step limit")
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
            error => error shouldBe a[ValidationError],
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

  // ==========================================================================
  // ContinueConversation Tests
  // ==========================================================================

  "Agent.continueConversation" should "continue from a completed state" in {
    val response1  = createCompletion("First response")
    val response2  = createCompletion("Second response")
    val mockClient = new MockLLMClient(Seq(Right(response1), Right(response2)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools  <- testTools
      state1 <- agent.run("First query", tools)
      _ = state1.status shouldBe AgentStatus.Complete
      state2 <- agent.continueConversation(state1, "Follow-up query")
    } yield {
      state2.status shouldBe AgentStatus.Complete

      // Should have both conversations
      val messages = state2.conversation.messages
      messages.count(_.isInstanceOf[UserMessage]) shouldBe 2
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "reject continuation from incomplete state" in {
    val mockClient = new MockLLMClient()
    val agent      = new Agent(mockClient)

    val result = for {
      tools           <- testTools
      inProgressState <- agent.initializeSafe("Test", tools)
    } yield {
      inProgressState.status shouldBe AgentStatus.InProgress

      agent
        .continueConversation(inProgressState, "Follow-up")
        .fold(
          error => error shouldBe a[ValidationError],
          _ => fail("Expected error but got success")
        )
    }
    result.left.foreach(e => fail(s"Failed: ${e.formatted}"))
  }

  it should "allow continuation from failed state" in {
    val response   = createCompletion("Recovery response")
    val mockClient = new MockLLMClient(Seq(Right(response)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools <- testTools
      state <- agent.initializeSafe("Test", tools)
      failedState = state.withStatus(AgentStatus.Failed("Previous error"))
      state2 <- agent.continueConversation(failedState, "Let's try again")
    } yield state2.status shouldBe AgentStatus.Complete
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
            state => {
              mockClient.callCount shouldBe 3

              // Final state should have all user messages
              val userMessages = state.conversation.messages.collect { case m: UserMessage => m }
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
  // RunWithEvents Tests (Streaming)
  // ==========================================================================

  "Agent.runWithEvents" should "emit events during execution" in {
    val completion = createCompletion("Response text")
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    val events = ArrayBuffer[AgentEvent]()

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools => {
        val result = agent.runWithEvents(
          query = "Test query",
          tools = tools,
          onEvent = events += _
        )

        result.isRight shouldBe true

        // Should have start, step, text, and complete events
        events.exists(_.isInstanceOf[AgentEvent.AgentStarted]) shouldBe true
        events.exists(_.isInstanceOf[AgentEvent.StepStarted]) shouldBe true
        events.exists(_.isInstanceOf[AgentEvent.AgentCompleted]) shouldBe true
      }
    )
  }

  it should "emit tool events when tools are called" in {
    val toolCall      = createToolCall("calculator", """{"a": 5, "b": 3, "operation": "multiply"}""")
    val firstResponse = createCompletion("Calculating...", Seq(toolCall))
    val finalResponse = createCompletion("The answer is 15.")

    val mockClient = new MockLLMClient(Seq(Right(firstResponse), Right(finalResponse)))
    val agent      = new Agent(mockClient)

    val events = ArrayBuffer[AgentEvent]()

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools => {
        val result = agent.runWithEvents(
          query = "What is 5 times 3?",
          tools = tools,
          onEvent = events += _
        )

        result.isRight shouldBe true

        // Should have tool start and complete events
        events.exists(_.isInstanceOf[AgentEvent.ToolCallStarted]) shouldBe true
        events.exists(_.isInstanceOf[AgentEvent.ToolCallCompleted]) shouldBe true
      }
    )
  }

  // ==========================================================================
  // RunCollectingEvents Tests
  // ==========================================================================

  "Agent.runCollectingEvents" should "return state and all events" in {
    val completion = createCompletion("Response")
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools =>
        agent
          .runCollectingEvents("Test", tools)
          .fold(
            e => fail(s"runCollectingEvents failed: ${e.formatted}"),
            { case (state, events) =>
              state.status shouldBe AgentStatus.Complete
              events should not be empty
            }
          )
    )
  }

  // ==========================================================================
  // FormatStateAsMarkdown Tests
  // ==========================================================================

  "Agent.formatStateAsMarkdown" should "format state as markdown" in {
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
            state => {
              val markdown = agent.formatStateAsMarkdown(state)

              markdown should include("# Agent Execution Trace")
              markdown should include("Initial Query")
              markdown should include("What is the meaning of life?")
              markdown should include("The answer is 42.")
              markdown should include("Complete")
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
            state => {
              val markdown = agent.formatStateAsMarkdown(state)

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
      tools <- testTools
      state <- agent.run("Test debug mode", tools, context = AgentContext(debug = true))
    } yield state

    result.fold(
      e => fail(s"Test failed: ${e.formatted}"),
      state => state.status shouldBe AgentStatus.Complete
    )
  }

  // ==========================================================================
  // Handoff Detection Tests
  // ==========================================================================

  "Agent with handoffs" should "detect handoff tool calls" in {
    val targetClient = new MockLLMClient()
    val targetAgent  = new Agent(targetClient)
    val handoff      = Handoff(targetAgent, Some("For specialist help"))

    // Get the actual handoff ID that will be used
    val handoffId = handoff.handoffId

    // Create a tool call that triggers handoff using the correct handoff ID
    val handoffToolCall = ToolCall(
      id = "call_handoff",
      name = handoffId,
      arguments = ujson.read("""{"reason": "Need specialist"}""")
    )
    val completion = createCompletion("Handing off...", Seq(handoffToolCall))

    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    val result = for {
      tools        <- testTools
      initialState <- agent.initializeSafe("Complex query", tools, handoffs = Seq(handoff))
      state1       <- agent.runStep(initialState)
      _ = state1.status shouldBe AgentStatus.WaitingForTools
      state2 <- agent.runStep(state1)
    } yield state2.status shouldBe a[AgentStatus.HandoffRequested]
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

  // ============ Deprecated API tests ============

  "Agent.initialize (deprecated)" should "initialize agent state successfully" in {
    val completion = createCompletion("Test response")
    val mockClient = new MockLLMClient(Seq(Right(completion)))
    val agent      = new Agent(mockClient)

    testTools.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tools => {
        @scala.annotation.nowarn("cat=deprecation")
        val state = agent.initialize(
          query = "Test query",
          tools = tools,
          handoffs = Seq.empty,
          systemPromptAddition = None,
          completionOptions = CompletionOptions()
        )

        state.initialQuery shouldBe Some("Test query")
        state.status shouldBe AgentStatus.InProgress
        state.conversation.messages.size shouldBe 1
        state.tools.tools.map(_.name) shouldBe tools.tools.map(_.name)
      }
    )
  }
}
