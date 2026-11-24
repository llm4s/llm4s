package org.llm4s.samples.tutorial.supportassistant

import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

/**
 * Part 4 of the "Build a support assistant" tutorial (`docs/tutorials/support-assistant/part-4-help-centre.md`):
 * answers from Acme Shop's help-centre articles with a RAG pipeline (ingest, embed, retrieve, cite), and the help
 * centre as a tool of the Part 3 agent.
 *
 * Each `// snippet:` region is a Scala block of the page, word for word; `Part4Spec` runs the code and compares the
 * regions with the page.
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part4HelpCentre"          # scripted, no key
 *   sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part4HelpCentre --live"   # your provider
 * }}}
 */
object Part4HelpCentre {

  // snippet: articles
  val articles: Map[String, String] = Map(
    "delivery" -> "Standard delivery takes 2 to 4 working days. Express delivery arrives the next working day.",
    "returns"  -> "You can return an unused item within 30 days of delivery. Print a free label from your account.",
    "refunds"  -> "A refund goes back to the original payment method within 5 working days of the return arriving.",
    "account"  -> "To change the email address of your account, open Settings and choose Email."
  )
  // end snippet

  // format: off
  // snippet: pipeline
  import org.llm4s.config.Llm4sConfig
  import org.llm4s.llmconnect.LLMClient
  import org.llm4s.llmconnect.spi.ProviderRegistry
  import org.llm4s.model.ModelRegistryService
  import org.llm4s.rag.RAG
  import org.llm4s.samples.cookbook.BagOfWordsEmbeddings
  import org.llm4s.types.Result

  val helpCentrePrompt: String =
    "You are the support assistant of Acme Shop. Answer only from the context, and cite each passage you use as [n]."

  def ingestArticles(rag: RAG): Result[Int] =
    articles.toSeq.foldLeft[Result[Int]](Right(0)) { case (chunks, (id, text)) =>
      chunks.flatMap(count => rag.ingestText(text, documentId = id).map(count + _))
    }

  def withHelpCentre[A](client: LLMClient)(use: RAG => Result[A]): Result[A] = {
    given ProviderRegistry = ProviderRegistry.default.withEmbeddingProvider(BagOfWordsEmbeddings)
    for {
      models <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = models
      config = RAG
        .builder()
        .withEmbeddings(BagOfWordsEmbeddings.id.asString, BagOfWordsEmbeddings.Model, BagOfWordsEmbeddings.Dimensions)
        .withTopK(2)
        .withSystemPrompt(helpCentrePrompt)
        .withLLM(client)
      rag <- RAG.build(config, _ => Right(BagOfWordsEmbeddings.config))
      result = ingestArticles(rag).flatMap(_ => use(rag))
      _      = rag.close() // closed whether `use` succeeded or not
      answer <- result
    } yield answer
  }
  // end snippet
  // format: on

  // snippet: cite
  case class CitedAnswer(text: String, sources: Seq[String])

  def answerFromHelpCentre(rag: RAG, question: String): Result[CitedAnswer] =
    rag.queryWithAnswer(question).map { result =>
      CitedAnswer(result.answer, result.contexts.flatMap(_.metadata.get("docId")).distinct)
    }
  // end snippet

  // format: off
  // snippet: help-tool
  import org.llm4s.agent.Agent
  import org.llm4s.schema.*
  import org.llm4s.toolapi.{ ToolFunction, ToolRegistry }
  import upickle.default.ReadWriter

  @description("What to look up in the help centre")
  case class HelpQuery(@description("The customer's question, in a few words") question: String)
    derives SchemaOf, ReadWriter

  case class HelpPassage(article: String, text: String) derives ReadWriter

  def helpTool(rag: RAG): Result[ToolFunction[Seq[HelpPassage]]] =
    Tool.of[HelpQuery, Seq[HelpPassage]]("search_help_centre", "Passages of Acme Shop's help-centre articles") {
      query =>
        rag
          .query(query.question)
          .map(_.map(passage => HelpPassage(passage.metadata.getOrElse("docId", "unknown"), passage.content)))
          .left
          .map(_.message)
    }

  def helpAgent(client: LLMClient, rag: RAG): Result[Agent] =
    for {
      order  <- Part3Tools.orderTool
      policy <- Part3Tools.policyTool
      help   <- helpTool(rag)
      registry <- ToolRegistry.of(Seq(order, policy, help))
      agent <- Agent
        .builder("support", client)
        .withSystemPrompt("You are the support assistant of Acme Shop. Use the tools; never guess an order's status.")
        .withTools(registry)
        .build()
    } yield agent
  // end snippet
  // format: on

  val question: String = "How long does express delivery take?"

  val scriptedAnswer: String = "Express delivery arrives the next working day [1]."

  /** The model this part's demo talks to when it runs without `--live`: one answer from the retrieved context. */
  def scripted(): ScriptedLLMClient = ScriptedLLMClient.sequence(Reply.text(scriptedAnswer))

  def main(args: Array[String]): Unit =
    TutorialRunner.run(args, scripted()) { client =>
      withHelpCentre(client)(rag => answerFromHelpCentre(rag, question)).map { answer =>
        s"${answer.text} (sources: ${answer.sources.mkString(", ")})"
      }
    }
}
