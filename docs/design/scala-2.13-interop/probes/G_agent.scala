// Probe G: build an Agent with Agent.builder, run a turn, continue the conversation on the same thread.
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.toolapi.ToolRegistry

object Probe extends App {
  FakeOllama.withServer("Agent answer.") { port =>
    FakeOllama.configure(port)
    val registry = ProviderRegistry.default
    val turns = for {
      models <- Llm4sConfig.modelRegistryService()
      config <- Llm4sConfig.defaultProvider()(registry)
      client <- LLMConnect.getClient(config)(models, registry)
      agent  <- Agent.builder("assistant", client).withTools(ToolRegistry.empty).build()
      first  <- agent.run("What is llm4s?")
      second <- agent.continueConversation(first, "And then?")
    } yield List(first, second)
    turns match {
      case Right(results) => println(s"PASS: ${results.map(_.answer)}, same thread: ${results.map(_.threadId).distinct.size == 1}")
      case Left(error)    => println(s"FAIL: ${error.message}")
    }
  }
}
