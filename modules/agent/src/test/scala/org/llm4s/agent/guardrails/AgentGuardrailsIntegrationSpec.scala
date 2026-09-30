package org.llm4s.agent.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AgentGuardrailsIntegrationSpec extends AnyFlatSpec with Matchers {

  /**
   * Mock LLM client for testing
   */
  class MockLLMClient(response: String) extends LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      Right(
        Completion(
          id = "test-completion",
          created = System.currentTimeMillis(),
          content = response,
          model = "mock-model",
          message = AssistantMessage(response, toolCalls = List.empty),
          toolCalls = List.empty,
          usage = None
        )
      )

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      // Call the callback with a chunk
      onChunk(
        StreamedChunk(
          id = "test-completion",
          content = Some(response),
          toolCall = None,
          finishReason = Some("stop")
        )
      )

      // Return the full completion
      complete(conversation, options)
    }

    override def getContextWindow(): Int = 8192

    override def getReserveCompletion(): Int = 1000
  }

  "Agent.run with input guardrails" should "validate input before processing" in {
    val mockClient = new MockLLMClient("Response")
    val agent      = new Agent(mockClient)
    val tools      = new ToolRegistry(Seq.empty)

    val inputGuardrails = Seq(
      new LengthCheck(min = 10, max = 100)
    )

    // Should fail validation - input too short
    val result = agent.run(
      query = "Short",
      tools = tools,
      inputGuardrails = inputGuardrails
    )

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
    result.swap.toOption.get.formatted should include("too short")
  }

  it should "pass when input validation succeeds" in {
    val mockClient = new MockLLMClient("Valid response")
    val agent      = new Agent(mockClient)
    val tools      = new ToolRegistry(Seq.empty)

    val inputGuardrails = Seq(
      new LengthCheck(min = 1, max = 100)
    )

    val result = agent.run(
      query = "Valid query",
      tools = tools,
      inputGuardrails = inputGuardrails
    )

    result.isRight shouldBe true
  }

  it should "apply multiple input guardrails" in {
    val mockClient = new MockLLMClient("Response")
    val agent      = new Agent(mockClient)
    val tools      = new ToolRegistry(Seq.empty)

    val inputGuardrails = Seq(
      new LengthCheck(min = 1, max = 100),
      new ProfanityFilter()
    )

    // Should fail on profanity filter
    val result = agent.run(
      query = "This contains badword",
      tools = tools,
      inputGuardrails = inputGuardrails
    )

    result.isLeft shouldBe true
  }

  "Agent.run with output guardrails" should "validate output before returning" in {
    val mockClient = new MockLLMClient("not json")
    val agent      = new Agent(mockClient)
    val tools      = new ToolRegistry(Seq.empty)

    val outputGuardrails = Seq(
      new JSONValidator()
    )

    val result = agent.run(
      query = "Generate JSON",
      tools = tools,
      outputGuardrails = outputGuardrails
    )

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
    result.swap.toOption.get.formatted should include("not valid JSON")
  }

  it should "pass when output validation succeeds" in {
    val mockClient = new MockLLMClient("""{"key": "value"}""")
    val agent      = new Agent(mockClient)
    val tools      = new ToolRegistry(Seq.empty)

    val outputGuardrails = Seq(
      new JSONValidator()
    )

    val result = agent.run(
      query = "Generate JSON",
      tools = tools,
      outputGuardrails = outputGuardrails
    )

    result.isRight shouldBe true
  }

  it should "apply multiple output guardrails" in {
    val mockClient = new MockLLMClient("""{"key": "value"}""")
    val agent      = new Agent(mockClient)
    val tools      = new ToolRegistry(Seq.empty)

    val outputGuardrails = Seq(
      new JSONValidator(),
      new LengthCheck(1, 1000)
    )

    val result = agent.run(
      query = "Generate JSON",
      tools = tools,
      outputGuardrails = outputGuardrails
    )

    result.isRight shouldBe true
  }

  "Agent.run with both input and output guardrails" should "validate both" in {
    val mockClient = new MockLLMClient("""{"result": "success"}""")
    val agent      = new Agent(mockClient)
    val tools      = new ToolRegistry(Seq.empty)

    val inputGuardrails = Seq(
      new LengthCheck(1, 100),
      new ProfanityFilter()
    )

    val outputGuardrails = Seq(
      new JSONValidator()
    )

    val result = agent.run(
      query = "Generate a JSON response",
      tools = tools,
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails
    )

    result.isRight shouldBe true
  }

  it should "fail on input validation first" in {
    val mockClient = new MockLLMClient("""{"result": "success"}""")
    val agent      = new Agent(mockClient)
    val tools      = new ToolRegistry(Seq.empty)

    val inputGuardrails = Seq(
      new LengthCheck(1, 5) // Will fail
    )

    val outputGuardrails = Seq(
      new JSONValidator()
    )

    val result = agent.run(
      query = "This query is too long",
      tools = tools,
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails
    )

    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("too long")
  }

  "Agent.continueConversation with guardrails" should "validate new message" in {
    val mockClient = new MockLLMClient("Response")
    val agent      = new Agent(mockClient)
    val tools      = new ToolRegistry(Seq.empty)

    // First turn
    val state1 = agent.run("First query", tools).toOption.get

    // Second turn with validation
    val inputGuardrails = Seq(
      new LengthCheck(min = 10, max = 100)
    )

    val result = agent.continueConversation(
      state1,
      "Short", // Too short
      inputGuardrails = inputGuardrails
    )

    result.isLeft shouldBe true
  }

  it should "validate output on continuation" in {
    val mockClient = new MockLLMClient("not json")
    val agent      = new Agent(mockClient)
    val tools      = new ToolRegistry(Seq.empty)

    // First turn
    val state1 = agent.run("First query", tools).toOption.get

    // Second turn with output validation
    val outputGuardrails = Seq(
      new JSONValidator()
    )

    val result = agent.continueConversation(
      state1,
      "Generate JSON please",
      outputGuardrails = outputGuardrails
    )

    result.isLeft shouldBe true
  }

  it should "work in multi-turn conversations" in {
    val mockClient = new MockLLMClient("""{"response": "ok"}""")
    val agent      = new Agent(mockClient)
    val tools      = new ToolRegistry(Seq.empty)

    val inputGuardrails  = Seq(new LengthCheck(1, 100))
    val outputGuardrails = Seq(new JSONValidator())

    val result = for {
      state1 <- agent.run(
        "First query",
        tools,
        inputGuardrails = inputGuardrails,
        outputGuardrails = outputGuardrails
      )
      state2 <- agent.continueConversation(
        state1,
        "Second query",
        inputGuardrails = inputGuardrails,
        outputGuardrails = outputGuardrails
      )
      state3 <- agent.continueConversation(
        state2,
        "Third query",
        inputGuardrails = inputGuardrails,
        outputGuardrails = outputGuardrails
      )
    } yield state3

    result.isRight shouldBe true
    result.toOption.get.conversation.messages.length should be >= 6 // At least 6 messages (3 user + 3 assistant)
  }

  "Guardrails" should "not interfere with normal operation when empty" in {
    val mockClient = new MockLLMClient("Normal response")
    val agent      = new Agent(mockClient)
    val tools      = new ToolRegistry(Seq.empty)

    // Run without guardrails
    val result1 = agent.run("Query", tools)

    // Run with empty guardrails
    val result2 = agent.run(
      "Query",
      tools,
      inputGuardrails = Seq.empty,
      outputGuardrails = Seq.empty
    )

    result1.isRight shouldBe true
    result2.isRight shouldBe true
  }
}
