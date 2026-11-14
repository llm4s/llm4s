package org.llm4s.samples.guides

import org.llm4s.agent.{ Agent, AgentResult, AgentStatus }
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin.{ LengthCheck, ProfanityFilter }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model.{ Conversation, MessageRole }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{ RAG, RAGAnswerResult, RAGConfig }
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.samples.cookbook.BagOfWordsEmbeddings
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }

/**
 * Compiles and runs the code of the five "Scala AI" pages under `docs/scala-ai/` (#2320).
 *
 * Each Scala block of a page sits here between a `// snippet: <page>/<name>` and an `// end snippet` comment, and the
 * page tests compare those regions with the page's blocks (imports aside), word for word, so neither copy can change
 * without the other. The blocks are also compiled on their own by `DocSnippets`, with the imports each block lists.
 * The model is a script (`llm4s-testkit`'s `ScriptedLLMClient`), so every snippet that makes a call runs offline; the
 * snippets that read a provider's configuration are compiled, not run.
 *
 * The Java and Kotlin blocks of `docs/scala-ai/java-kotlin.md` are excerpts of code compiled and run elsewhere:
 * `HelloLlm4s.java` (run by `JavaGuideSpec`) and `WrapClientTest.kt` (run by the Kotlin API's Gradle build); the last
 * tests check that they still are.
 */
class ScalaAiPagesSpec extends AnyFlatSpec with Matchers with EitherValues {

  // ---------------------------------------------------------------------------------------------------------------
  // docs/scala-ai/llm-framework.md
  // ---------------------------------------------------------------------------------------------------------------

