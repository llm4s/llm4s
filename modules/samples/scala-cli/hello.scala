//> using scala 3.7.1
//> using dep org.llm4s::llm4s-core:0.4.1
//> using resourceDir resources
//> using javaOpt -Dlogback.configurationFile=logback.xml

// A first LLM4S call in one file, with no sbt project.
//
//   cd modules/samples/scala-cli
//   scala-cli run .                                              # OpenAI: set OPENAI_API_KEY first
//   scala-cli run . --java-opt -Dllm4s.providers.provider=ollama-local   # local Ollama, no API key
//
// Run it from this directory: `logback.xml` is found relative to the working directory.
// The provider comes from `resources/application.conf` (named sections, see
// docs/getting-started/configuration.md). The version above is the latest release; update it
// (and read the 0.5.0 note in docs/getting-started/scala-cli.md) when a newer one is published.

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService

@main def hello(): Unit = {
  val answer = for {
    providerConfig <- Llm4sConfig.defaultProvider()
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client     <- LLMConnect.getClient(providerConfig)
    completion <- client.complete(Conversation(Seq(UserMessage("In one sentence, what is Scala?"))))
  } yield completion.content

  answer.fold(error => println(s"Error: ${error.formatted}"), text => println(text))
}
