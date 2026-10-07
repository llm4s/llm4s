package org.llm4s.samples.guides

import org.llm4s.agent.Agent
import org.llm4s.agent.memory.SimpleMemoryManager
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.RAG
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.toolapi.{ ObjectSchema, PropertyDefinition, Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/**
 * Compiles and runs the llm4s snippets of `docs/guide/migrating-from-langchain4j.md`.
 *
 * Each test holds one snippet of the guide, between the "from here" and "to here" comments, so the page cannot drift
 * from the API. The client is a script, not a model: it returns the replies each test hands it, and records what it
 * was asked, so the checks are on what llm4s sends and returns, never on what a model says. The configuration and
 * RAG snippets need a provider, so they are compiled (as the bodies of `def`s) but not run.
 */
class ComparisonGuideSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def completionOf(message: AssistantMessage): Completion =
    Completion(
      id = "script",
      created = 0L,
      content = message.content,
      model = "script",
      message = message,
      toolCalls = message.toolCalls.toList
    )

  /** Answers every request with `reply(conversation)` and records the requests. */
  private class ScriptedClient(reply: Conversation => AssistantMessage) extends LLMClient {
    private val seen = new ConcurrentLinkedQueue[(Conversation, CompletionOptions)]()

    def requests: Seq[(Conversation, CompletionOptions)] = seen.asScala.toSeq

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      seen.add((conversation, options))
      Right(completionOf(reply(conversation)))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 1. A chat call
  // ---------------------------------------------------------------------------------------------------------------

  // The snippet, from here ...
  def connect(): Result[LLMClient] = for {
    providerConfig  <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerConfig)
  } yield client
  // ... to here (compiled, not run: it needs a configured provider).

  "The chat snippet" should "send a system and a user message and return the reply text" in {
    val client = new ScriptedClient(_ => AssistantMessage("Scala is a JVM language."))

    // The snippet, from here ...
    val reply: Result[String] = for {
      conversation <- Conversation.fromPrompts("You answer in one sentence.", "What is Scala?")
      completion   <- client.complete(conversation)
    } yield completion.content
    // ... to here.

    reply.value shouldBe "Scala is a JVM language."
    val sent = client.requests.map(_._1.messages)
    sent should have size 1
    sent.head.map(m => (m.role, m.content)) shouldBe Seq(
      (MessageRole.System, "You answer in one sentence."),
      (MessageRole.User, "What is Scala?")
    )
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 2. Structured output
  // ---------------------------------------------------------------------------------------------------------------

  // The snippet, from here ...
  case class Person(name: String, age: Int)
  object Person { implicit val rw: ReadWriter[Person] = macroRW }

  lazy val personSchema: ObjectSchema[Person] = Schema
    .`object`[Person]("A person")
    .withRequiredField("name", Schema.string("The person's name"))
    .withRequiredField("age", Schema.integer("The person's age in years"))
  // ... to here.

  "The structured output snippet" should "return a typed value and send the schema to the provider" in {
    val client = new ScriptedClient(_ => AssistantMessage("""{"name":"Ada","age":36}"""))

    // The snippet, from here ...
    val person: Result[Person] = for {
      conversation <- Conversation.fromPrompts("Extract the person.", "Ada Lovelace is 36.")
      parsed       <- client.completeStructured[Person](conversation, personSchema)
    } yield parsed
    // ... to here.

    person.value shouldBe Person("Ada", 36)

    // What the guide says llm4s sends: a JSON-Schema response format built from the schema, strict.
    val options = client.requests.map(_._2)
    options should have size 1
    options.head.responseFormat match {
      case Some(ResponseFormat.JsonSchema(schema, _, strict)) =>
        strict shouldBe true
        schema.obj("required").arr.map(_.str).toSet shouldBe Set("name", "age")
        schema.obj("properties").obj.keySet shouldBe Set("name", "age")
      case other => fail(s"expected a JSON-Schema response format, got $other")
    }
  }

  it should "send every property as required, optional ones included (strict mode)" in {
    val client = new ScriptedClient(_ => AssistantMessage("""{"name":"Ada","age":36}"""))
    val withOptional = Schema
      .`object`[Person]("A person")
      .withRequiredField("name", Schema.string("The person's name"))
      .withProperty(PropertyDefinition("nickname", Schema.string("An optional nickname"), required = false))

    val _ = for {
      conversation <- Conversation.fromPrompts("Extract the person.", "Ada Lovelace is 36.")
      parsed       <- client.completeStructured[Person](conversation, withOptional)
    } yield parsed

    client.requests.head._2.responseFormat match {
      case Some(ResponseFormat.JsonSchema(schema, _, _)) =>
        schema.obj("required").arr.map(_.str).toSet shouldBe Set("name", "nickname")
      case other => fail(s"expected a JSON-Schema response format, got $other")
    }
  }

  it should "fail with a Left, not an exception, when the reply is not the requested shape" in {
    val client = new ScriptedClient(_ => AssistantMessage("I could not find a person."))

    val person = for {
      conversation <- Conversation.fromPrompts("Extract the person.", "Nobody here.")
      parsed       <- client.completeStructured[Person](conversation, personSchema)
    } yield parsed

    person.isLeft shouldBe true
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 3. A tool and an agent
  // ---------------------------------------------------------------------------------------------------------------

  // The snippet, from here ...
  case class Forecast(city: String, summary: String)
  object Forecast { implicit val rw: ReadWriter[Forecast] = macroRW }

  private lazy val forecastSchema = Schema
    .`object`[Map[String, Any]]("Forecast parameters")
    .withProperty(Schema.property("city", Schema.string("The city to look up")))

  lazy val forecastTool = ToolBuilder[Map[String, Any], Forecast](
    name = "get_forecast",
    description = "Returns the forecast for a city",
    schema = forecastSchema
  ).withHandler(extractor => extractor.getString("city").map(city => Forecast(city, "sunny"))).buildSafe()
  // ... to here.

  "The tool snippet" should "run through an agent: the model asks for the tool and the answer uses its result" in {
    val client = new ScriptedClient(conversation =>
      conversation.messages.filter(_.role == MessageRole.Tool).lastOption.map(_.content) match {
        case None =>
          AssistantMessage(
            contentOpt = None,
            toolCalls = Seq(ToolCall("call-1", "get_forecast", ujson.Obj("city" -> "Oslo")))
          )
        case Some(output) => AssistantMessage(s"The tool returned $output")
      }
    )

    // The snippet, from here ...
    val result = for {
      tool  <- forecastTool
      agent <- Agent.builder("forecast-agent", client).withTools(new ToolRegistry(Seq(tool))).build()
      state <- agent.run("What is the weather in Oslo?")
    } yield state
    // ... to here.

    val answer = result.value.answer.getOrElse(fail(s"the run did not complete: ${result.value.status}"))
    answer should include("Oslo")
    answer should include("sunny")
    // The model was offered the tool: the agent sent its definition with the request.
    client.requests.head._2.tools.map(_.name) should contain("get_forecast")
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 4. Memory
  // ---------------------------------------------------------------------------------------------------------------

  "The memory snippet" should "record facts and return them as context for a query that shares a word" in {
    // The snippet, from here ...
    val context: Result[String] = for {
      m1  <- SimpleMemoryManager.empty.recordUserFact("Prefers Scala over Java", Some("user-1"), Some(0.9))
      m2  <- m1.recordKnowledge("Scala 3 has opaque types", "docs")
      ctx <- m2.getRelevantContext("Scala")
    } yield ctx
    // ... to here.

    context.value shouldBe
      "# Retrieved Context\n## Relevant Knowledge\n- Scala 3 has opaque types\n\n## User Preferences\n- Prefers Scala over Java"
  }

  it should "return nothing for a query that shares no word with a memory (the in-memory store matches keywords)" in {
    val context = for {
      m1  <- SimpleMemoryManager.empty.recordUserFact("Prefers Scala over Java", Some("user-1"), Some(0.9))
      ctx <- m1.getRelevantContext("What does the user like?")
    } yield ctx

    context.value shouldBe ""
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 5. A RAG pipeline
  // ---------------------------------------------------------------------------------------------------------------

  // The snippet, from here ...
  def buildRag(): Result[RAG] = for {
    registryService <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registryService
    rag <- RAG.builder().withEmbeddings("ollama").build
  } yield rag

  def askTheDocs(rag: RAG): Result[Seq[String]] = for {
    _       <- rag.ingestText("Opaque types hide their representation outside the defining scope.", "scala-3")
    results <- rag.query("What are opaque types?")
  } yield results.map(_.content)
  // ... to here (compiled, not run: embedding needs a provider).

  "The RAG snippet" should "compile against the public API" in {
    // `buildRag` and `askTheDocs` are not called: they would need an embedding provider. That they type-check is
    // the point of this test, and the references keep the compiler from treating them as unused.
    val builders: Seq[() => Result[RAG]]        = Seq(() => buildRag())
    val askers: Seq[RAG => Result[Seq[String]]] = Seq(askTheDocs)
    (builders.size + askers.size) shouldBe 2
  }

  "The configuration snippet" should "compile against the public API" in {
    val connectors: Seq[() => Result[LLMClient]] = Seq(() => connect())
    connectors should have size 1
  }
}
