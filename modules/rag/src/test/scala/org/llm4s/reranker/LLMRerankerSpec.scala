package org.llm4s.reranker

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LLMRerankerSpec extends AnyFlatSpec with Matchers {

  // Helper to create mock completion
  private def mockCompletion(content: String): Completion = Completion(
    id = "test-id",
    created = System.currentTimeMillis(),
    content = content,
    model = "test-model",
    message = AssistantMessage(content)
  )

  // Mock LLM client that returns predictable scores
  class MockLLMClient(responseContent: String) extends LLMClient {
    var lastConversation: Option[Conversation] = None

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      lastConversation = Some(conversation)
      Right(mockCompletion(responseContent))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int = 4096

    override def getReserveCompletion(): Int = 1024
  }

  // Mock LLM client that returns an error
  class ErrorLLMClient extends LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      Left(RerankError(code = Some("500"), message = "Mock error", provider = "mock"))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int = 4096

    override def getReserveCompletion(): Int = 1024
  }

  "LLMReranker" should "have correct default values" in {
    LLMReranker.DEFAULT_BATCH_SIZE shouldBe 10
  }

  it should "create reranker with default parameters" in {
    val mockClient = new MockLLMClient("[0.9]")
    val reranker   = LLMReranker(mockClient)

    reranker shouldBe a[LLMReranker]
  }

  it should "create reranker with custom batch size" in {
    val mockClient = new MockLLMClient("[0.9]")
    val reranker   = LLMReranker(mockClient, batchSize = 5)

    reranker shouldBe a[LLMReranker]
  }

  it should "create reranker with custom system prompt" in {
    val mockClient = new MockLLMClient("[0.9]")
    val reranker   = LLMReranker(mockClient, systemPrompt = Some("Custom prompt"))

    reranker shouldBe a[LLMReranker]
  }

  it should "reject invalid batch size" in {
    val mockClient = new MockLLMClient("[0.9]")

    an[IllegalArgumentException] should be thrownBy {
      LLMReranker(mockClient, batchSize = 0)
    }

    an[IllegalArgumentException] should be thrownBy {
      LLMReranker(mockClient, batchSize = -1)
    }
  }

  it should "return empty results for empty documents" in {
    val mockClient = new MockLLMClient("[0.9]")
    val reranker   = LLMReranker(mockClient)

    val request = RerankRequest(
      query = "test query",
      documents = Seq.empty
    )

    val response = reranker.rerank(request)

    response.isRight shouldBe true
    response.toOption.get.results shouldBe empty
  }

  it should "parse JSON array of scores" in {
    val mockClient = new MockLLMClient("[0.95, 0.3, 0.72]")
    val reranker   = LLMReranker(mockClient)

    val request = RerankRequest(
      query = "What is Scala?",
      documents = Seq("Scala is a programming language", "Python is popular", "Java runs on JVM")
    )

    val response = reranker.rerank(request)

    response.isRight shouldBe true
    val results = response.toOption.get.results

    results should have size 3
    // Results should be sorted by score descending
    results.head.score shouldBe 0.95
    results(1).score shouldBe 0.72
    results(2).score shouldBe 0.3
  }

  it should "handle markdown code block wrapping" in {
    val mockClient = new MockLLMClient("```json\n[0.8, 0.6]\n```")
    val reranker   = LLMReranker(mockClient)

    val request = RerankRequest(
      query = "test",
      documents = Seq("doc1", "doc2")
    )

    val response = reranker.rerank(request)

    response.isRight shouldBe true
    response.toOption.get.results should have size 2
  }

  it should "handle scores as strings" in {
    val mockClient = new MockLLMClient("[\"0.9\", \"0.5\"]")
    val reranker   = LLMReranker(mockClient)

    val request = RerankRequest(
      query = "test",
      documents = Seq("doc1", "doc2")
    )

    val response = reranker.rerank(request)

    response.isRight shouldBe true
    val results = response.toOption.get.results
    results.head.score shouldBe 0.9 +- 0.01
  }

  it should "clamp scores to valid range" in {
    val mockClient = new MockLLMClient("[1.5, -0.5]")
    val reranker   = LLMReranker(mockClient)

    val request = RerankRequest(
      query = "test",
      documents = Seq("doc1", "doc2")
    )

    val response = reranker.rerank(request)

    response.isRight shouldBe true
    val results = response.toOption.get.results
    // Scores should be clamped to [0, 1]
    results.foreach { r =>
      r.score should be >= 0.0
      r.score should be <= 1.0
    }
  }

  it should "respect topK parameter" in {
    val mockClient = new MockLLMClient("[0.9, 0.8, 0.7, 0.6, 0.5]")
    val reranker   = LLMReranker(mockClient)

    val request = RerankRequest(
      query = "test",
      documents = Seq("d1", "d2", "d3", "d4", "d5"),
      topK = Some(2)
    )

    val response = reranker.rerank(request)

    response.isRight shouldBe true
    response.toOption.get.results should have size 2
    // Should return top 2 by score
    response.toOption.get.results.head.score shouldBe 0.9
    response.toOption.get.results(1).score shouldBe 0.8
  }

  it should "return documents when returnDocuments is true" in {
    val mockClient = new MockLLMClient("[0.9, 0.5]")
    val reranker   = LLMReranker(mockClient)

    val request = RerankRequest(
      query = "test",
      documents = Seq("Document One", "Document Two"),
      returnDocuments = true
    )

    val response = reranker.rerank(request)

    response.isRight shouldBe true
    val results = response.toOption.get.results
    results.exists(_.document == "Document One") shouldBe true
    results.exists(_.document == "Document Two") shouldBe true
  }

  it should "not return documents when returnDocuments is false" in {
    val mockClient = new MockLLMClient("[0.9, 0.5]")
    val reranker   = LLMReranker(mockClient)

    val request = RerankRequest(
      query = "test",
      documents = Seq("Document One", "Document Two"),
      returnDocuments = false
    )

    val response = reranker.rerank(request)

    response.isRight shouldBe true
    val results = response.toOption.get.results
    results.foreach(_.document shouldBe "")
  }

  it should "include provider metadata" in {
    val mockClient = new MockLLMClient("[0.9]")
    val reranker   = LLMReranker(mockClient)

    val request = RerankRequest(
      query = "test",
      documents = Seq("doc1")
    )

    val response = reranker.rerank(request)

    response.isRight shouldBe true
    response.toOption.get.metadata("provider") shouldBe "llm"
  }

  it should "pad with neutral scores when LLM returns fewer scores" in {
    val mockClient = new MockLLMClient("[0.9]") // Only 1 score for 3 documents
    val reranker   = LLMReranker(mockClient)

    val request = RerankRequest(
      query = "test",
      documents = Seq("doc1", "doc2", "doc3")
    )

    val response = reranker.rerank(request)

    response.isRight shouldBe true
    val results = response.toOption.get.results
    results should have size 3
    // Missing scores should be 0.5 (neutral)
    results.count(_.score == 0.5) shouldBe 2
  }

  it should "handle LLM errors gracefully" in {
    val mockClient = new ErrorLLMClient
    val reranker   = LLMReranker(mockClient)

    val request = RerankRequest(
      query = "test",
      documents = Seq("doc1", "doc2")
    )

    val response = reranker.rerank(request)

    // Should return results with neutral scores on error
    response.isRight shouldBe true
    val results = response.toOption.get.results
    results.foreach(_.score shouldBe 0.5)
  }

  it should "send correct messages to LLM" in {
    val mockClient = new MockLLMClient("[0.9]")
    val reranker   = LLMReranker(mockClient)

    val request = RerankRequest(
      query = "What is the meaning of life?",
      documents = Seq("The answer is 42")
    )

    reranker.rerank(request)

    mockClient.lastConversation shouldBe defined
    val messages = mockClient.lastConversation.get.messages

    messages should have size 2
    messages.head shouldBe a[SystemMessage]
    messages(1) shouldBe a[UserMessage]

    // User message should contain query and document
    val userContent = messages(1).asInstanceOf[UserMessage].content
    userContent should include("What is the meaning of life?")
    userContent should include("The answer is 42")
  }

  "RerankerFactory" should "create LLM reranker" in {
    val mockClient = new MockLLMClient("[0.9]")
    val reranker   = RerankerFactory.llm(mockClient)

    reranker shouldBe a[Reranker]
  }

  it should "create LLM reranker with custom parameters" in {
    val mockClient = new MockLLMClient("[0.9]")
    val reranker   = RerankerFactory.llm(mockClient, batchSize = 5, systemPrompt = Some("Custom"))

    reranker shouldBe a[Reranker]
  }
}
