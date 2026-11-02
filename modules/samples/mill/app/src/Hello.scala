package hello

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

object Hello {

  /** Asks `client` one question. Taking the client as a parameter is what lets the test script it. */
  def ask(client: LLMClient, question: String): Result[String] =
    client.complete(Conversation(Seq(UserMessage(question)))).map(_.content)

  def main(args: Array[String]): Unit = {
    val answer = for {
      providerConfig <- Llm4sConfig.defaultProvider()
      registry       <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      client <- LLMConnect.getClient(providerConfig)
      text   <- ask(client, "In one sentence, what is Scala?")
    } yield text

    answer.fold(error => println(s"Error: ${error.formatted}"), text => println(text))
  }
}
