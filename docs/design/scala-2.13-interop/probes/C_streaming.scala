// Probe C: streamComplete with a Scala 2.13 callback.
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{CompletionOptions, Conversation, UserMessage}
import org.llm4s.llmconnect.spi.ProviderRegistry

object Probe extends App {
  FakeOllama.withServer("streamed reply") { port =>
    FakeOllama.configure(port)
    val registry = ProviderRegistry.default
    val chunks   = scala.collection.mutable.ListBuffer.empty[String]
    val reply = for {
      models <- Llm4sConfig.modelRegistryService()
      config <- Llm4sConfig.defaultProvider()(registry)
      client <- LLMConnect.getClient(config)(models, registry)
      done <- client.streamComplete(Conversation(Seq(UserMessage("Hi"))), CompletionOptions(), chunk => chunks ++= chunk.content)
    } yield done
    reply match {
      case Right(c)    => println(s"PASS: ${chunks.size} chunks ${chunks.mkString("[", "|", "]")}, final: ${c.content}")
      case Left(error) => println(s"FAIL: ${error.message}")
    }
  }
}
