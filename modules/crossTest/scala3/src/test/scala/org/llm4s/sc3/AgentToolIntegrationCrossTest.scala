package org.llm4s.sc3

/**
 * Cross-version test for Agent and ToolRegistry integration.
 * Verifies that Agent.initialize with builtin tools produces consistent state across Scala 2.13 and 3.x.
 * No network: uses a stub LLMClient that never performs real completion.
*/
class AgentToolIntegrationCrossTest
    extends org.scalatest.flatspec.AnyFlatSpec
    with org.scalatest.matchers.should.Matchers {

  val stubClient: org.llm4s.llmconnect.LLMClient = new org.llm4s.llmconnect.LLMClient {
    override def complete(
      conversation: org.llm4s.llmconnect.model.Conversation,
      options: org.llm4s.llmconnect.model.CompletionOptions
    ): org.llm4s.types.Result[org.llm4s.llmconnect.model.Completion] =
      Left(org.llm4s.error.ServiceError(0, "stub", "Stub client: no network"))

    override def streamComplete(
      conversation: org.llm4s.llmconnect.model.Conversation,
      options: org.llm4s.llmconnect.model.CompletionOptions,
      onChunk: org.llm4s.llmconnect.model.StreamedChunk => Unit
    ): org.llm4s.types.Result[org.llm4s.llmconnect.model.Completion] =
      Left(org.llm4s.error.ServiceError(0, "stub", "Stub client: no network"))

    override def getContextWindow(): Int    = 8192
    override def getReserveCompletion(): Int = 4096
  }

  "Agent.initialize" should "return state with conversation containing user message" in {
    val agent = new org.llm4s.agent.Agent(stubClient)
    val tools = new org.llm4s.toolapi.ToolRegistry(org.llm4s.toolapi.builtin.BuiltinTools.core)
    val state = agent.initialize("Hello, world", tools)
    state.conversation.messages should not be empty
    state.conversation.messages.head shouldBe a[org.llm4s.llmconnect.model.UserMessage]
    state.conversation.messages.head.asInstanceOf[org.llm4s.llmconnect.model.UserMessage].content shouldBe "Hello, world"
  }

  it should "return state with tools registry containing builtin core tools" in {
    val agent = new org.llm4s.agent.Agent(stubClient)
    val tools = new org.llm4s.toolapi.ToolRegistry(org.llm4s.toolapi.builtin.BuiltinTools.core)
    val state = agent.initialize("Query", tools)
    state.tools.tools should not be empty
    state.tools.tools.map(_.name).toSet should contain("get_current_datetime")
    state.tools.tools.map(_.name).toSet should contain("calculator")
  }

  it should "return state with InProgress status" in {
    val agent = new org.llm4s.agent.Agent(stubClient)
    val tools = new org.llm4s.toolapi.ToolRegistry(org.llm4s.toolapi.builtin.BuiltinTools.core)
    val state = agent.initialize("Query", tools)
    state.status shouldBe org.llm4s.agent.AgentStatus.InProgress
  }

  it should "return state with initialQuery set" in {
    val agent = new org.llm4s.agent.Agent(stubClient)
    val tools = new org.llm4s.toolapi.ToolRegistry(org.llm4s.toolapi.builtin.BuiltinTools.core)
    val state = agent.initialize("My query", tools)
    state.initialQuery shouldBe Some("My query")
  }

  it should "return state with systemMessage set" in {
    val agent = new org.llm4s.agent.Agent(stubClient)
    val tools = new org.llm4s.toolapi.ToolRegistry(org.llm4s.toolapi.builtin.BuiltinTools.core)
    val state = agent.initialize("Query", tools)
    state.systemMessage shouldBe defined
    state.systemMessage.get.content should include("assistant")
  }

  it should "expose tool definitions via state.tools.getOpenAITools" in {
    val agent = new org.llm4s.agent.Agent(stubClient)
    val tools = new org.llm4s.toolapi.ToolRegistry(org.llm4s.toolapi.builtin.BuiltinTools.core)
    val state = agent.initialize("Query", tools)
    val defs = state.tools.getOpenAITools(strict = true)
    defs.arr should not be empty
    defs.arr.map(_.obj("function").obj("name").str) should contain("get_current_datetime")
  }

  it should "work with safe() tools as well" in {
    val agent = new org.llm4s.agent.Agent(stubClient)
    val tools = new org.llm4s.toolapi.ToolRegistry(org.llm4s.toolapi.builtin.BuiltinTools.safe())
    val state = agent.initialize("Query", tools)
    state.tools.tools.map(_.name) should contain("http_request")
    state.tools.tools.map(_.name) should contain("calculator")
  }
}
