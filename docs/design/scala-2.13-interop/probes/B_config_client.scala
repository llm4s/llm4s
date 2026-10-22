// Probe B: load a provider section with Llm4sConfig, get a client from LLMConnect, complete a call.
// llm4s's `using` parameters (ProviderRegistry, ModelRegistryService) are passed as an explicit argument list.
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{Conversation, UserMessage}
import org.llm4s.llmconnect.spi.ProviderRegistry

object Probe extends App {
  FakeOllama.withServer("Hello from the fake Ollama.") { port =>
    FakeOllama.configure(port)
    val registry = ProviderRegistry.default
    val reply = for {
      models     <- Llm4sConfig.modelRegistryService()
      config     <- Llm4sConfig.defaultProvider()(registry)
      client     <- LLMConnect.getClient(config)(models, registry)
      completion <- client.complete(Conversation(Seq(UserMessage("Hi"))))
    } yield completion
    reply match {
      case Right(c)    => println(s"PASS: ${c.content} (usage: ${c.usage.map(_.totalTokens)})")
      case Left(error) => println(s"FAIL: ${error.message}")
    }
  }
}
