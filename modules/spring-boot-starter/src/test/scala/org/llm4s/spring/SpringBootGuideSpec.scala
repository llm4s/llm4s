package org.llm4s.spring

import org.llm4s.error.NetworkError
import org.llm4s.javaapi.{ JLlmClient, JLlmClientTestFactory, LlmException }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.springframework.beans.BeanWrapperImpl
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.convert.DurationStyle
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.{ Bean, Configuration }
import org.springframework.core.io.ByteArrayResource

import java.io.{ ByteArrayOutputStream, PrintStream }
import java.util.concurrent.{ ConcurrentLinkedQueue, TimeUnit }
import scala.jdk.CollectionConverters._
import scala.util.Using

object SpringBootGuideSpec {

  /** A client that records the conversations it is sent and answers `answer`. */
  final class Recording(answer: String) extends LLMClient {
    val sent: ConcurrentLinkedQueue[Conversation] = new ConcurrentLinkedQueue[Conversation]()
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
      sent.add(c)
      Right(Completion("id", 0L, answer, "m", AssistantMessage(answer)))
    }
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  val recording: Recording = new Recording("summary")

  private val failing: LLMClient = new LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Left(NetworkError("the network is down", None, "mock://test"))
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  /** The user's own client, the way a test configuration supplies one. */
  @Configuration
  class RecordingClientConfig {
    @Bean
    def recordingClient(): JLlmClient = JLlmClientTestFactory.create(recording)
  }

  @Configuration
  class FailingClientConfig {
    @Bean
    def failingClient(): JLlmClient = JLlmClientTestFactory.create(failing)
  }
}

/**
 * `docs/guide/spring-boot.md`, compiled and run.
 *
 * The Java in the guide is real Java: `SummaryService.java`, `OtherProviderConfig.java` and `SpringGuideSnippets.java`
 * in `src/test/java` hold its blocks, and the spec checks that each block of the guide appears in them word for
 * word. The properties and YAML blocks are read from the guide and used to configure a real application context, and
 * the tables are compared with the starter's code: every property of `Llm4sProperties` is listed with the default it has,
 * and every provider with the URL it uses. If one of them stops being true the guide is teaching something that does
 * not work: change the guide and the code together.
 */
class SpringBootGuideSpec extends AnyWordSpec with Matchers {
  import SpringBootGuideSpec._

  private val GuideFile = "docs/guide/spring-boot.md"

  private lazy val guide: String = StarterGuideDocs.read(GuideFile, GuideFile)

  private def blocksIn(language: String): List[String] = StarterGuideDocs.blocksIn(guide, language)

  private def squash(text: String): String = StarterGuideDocs.squash(text)

  private lazy val javaSources: String = Seq("SummaryService", "OtherProviderConfig", "SpringGuideSnippets")
    .map(name =>
      squash(StarterGuideDocs.read(GuideFile, s"modules/spring-boot-starter/src/test/java/org/llm4s/spring/$name.java"))
    )
    .mkString("\n")

