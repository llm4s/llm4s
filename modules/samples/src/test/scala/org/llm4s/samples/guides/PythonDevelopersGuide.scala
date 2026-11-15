package org.llm4s.samples.guides

/**
 * The Scala snippets of `docs/guide/for-python-developers.md` (#2344), one object per section.
 *
 * Each region between `// snippet: <name>` and `// end snippet` is a block of the page, imports included, word for
 * word: `PythonDevelopersGuideSpec` runs the regions against `llm4s-testkit`'s `ScriptedLLMClient` and checks that
 * the page shows exactly these regions. The page's blocks are also compiled on their own by the DocSnippets harness
 * (`project/DocSnippets.scala`), so each region carries its own imports. The test block calls `ask` from the first
 * section, which the harness imports from here (`modules/samples/src/test/docs-snippets/skip.txt`).
 */
object PythonDevelopersGuide {

  object FirstCall {
    // snippet: first-call
    import org.llm4s.llmconnect.LLMClient
    import org.llm4s.llmconnect.model.Conversation
    import org.llm4s.types.Result

    def ask(client: LLMClient, question: String): Result[String] =
      for {
        conversation <- Conversation.fromPrompts("You answer in one sentence.", question)
        completion   <- client.complete(conversation)
      } yield completion.content
    // end snippet
  }

  object TypedOutput {
    // snippet: typed-output
    import org.llm4s.llmconnect.LLMClient
    import org.llm4s.llmconnect.model.Conversation
    import org.llm4s.schema.*
    import org.llm4s.types.Result
    import upickle.default.ReadWriter

    case class Capital(country: String, capital: String) derives SchemaOf, ReadWriter

    def capitalOf(client: LLMClient, country: String): Result[Capital] =
      for {
        conversation <- Conversation.fromPrompts("Answer with the country and its capital.", s"Capital of $country?")
        capital      <- client.completeStructuredOf[Capital](conversation)
      } yield capital
    // end snippet
  }

  object ATool {
    // snippet: tool
    import org.llm4s.toolapi.{ SafeParameterExtractor, Schema, ToolBuilder, ToolFunction }
    import org.llm4s.types.Result

    def searchDatabase: Result[ToolFunction[Map[String, Any], String]] = {
      val schema = Schema
        .`object`[Map[String, Any]]("A search of the customer database")
        .withRequiredField("query", Schema.string("What to search for"))
        .withOptionalField("limit", Schema.integer("The most records to return (default 10)"))

      def handler(params: SafeParameterExtractor): Either[String, String] =
        for {
          query <- params.getString("query")
          limit <- params.getOptionalInt("limit").left.map(_.getMessage)
        } yield s"Found ${limit.getOrElse(10)} results for '$query'"

      ToolBuilder[Map[String, Any], String](
        "search_database",
        "Search the customer database for records matching the query.",
        schema
      ).withHandler(handler).buildSafe()
    }
    // end snippet
  }

  object AgentLoop {
    // snippet: agent
    import org.llm4s.agent.{ Agent, AgentResult, AgentStatus }
    import org.llm4s.llmconnect.LLMClient
    import org.llm4s.toolapi.ToolRegistry
    import org.llm4s.types.Result

    def describe(result: AgentResult): String =
      result.status match {
        case AgentStatus.Completed(answer)  => answer
        case AgentStatus.Blocked(_, reason) => s"Blocked: $reason"
        case other                          => s"Stopped: $other"
      }

    def twoTurns(client: LLMClient, tools: ToolRegistry): Result[Seq[String]] =
      for {
        agent  <- Agent.builder("support", client).withTools(tools).withSystemPrompt("You help support staff.").build()
        first  <- agent.run("Find customers named Ada")
        second <- agent.continueConversation(first, "Only the first two, please")
      } yield Seq(describe(first), describe(second))
    // end snippet
  }

  object Rag {
    // snippet: rag
    import org.llm4s.rag.RAG
    import org.llm4s.types.Result

    def askTheDocs(rag: RAG, question: String): Result[String] =
      for {
        _      <- rag.ingestText("Refunds are paid within 14 days of a return.", "refunds")
        _      <- rag.ingestText("Orders over 50 EUR ship for free.", "shipping")
        answer <- rag.queryWithAnswer(question)
      } yield answer.answer

    def sources(rag: RAG, question: String): Result[Seq[String]] =
      rag.query(question).map(results => results.map(_.content))
    // end snippet
  }

  object Streaming {
    // snippet: streaming
    import org.llm4s.llmconnect.LLMClient
    import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation }
    import org.llm4s.types.Result

    def streamAnswer(client: LLMClient, question: String, onText: String => Unit): Result[String] =
      for {
        conversation <- Conversation.fromPrompts("You answer in one sentence.", question)
        completion   <- client.streamComplete(conversation, CompletionOptions(), chunk => chunk.content.foreach(onText))
      } yield completion.content
    // end snippet
  }

  object Testing {
    import FirstCall.ask

    // snippet: test
    import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
    import org.scalatest.flatspec.AnyFlatSpec
    import org.scalatest.matchers.should.Matchers

    class AskSpec extends AnyFlatSpec with Matchers {
      "ask" should "return the model's answer" in {
        val client = ScriptedLLMClient.sequence(Reply.text("Paris"))

        ask(client, "Capital of France?") shouldBe Right("Paris")
        client.callCount shouldBe 1
        client.calls.head.messages.last.content shouldBe "Capital of France?"
      }
    }
    // end snippet
  }

  object Tooling {
    // snippet: connect
    import org.llm4s.config.Llm4sConfig
    import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
    import org.llm4s.model.ModelRegistryService
    import org.llm4s.types.Result

    def connect(): Result[LLMClient] =
      for {
        providerConfig  <- Llm4sConfig.defaultProvider()
        registryService <- Llm4sConfig.modelRegistryService()
        given ModelRegistryService = registryService
        client <- LLMConnect.getClient(providerConfig)
      } yield client
    // end snippet
  }
}
