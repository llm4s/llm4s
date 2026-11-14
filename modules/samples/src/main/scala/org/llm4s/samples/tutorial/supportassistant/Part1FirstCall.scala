package org.llm4s.samples.tutorial.supportassistant

import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

/**
 * Part 1 of the "Build a support assistant" tutorial (`docs/tutorials/support-assistant/part-1-first-call.md`): a
 * first call, a streamed answer, and a client from configuration.
 *
 * Each `// snippet:` region is a Scala block of the page, word for word; `Part1Spec` runs the code and compares the
 * regions with the page.
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part1FirstCall"          # scripted, no key
 *   sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part1FirstCall --live"   # your provider
 * }}}
 */
object Part1FirstCall {

  // snippet: ask
  import org.llm4s.llmconnect.LLMClient
  import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
  import org.llm4s.types.Result

  val instructions: String =
    "You are the support assistant of Acme Shop. Answer in at most three sentences."

  def conversationFor(question: String): Conversation =
    Conversation(Seq(SystemMessage(instructions), UserMessage(question)))

  def ask(client: LLMClient, question: String): Result[String] =
    client.complete(conversationFor(question), CompletionOptions()).map(_.content)
  // end snippet

  // snippet: stream
  def askStreaming(client: LLMClient, question: String)(onText: String => Unit): Result[String] =
    client
      .streamComplete(conversationFor(question), CompletionOptions(), chunk => chunk.content.foreach(onText))
      .map(_.content)
  // end snippet

  // snippet: configured-client
  import org.llm4s.config.Llm4sConfig
  import org.llm4s.llmconnect.LLMConnect
  import org.llm4s.model.ModelRegistryService

  def configuredClient(): Result[LLMClient] =
    for {
      providerConfig <- Llm4sConfig.defaultProvider()
      registry       <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      client <- LLMConnect.getClient(providerConfig)
    } yield client
  // end snippet

  val question: String = "How long does delivery to London take?"

  /** The model this part's demo talks to when it runs without `--live`. */
  def scripted(): ScriptedLLMClient =
    ScriptedLLMClient
      .sequence(
        Reply.text("Delivery to London takes two working days."),
        Reply.text("Orders to London arrive in two working days.")
      )
      .withChunkSize(6)

  def main(args: Array[String]): Unit =
    TutorialRunner.run(args, scripted()) { client =>
      for {
        answer <- ask(client, question)
        _ = println(s"ask:       $answer")
        _ = print("streaming: ")
        streamed <- askStreaming(client, question)(text => print(text))
      } yield {
        println()
        streamed
      }
    }
}

/** Runs a part's demo against its scripted client, or with `--live` against the configured provider. */
object TutorialRunner {
  import org.llm4s.llmconnect.LLMClient
  import org.llm4s.types.Result

  def run(args: Array[String], scripted: => LLMClient)(program: LLMClient => Result[String]): Unit = {
    val client = if (args.contains("--live")) Part1FirstCall.configuredClient() else Right(scripted)
    client.flatMap(program) match {
      case Right(answer) => println(s"done: $answer")
      case Left(error)   => println(s"failed: ${error.message}")
    }
  }
}