  private val runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(classOf[Llm4sAutoConfiguration], classOf[LlmActuatorAutoConfiguration]))

  /** The key=value pairs of a .properties block, comments and blank lines left out. */
  private def propertyPairs(block: String): List[String] =
    block.linesIterator.map(_.trim).filter(line => line.nonEmpty && !line.startsWith("#")).toList

  private def yamlPairs(block: String): List[String] = {
    val factory = new YamlPropertiesFactoryBean
    factory.setResources(new ByteArrayResource(block.getBytes("UTF-8")))
    val props = factory.getObject
    props.stringPropertyNames().asScala.toList.sorted.map(key => s"$key=${props.getProperty(key)}")
  }

  /** What a snippet printed to System.err; the streams are process-wide, so the capture is serialised. */
  private def printedToErr(body: => Unit): String = SpringBootGuideSpec.synchronized {
    val err = new ByteArrayOutputStream
    val redirect = new AutoCloseable {
      private val old = System.err
      System.setErr(new PrintStream(err, true, "UTF-8"))
      def close(): Unit = System.setErr(old)
    }
    Using.resource(redirect)(_ => body)
    err.toString("UTF-8").replace("\r\n", "\n") // `println` writes the platform's separator
  }

  private def tableRows(from: String, to: String): List[List[String]] = {
    val section = guide.substring(guide.indexOf(from), guide.indexOf(to))
    section.linesIterator
      .filter(_.startsWith("| `"))
      .map(_.split("\\|").map(_.trim).filter(_.nonEmpty).toList)
      .toList
  }

  private def unquoted(cell: String): String = cell.stripPrefix("`").stripSuffix("`")

  private def kebab(camel: String): String = camel.replaceAll("([A-Z])", "-$1").toLowerCase
  private def camel(kebabCase: String): String =
    kebabCase.split('-').toList match {
      case head :: tail => head + tail.map(_.capitalize).mkString
      case Nil          => kebabCase
    }

  /** Every settable property of the bean, as a dotted path, descending into the nested `async` and `health` objects. */
  private def propertyPaths(bean: AnyRef, prefix: String = ""): List[String] = {
    val wrapper = new BeanWrapperImpl(bean)
    wrapper.getPropertyDescriptors.toList
      .filter(d => d.getName != "class" && d.getWriteMethod != null)
      .flatMap { d =>
        val value = wrapper.getPropertyValue(d.getName)
        if (value != null && value.getClass.getName.startsWith("org.llm4s.spring."))
          propertyPaths(value, prefix + d.getName + ".")
        else List(prefix + d.getName)
      }
  }

  private def assertGuideConfigurationApplied(ctx: AssertableApplicationContext): Unit = {
    ctx.getStartupFailure shouldBe null
    val props = ctx.getBean(classOf[Llm4sProperties])
    props.provider shouldBe "openai"
    props.model shouldBe "gpt-4o"
    props.apiKey shouldBe "sk-from-env" // the ${OPENAI_API_KEY} placeholder, resolved
    ctx.getBeansOfType(classOf[JLlmClient]).size() shouldBe 1
    ctx.getBeansOfType(classOf[LLM4STemplate]).size() shouldBe 1
  }

  "The properties block of the guide" should {

    "configure a real context: a client, a template, an executor and a health indicator" in {
      val pairs = propertyPairs(blocksIn("properties").head) :+ "OPENAI_API_KEY=sk-from-env"
      runner.withPropertyValues(pairs: _*).run(assertGuideConfigurationApplied)
    }

    "be the only flat properties block, and no block keeps a comment on a value line, which a .properties file would read as part of the value" in {
      blocksIn("properties").filterNot(_.contains("llm4s.providers.")).size shouldBe 1
      blocksIn("properties").foreach(
        _.linesIterator.filterNot(_.trim.startsWith("#")).foreach(_ should not include "#")
      )
    }
  }

  "The YAML block of the guide" should {
    "configure the same context as the properties block" in {
      val pairs = yamlPairs(blocksIn("yaml").head) :+ "OPENAI_API_KEY=sk-from-env"
      yamlPairs(blocksIn("yaml").head).toSet shouldBe propertyPairs(blocksIn("properties").head).toSet
      runner.withPropertyValues(pairs: _*).run(assertGuideConfigurationApplied)
    }
  }

  "The beans table of the guide" should {
    "name the beans the starter registers" in {
      val names = tableRows("## What the starter gives you", "Set `llm4s.enabled=false`").map(row => unquoted(row.head))
      names.toSet shouldBe Set("llm4sClient", "llm4sTaskExecutor", "llm4sTemplate", "llmHealthIndicator")

      val pairs = propertyPairs(blocksIn("properties").head) :+ "OPENAI_API_KEY=sk-from-env"
      runner
        .withPropertyValues(pairs: _*)
        .run(ctx => names.foreach(name => withClue(name)(ctx.containsBean(name) shouldBe true)))
    }

    "say that llm4s.enabled=false registers none of them" in {
      guide should include("Set `llm4s.enabled=false` to switch all of them off")
      runner.withPropertyValues("llm4s.enabled=false").run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[JLlmClient]).size() shouldBe 0
        ctx.getBeansOfType(classOf[LLM4STemplate]).size() shouldBe 0
        ctx.getBeansOfType(classOf[java.util.concurrent.ExecutorService]).size() shouldBe 0
      }
    }
  }

  "The properties table of the guide" should {

    val rows = tableRows("### Properties", "### Which providers")

    "list every property of Llm4sProperties, and no other" in {
      val declared =
        propertyPaths(new Llm4sProperties).map(path => "llm4s." + path.split('.').map(kebab).mkString(".")).toSet
      rows.map(row => unquoted(row.head)).toSet shouldBe declared
    }

    "give each property the default the class has" in {
      val wrapper = new BeanWrapperImpl(new Llm4sProperties)
      rows.foreach { row =>
        val name     = unquoted(row.head)
        val path     = name.stripPrefix("llm4s.").split('.').map(camel).mkString(".")
        val declared = if (row(1) == "empty") "" else unquoted(row(1))
        withClue(s"$name: ") {
          wrapper.getPropertyValue(path) match {
            case duration: java.time.Duration => duration shouldBe DurationStyle.detectAndParse(declared)
            case other                        => String.valueOf(other) shouldBe declared
          }
        }
      }
    }
  }

  "The providers table of the guide" should {

    // the header cell is `llm4s.provider` too, so rows are the ones whose first cell is a provider name
    val rows = tableRows("### Which providers", "Any other value stops the context")
      .filterNot(row => unquoted(row.head).startsWith("llm4s."))

    def properties(provider: String, apiKey: String): Llm4sProperties = {
      val p = new Llm4sProperties
      p.provider = provider
      p.model = "some-model"
      p.apiKey = apiKey
      p
    }

    "list exactly the providers the starter supports" in {
      rows.map(row => unquoted(row.head)).toSet shouldBe Set("openai", "anthropic", "ollama")
    }

    "give each provider the default base URL the table states" in {
      rows.foreach { row =>
        val config = ProviderConfigParser.parse(properties(unquoted(row.head), "some-key"))
        withClue(row.head)(config.get().endpointUrl shouldBe Some(unquoted(row(2))))
      }
    }

    "require a key exactly where the table says it is required" in {
      rows.foreach { row =>
        val result = ProviderConfigParser.parse(properties(unquoted(row.head), ""))
        withClue(row.head)(result.isSuccess shouldBe (row(1) == "not used"))
      }
    }

    "stop the context for any other flat provider with the message the guide quotes" in {
      val quoted =
        "Unknown provider: '<name>'. The flat llm4s.provider key supports openai, anthropic and ollama; " +
          "configure any other provider under llm4s.providers.<name>"
      squash(guide) should include(squash(quoted))
      runner.withPropertyValues("llm4s.provider=gemini", "llm4s.model=gemini-2.0-flash", "llm4s.api-key=k").run { ctx =>
        ctx.getStartupFailure should not be null
        val messages = Iterator
          .iterate[Throwable](ctx.getStartupFailure)(_.getCause)
          .takeWhile(_ != null)
          .map(_.getMessage)
          .mkString(" | ")
        messages should include(quoted.replace("<name>'", "gemini'"))
        messages should include("Providers on the classpath:")
      }
    }
  }

  "The llm4s.providers blocks of the guide" should {

    val blocks = blocksIn("properties").filter(_.contains("llm4s.providers."))

    "show Gemini, Azure OpenAI and an OpenAI-compatible server" in {
      blocks
        .map(block => propertyPairs(block).find(_.matches("llm4s\\.providers\\.[^.]+\\.provider=.*")))
        .flatten
        .map(
          _.split('=')(1)
        ) shouldBe List("gemini", "azure", "openai-compatible")
    }

    "each start a context whose client and health indicator are the provider and model the block names" in {
      val placeholders = List("GOOGLE_API_KEY=gk-from-env", "AZURE_OPENAI_API_KEY=az-from-env")
      blocks.foreach { block =>
        val pairs   = propertyPairs(block)
        val section = pairs.collectFirst { case p if p.startsWith("llm4s.providers.provider=") => p.split('=')(1) }.get
        def value(key: String): String =
          pairs.collectFirst { case p if p.startsWith(s"llm4s.providers.$section.$key=") => p.split("=", 2)(1) }.get
        withClue(s"section $section: ") {
          runner.withPropertyValues(pairs ++ placeholders: _*).run { ctx =>
            ctx.getStartupFailure shouldBe null
            ctx.getBeansOfType(classOf[JLlmClient]).size() shouldBe 1
            ctx.getBeansOfType(classOf[LLM4STemplate]).size() shouldBe 1
            val health = ctx.getBean(classOf[LlmHealthIndicator]).health()
            health.getDetails.get("provider") shouldBe value("provider")
            health.getDetails.get("model") shouldBe value("model")
            (health.toString should not).include("from-env")
          }
        }
      }
    }

    "stop the context with a message naming the property when a required one is missing" in {
      runner.withPropertyValues("llm4s.model=gpt-4o").run { ctx =>
        ctx.getStartupFailure should not be null
        ctx.getStartupFailure.getMessage should include("llm4s.provider")
      }
      runner.withPropertyValues("llm4s.provider=openai", "llm4s.model=gpt-4o").run { ctx =>
        ctx.getStartupFailure should not be null
        ctx.getStartupFailure.getMessage should include("api-key")
      }
    }
  }

  "The Java blocks of the guide" should {

    "each appear word for word in the Java the spec compiles, except the one marked illustrative" in {
      val (illustrative, compiled) = blocksIn("java").partition(_.trim.startsWith("// illustrative"))
      illustrative.size shouldBe 1
      compiled should not be empty
      compiled.foreach { block =>
        withClue(s"This block of $GuideFile is not in the Java the spec compiles:\n$block\n") {
          javaSources should include(squash(block))
        }
      }
    }
  }

  "The service of the guide" should {
    "build its prompt from the text and return the reply" in {
      runner.withUserConfiguration(classOf[RecordingClientConfig], classOf[SummaryService]).run { ctx =>
        ctx.getStartupFailure shouldBe null
        recording.sent.clear()

        ctx.getBean(classOf[SummaryService]).summarise("the meeting ran long") shouldBe "summary"

        recording.sent.asScala.toList.map(_.messages.map(m => (m.role.name, m.content))) shouldBe
          List(List(("user", "Summarise in one sentence: the meeting ran long")))
      }
    }
  }

  "The asynchronous block of the guide" should {
    "complete the future with the reply, from the executor" in {
      runner.withUserConfiguration(classOf[RecordingClientConfig]).run { ctx =>
        recording.sent.clear()

        val reply = SpringGuideSnippets.asynchronous(ctx.getBean(classOf[LLM4STemplate]), "hello")

        reply.get(10, TimeUnit.SECONDS) shouldBe "summary"
        recording.sent.asScala.toList.map(_.messages.map(_.content)) shouldBe List(List("Translate to French: hello"))
      }
    }
  }

  "The failure block of the guide" should {
    "print the message of the LlmException and return the failure as a value from tryComplete" in {
      runner.withUserConfiguration(classOf[FailingClientConfig]).run { ctx =>
        val template = ctx.getBean(classOf[LLM4STemplate])

        val printed = printedToErr(SpringGuideSnippets.whenACallFails(template))
        val result  = SpringGuideSnippets.whenACallFails(template)

        printed should include("the network is down")
        result.isFailure shouldBe true
        intercept[LlmException](template.complete("What is 2+2?")).getMessage should include("the network is down")
      }
    }
  }

  "The other-provider block of the guide" should {
    "give a context its own client, with no llm4s.provider property, and keep the template and health indicator" in {
      runner.withUserConfiguration(classOf[OtherProviderConfig]).run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[JLlmClient]).size() shouldBe 1
        ctx.getBeansOfType(classOf[LLM4STemplate]).size() shouldBe 1
        ctx.getBeansOfType(classOf[LlmHealthIndicator]).size() shouldBe 1
      }
    }
  }

  "The health section of the guide" should {

    "report UP with the provider, the model and probe=disabled, and no key, by default" in {
      val pairs = propertyPairs(blocksIn("properties").head) :+ "OPENAI_API_KEY=sk-from-env"
      runner.withPropertyValues(pairs: _*).run { ctx =>
        val health = ctx.getBean(classOf[LlmHealthIndicator]).health()

        health.getStatus shouldBe Status.UP
        health.getDetails.asScala.toMap shouldBe Map("provider" -> "openai", "model" -> "gpt-4o", "probe" -> "disabled")
        (health.toString should not).include("sk-from-env")
      }
    }

    "report UP with probe=ok after one real call when llm4s.health.probe is true" in {
      runner.withUserConfiguration(classOf[RecordingClientConfig]).withPropertyValues("llm4s.health.probe=true").run {
        ctx =>
          recording.sent.clear()

          val health = ctx.getBean(classOf[LlmHealthIndicator]).health()

          health.getStatus shouldBe Status.UP
          health.getDetails.asScala.get("probe") shouldBe Some("ok")
          recording.sent.size() shouldBe 1
      }
    }

    "report DOWN with probe=failed and an error when the probe call fails" in {
      runner.withUserConfiguration(classOf[FailingClientConfig]).withPropertyValues("llm4s.health.probe=true").run {
        ctx =>
          val health = ctx.getBean(classOf[LlmHealthIndicator]).health()

          health.getStatus shouldBe Status.DOWN
          health.getDetails.asScala.get("probe") shouldBe Some("failed")
          health.getDetails.asScala.get("error").map(_.toString).getOrElse("") should include("the network is down")
      }
    }
  }
}
