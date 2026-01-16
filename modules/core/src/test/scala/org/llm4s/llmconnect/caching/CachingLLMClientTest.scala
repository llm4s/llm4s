package org.llm4s.llmconnect.caching

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.{EmbeddingClient, LLMClient}
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.types.Result
import java.time.Instant
import scala.concurrent.duration._

class CachingLLMClientTest extends AnyFunSuite with Matchers {

  class MockProvider extends EmbeddingProvider {
    def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = ???
  }

  class MockLLMClient extends LLMClient {
    var callCount = 0
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      callCount += 1
      Right(Completion(
        id = "comp-123",
        created = Instant.now().toEpochMilli,
        content = "Response from LLM",
        model = "gpt-model",
        message = AssistantMessage("Response from LLM"),
        usage = None
      ))
    }
    
    override def streamComplete(conversation: Conversation, options: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] = ???
    override def getContextWindow(): Int = 4096
    override def getReserveCompletion(): Int = 100
    override def validate(): Result[Unit] = Right(())
    override def close(): Unit = ()
  }

  class MockEmbeddingClient(responses: Map[String, Seq[Double]]) extends EmbeddingClient(new MockProvider(), None, "embedding") {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      val text = request.input.head
      val embedding = responses.getOrElse(text, Seq.fill(3)(0.0))
      Right(EmbeddingResponse(
        embeddings = Seq(embedding),
        metadata = Map.empty
      ))
    }
  }

  val embeddingModel = EmbeddingModelConfig("test-embedding", 3)
  val config = CacheConfig(similarityThreshold = 0.9, ttl = 1.hour)

  test("Cache miss calls base client and caches result") {
    val mockLLM = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map(
      "Hello" -> Seq(1.0, 0.0, 0.0)
    ))
    
    val cachingClient = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config)
    
    val conversation = Conversation.userOnly("Hello").getOrElse(fail("failed to create conv"))
    
    val res1 = cachingClient.complete(conversation)
    res1.isRight shouldBe true
    mockLLM.callCount shouldBe 1
    
    val res2 = cachingClient.complete(conversation)
    res2.isRight shouldBe true
    mockLLM.callCount shouldBe 1
  }

  test("Cache hit with similar query") {
    val mockLLM = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map(
      "Hello" -> Seq(1.0, 0.0, 0.0),
      "Hi"    -> Seq(0.95, 0.0, 0.0)
    ))
    
    val cachingClient = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config)
    
    val conv1 = Conversation.userOnly("Hello").getOrElse(fail("fail"))
    cachingClient.complete(conv1)
    mockLLM.callCount shouldBe 1
    
    val conv2 = Conversation.userOnly("Hi").getOrElse(fail("fail"))
    cachingClient.complete(conv2)
    mockLLM.callCount shouldBe 1
  }

  test("Cache miss with dissimilar query") {
    val mockLLM = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map(
      "Hello" -> Seq(1.0, 0.0, 0.0),
      "Bye"   -> Seq(0.0, 1.0, 0.0)
    ))
    
    val cachingClient = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, config)
    
    val conv1 = Conversation.userOnly("Hello").getOrElse(fail("fail"))
    cachingClient.complete(conv1)
    mockLLM.callCount shouldBe 1
    
    val conv2 = Conversation.userOnly("Bye").getOrElse(fail("fail"))
    cachingClient.complete(conv2)
    mockLLM.callCount shouldBe 2
  }

  test("TTL expiration") {
    val mockLLM = new MockLLMClient()
    val mockEmbed = new MockEmbeddingClient(Map(
      "Hello" -> Seq(1.0, 0.0, 0.0)
    ))
    val shortConfig = CacheConfig(similarityThreshold = 0.9, ttl = 10.millis)
    val cachingClient = new CachingLLMClient(mockLLM, mockEmbed, embeddingModel, shortConfig)
    
    val conv = Conversation.userOnly("Hello").getOrElse(fail("fail"))
    cachingClient.complete(conv)
    mockLLM.callCount shouldBe 1
    
    Thread.sleep(50)
    
    cachingClient.complete(conv)
    mockLLM.callCount shouldBe 2
  }
}
