package org.llm4s.llmconnect.provider

import org.llm4s.error.{ CancelledError, ConfigurationError }
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.llmconnect.config.AzureConfig
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.testkit.ProviderModuleChecks
import org.llm4s.testutil.TestTimeLimit
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicReference

/** A caller waiting behind another caller's key fetch can be cancelled, whatever the provider is doing. */
class AzureKeyRefreshInterruptSpec extends AnyWordSpec with Matchers with TestTimeLimit:

  private given ModelRegistryService = ProviderModuleChecks.defaultModelRegistry

  private val hello = Conversation(Seq(UserMessage("hello")))

  "an Azure client with an ApiKeyProvider" should {

    "return CancelledError promptly to a caller interrupted while another call is fetching the key" in {
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val blocking = new ApiKeyProvider:
        def current(): Result[ApiKey] =
          entered.countDown()
          release.await()
          Left(ConfigurationError("vault down"))

      val config = AzureConfig("https://r.openai.azure.com", Secret(""), "gpt-4o").withApiKeyProvider(blocking)
      val client = OpenAIClient(config).fold(e => fail(e.message), identity)

      val holder = new Thread(() => client.complete(hello, CompletionOptions()): Unit)
      holder.setDaemon(true)
      holder.start()
      entered.await(10, TimeUnit.SECONDS) shouldBe true

      val outcome = new AtomicReference[Result[Completion]]()
      val waiter  = Thread.ofVirtual().start(() => outcome.set(client.complete(hello, CompletionOptions())))

      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while waiter.getState != Thread.State.BLOCKED && waiter.getState != Thread.State.WAITING &&
        System.nanoTime() < deadline
      do Thread.sleep(10)

      waiter.interrupt()
      waiter.join(java.time.Duration.ofSeconds(2))
      val stuck = waiter.isAlive

      release.countDown()
      waiter.join(java.time.Duration.ofSeconds(10))
      holder.join(10000)
      client.close()

      withClue("still blocked 2s after the interrupt: ")(stuck shouldBe false)
      outcome.get().left.toOption.get shouldBe a[CancelledError]
    }
  }