  // snippet: llm-framework/connect
  def connect(): Result[LLMClient] = for {
    providerConfig  <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerConfig)
  } yield client
  // end snippet
  // (compiled, not run: it reads the application's provider section.)

  // snippet: llm-framework/ask
  def ask(client: LLMClient, question: String): Result[String] = for {
    conversation <- Conversation.fromPrompts("You answer in one sentence.", question)
    completion   <- client.complete(conversation)
  } yield completion.content
  // end snippet

  "The first call of the LLM framework page" should "send a system and a user message and return the reply" in {
    val client = ScriptedLLMClient.always(Reply.text("Scala is a JVM language."))

    ask(client, "What is Scala?").value shouldBe "Scala is a JVM language."

    val sent = client.lastCall.getOrElse(fail("no call was made")).messages
    sent.map(m => (m.role, m.content)) shouldBe Seq(
      (MessageRole.System, "You answer in one sentence."),
      (MessageRole.User, "What is Scala?")
    )
  }

  // ---------------------------------------------------------------------------------------------------------------
  // docs/scala-ai/agents.md
  // ---------------------------------------------------------------------------------------------------------------

  // snippet: agents/weather
  def weatherAgent(client: LLMClient, question: String): Result[AgentResult] = for {
    forecast <- ToolBuilder[Map[String, Any], String](
      name = "get_forecast",
      description = "Returns today's forecast for a city",
      schema = Schema
        .`object`[Map[String, Any]]("Forecast parameters")
        .withProperty(Schema.property("city", Schema.string("The city to look up")))
    ).withHandler(args => args.getString("city").map(city => s"Sunny and 18 C in $city")).buildSafe()
    agent <- Agent
      .builder("weather", client)
      .withSystemPrompt("You answer questions about the weather.")
      .withTools(new ToolRegistry(Seq(forecast)))
      .withMiddleware(new GuardrailMiddleware(input = Seq(new LengthCheck(1, 2000), new ProfanityFilter()), Nil))
      .withStreaming()
      .build()
    run <- agent.stream(ThreadId("weather-1"), question) {
      case AgentEvents.TextDelta(delta)    => print(delta.text)
      case AgentEvents.ToolCallStarted(tc) => println(s"[calling ${tc.tool}]")
      case _                               => ()
    }
    result <- run.await()
  } yield result
  // end snippet

  "The agent of the agents page" should "call the tool the model asks for and answer with its result" in {
    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("get_forecast", """{"city":"Oslo"}"""),
      Reply.text("It is sunny and 18 C in Oslo.")
    )

    val result = weatherAgent(client, "What is the weather in Oslo?").value

    result.answer shouldBe Some("It is sunny and 18 C in Oslo.")
    client.callCount shouldBe 2
    // The model was offered the tool, and the second call carried the tool's result.
    client.calls.head.options.tools.map(_.name) should contain("get_forecast")
    client.calls(1).messages.filter(_.role == MessageRole.Tool).map(_.content).mkString should include(
      "Sunny and 18 C in Oslo"
    )
    // The calls streamed, as `withStreaming()` asks.
    client.calls.map(_.streamed) shouldBe Vector(true, true)
  }

  it should "stop a question longer than the length guardrail allows before the model is called" in {
    val client = ScriptedLLMClient.always(Reply.text("never sent"))

    val result = weatherAgent(client, "x" * 2001).value

    result.status shouldBe a[AgentStatus.Blocked]
    client.callCount shouldBe 0
  }

  // ---------------------------------------------------------------------------------------------------------------
  // docs/scala-ai/rag.md
  // ---------------------------------------------------------------------------------------------------------------

  // snippet: rag/pipeline
  def docsPipeline(client: LLMClient): Result[RAG] = for {
    registryService <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registryService
    selected <- Llm4sConfig.embeddings()
    (provider, embeddingConfig) = selected
    rag <- RAG
      .builder()
      .withEmbeddings(provider)
      .withPgVector()
      .withLLM(client)
      .build(_ => Right(embeddingConfig))
  } yield rag
  // end snippet
  // (compiled, not run: it reads the embedding configuration and connects to PostgreSQL.)

  // snippet: rag/ask
  def askTheDocs(rag: RAG, question: String): Result[RAGAnswerResult] = for {
    _      <- rag.ingestText("Opaque types hide their representation outside the defining scope.", "opaque-types")
    _      <- rag.ingestText("Given instances supply the values of context parameters.", "givens")
    answer <- rag.queryWithAnswer(question)
  } yield answer
  // end snippet

  "The RAG page's pipeline" should "ingest, retrieve the matching passage and answer from it" in {
    val client = ScriptedLLMClient.respondingTo {
      case c if c.messages.exists(_.content.contains("Opaque types hide")) => Reply.text("Their representation.")
    }
    given ProviderRegistry = ProviderRegistry.default.withEmbeddingProvider(BagOfWordsEmbeddings)
    val answer = for {
      models <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = models
      config = RAGConfig()
        .withEmbeddings(BagOfWordsEmbeddings.id.asString, BagOfWordsEmbeddings.Model, BagOfWordsEmbeddings.Dimensions)
        .withTopK(1)
        .withLLM(client)
      rag <- RAG.build(config, _ => Right(BagOfWordsEmbeddings.config))
      result = askTheDocs(rag, "What do opaque types hide?")
      _      = rag.close()
      answer <- result
    } yield answer

    answer.value.answer shouldBe "Their representation."
    answer.value.contexts.map(_.content) shouldBe Seq(
      "Opaque types hide their representation outside the defining scope."
    )
  }

  "The configuration snippets" should "compile against the public API" in {
    // Not called: they need a configured provider, an embedding provider and PostgreSQL. That they type-check is the
    // point, and the references keep the compiler from treating them as unused.
    val loaders: Seq[() => Any] = Seq(() => connect(), () => docsPipeline(null), () => clientFor("claude"))
    loaders should have size 3
  }

  // ---------------------------------------------------------------------------------------------------------------
  // docs/scala-ai/providers.md
  // ---------------------------------------------------------------------------------------------------------------

  // snippet: providers/client-for
  def clientFor(section: String): Result[LLMClient] = for {
    providerConfig  <- Llm4sConfig.provider(section)
    registryService <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerConfig)
  } yield client
  // end snippet
  // (compiled, not run: it reads the application's provider sections.)

  // ---------------------------------------------------------------------------------------------------------------
  // The pages (lazy: Scala's initialisation check refuses a test registered before a later plain val)
  // ---------------------------------------------------------------------------------------------------------------

  private lazy val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")))
      .getOrElse(throw new IllegalStateException("build.sbt not found above the working directory"))

  private def read(relative: String): String =
    new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8).replace("\r\n", "\n")

  private lazy val specSource = read("modules/samples/src/test/scala/org/llm4s/samples/guides/ScalaAiPagesSpec.scala")

  private lazy val Pages = Seq("index", "llm-framework", "agents", "rag", "java-kotlin", "providers")

  private def page(name: String): String = read(s"docs/scala-ai/$name.md")

  /** Removes the common indentation and the blank lines around a block. */
  private def normalise(block: String): String = {
    val lines  = block.split("\n", -1).toList.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
    val indent = lines.filter(_.trim.nonEmpty).map(l => l.length - l.stripLeading().length).minOption.getOrElse(0)
    lines.map(l => if (l.length >= indent) l.substring(indent) else l.trim).mkString("\n")
  }

  private def regionsOf(pageName: String): List[String] =
    s"""(?ms)^[ ]*// snippet: $pageName/([a-z-]+)\\n(.*?)^[ ]*// end snippet$$""".r
      .findAllMatchIn(specSource)
      .map(m => normalise(m.group(2)))
      .toList

  private def blocksOf(markdown: String, language: String): List[String] =
    s"""(?ms)^```$language\\n(.*?)^```$$""".r.findAllMatchIn(markdown).map(m => m.group(1)).toList

  /** The page's Scala code blocks; an sbt build definition (`libraryDependencies`) is not code this spec runs. */
  private def scalaBlocksOf(markdown: String): List[String] =
    blocksOf(markdown, "scala").filterNot(_.contains("libraryDependencies"))

  private def isImport(line: String): Boolean = line.startsWith("import ")

  /** A page block without its import lines: the code a region holds. */
  private def code(block: String): String = normalise(block.linesIterator.filterNot(isImport).mkString("\n"))

  private def imports(block: String): List[String] = block.linesIterator.filter(isImport).toList

  private lazy val specImports: Set[String] = specSource.linesIterator.filter(isImport).toSet

  private lazy val Braced = """import ([\w.]+)\.\{ (.*) \}""".r
  private lazy val Single = """import ([\w.]+)\.(\w+)""".r

  /** The names an import line brings in from its package, or None for a line of another shape. */
  private def named(line: String): Option[(String, Set[String])] = line match {
    case Braced(pkg, names) => Some(pkg -> names.split(", ").toSet)
    case Single(pkg, name)  => Some(pkg -> Set(name))
    case _                  => None
  }

  /** A page import is covered when this spec imports at least the same names from the same package. */
  private def covered(pageLine: String): Boolean =
    specImports.contains(pageLine) || named(pageLine).exists { case (pkg, names) =>
      specImports.flatMap(named).exists { case (specPkg, specNames) => specPkg == pkg && names.subsetOf(specNames) }
    }

  "Each Scala AI page" should "show every snippet of this spec for it, in the same order, word for word" in {
    for (name <- Seq("llm-framework", "agents", "rag", "providers")) {
      val regions = regionsOf(name)
      withClue(s"docs/scala-ai/$name.md: ") {
        regions should not be empty
        scalaBlocksOf(page(name)).map(code) shouldBe regions
      }
    }
    Seq("index", "java-kotlin").foreach(name => scalaBlocksOf(page(name)) shouldBe empty)
  }

  it should "list, in each Scala block, only imports that this spec, which runs the snippets, also uses" in {
    for {
      name  <- Seq("llm-framework", "agents", "rag", "providers")
      block <- scalaBlocksOf(page(name))
    }
      withClue(s"docs/scala-ai/$name.md, block:\n$block\n") {
        imports(block) should not be empty
        imports(block).filterNot(covered) shouldBe empty
      }
  }

  it should "have its own description and a page under the Scala AI section" in {
    Pages.foreach { name =>
      val text = page(name)
      withClue(s"docs/scala-ai/$name.md: ") {
        text should startWith("---\n")
        text should include("\ndescription: \"")
        if (name == "index") text should include("\nhas_children: true\n")
        else text should include("\nparent: Scala AI\n")
      }
    }
  }

  "The providers page" should "render its provider list from the generated capabilities data" in {
    // docs/_data/capabilities.yml is written from the provider descriptors by `sbt generateCompatibilityMatrix`, and CI
    // fails when it is stale, so a provider module added to the build appears on the page without an edit here.
    page("providers") should include("{% for p in site.data.capabilities.providers %}")
  }

  "The Java and Kotlin page" should "show Java that HelloLlm4s.java holds word for word" in {
    val hello = GuidePages.squash(read("modules/java-api/src/test/java/org/llm4s/javaapi/HelloLlm4s.java"))
    val java  = blocksOf(page("java-kotlin"), "java")
    java should have size 1
    hello should include(GuidePages.squash(java.head))
  }

  it should "show a Kotlin call that WrapClientTest.kt holds word for word and imports" in {
    val test     = read("modules/kotlin-api/src/test/kotlin/org/llm4s/kotlin/WrapClientTest.kt")
    val calls    = blocksOf(page("java-kotlin"), "kotlin").filter(_.contains("suspend fun"))
    val kotlinIn = GuidePages.squash(test)
    calls should have size 1
    kotlinIn should include(GuidePages.squash(code(calls.head)))
    // The page imports what the test, in package org.llm4s.kotlin, needs no import for.
    imports(calls.head) shouldBe List("import org.llm4s.kotlin.Llm4s")
  }

  it should "name the artifacts with the Scala suffix where they have one, and the version placeholder" in {
    val text = page("java-kotlin")
    val xml  = blocksOf(text, "xml").mkString
    val kts  = blocksOf(text, "kotlin").filterNot(_.contains("suspend fun")).mkString
    xml should include("<artifactId>llm4s-java-api_3</artifactId>")
    kts should include("org.llm4s:llm4s-kotlin-api:")
    Seq(xml, kts).foreach(_ should include("{{ site.data.project.latest_release }}"))
  }
}

/** Whitespace collapsed to single spaces, so that indentation and line breaks do not matter in a comparison. */
private object GuidePages {
  def squash(text: String): String = text.replaceAll("\\s+", " ").trim
}
