package org.llm4s.spring

import org.llm4s.javaapi.{ JLlmClient, JLlmClientTestFactory }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.{ Bean, Configuration }

import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

object StarterContractSpec {

  val closes = new AtomicInteger(0)

  def client(onClose: () => Unit = () => ()): LLMClient = new LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Right(Completion("id", 0L, "mock", "m", AssistantMessage("mock")))
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
    override def close(): Unit               = onClose()
  }

  /**
   * Deliberately NOT named llm4sClient/llm4sTemplate/llmHealthIndicator, so only
   * @ConditionalOnMissingBean (not bean-name overriding) can make the auto-configured beans back off.
   */
  @Configuration
  class UserBeans {
    @Bean def myOwnClient(): JLlmClient = JLlmClientTestFactory.create(client { () =>
      closes.incrementAndGet(); ()
    })
    @Bean def myOwnTemplate(c: JLlmClient): LLM4STemplate       = StarterTestSupport.template(c)
    @Bean def myOwnIndicator(c: JLlmClient): LlmHealthIndicator = StarterTestSupport.indicator(c)
  }
}

class StarterContractSpec extends AnyFlatSpec with Matchers {
  import StarterContractSpec._

  private val secret = "sk-SUPER-SECRET-KEY-1234567890"

  private val runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(classOf[Llm4sAutoConfiguration], classOf[LlmActuatorAutoConfiguration]))

  private def failureText(ctx: AssertableApplicationContext): String = {
    ctx.getStartupFailure should not be null
    Iterator
      .iterate[Throwable](ctx.getStartupFailure)(_.getCause)
      .takeWhile(_ != null)
      .map(_.getMessage)
      .mkString(" | ")
  }

  // ---- auto-configuration conditions ------------------------------------------------------

  "auto-configuration" should "build the real client, template and health indicator from kebab-case properties" in {
    runner
      .withPropertyValues(
        "llm4s.provider=openai",
        "llm4s.model=gpt-4o",
        s"llm4s.api-key=$secret",
        "llm4s.base-url=https://example.invalid/v1",
        "llm4s.context-window=32000",
        "llm4s.reserve-completion=2000"
      )
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBean(classOf[JLlmClient]) should not be null
        ctx.getBean(classOf[LLM4STemplate]) should not be null
        ctx.getBean(classOf[LlmHealthIndicator]) should not be null
        val p = ctx.getBean(classOf[Llm4sProperties])
        p.apiKey shouldBe secret
        p.baseUrl shouldBe "https://example.invalid/v1"
        p.contextWindow shouldBe 32000
        p.reserveCompletion shouldBe 2000
      }
  }

  it should "start for anthropic and for ollama (which needs no key)" in {
    runner.withPropertyValues("llm4s.provider=anthropic", "llm4s.model=claude", "llm4s.api-key=k").run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBean(classOf[JLlmClient]) should not be null
    }
    runner.withPropertyValues("llm4s.provider=ollama", "llm4s.model=llama3").run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBean(classOf[JLlmClient]) should not be null
    }
  }

  it should "back off for user-defined beans of every type, even under different bean names" in {
    runner
      .withUserConfiguration(classOf[UserBeans])
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[JLlmClient]).keySet.asScala shouldBe Set("myOwnClient")
        ctx.getBeansOfType(classOf[LLM4STemplate]).keySet.asScala shouldBe Set("myOwnTemplate")
        ctx.getBeansOfType(classOf[LlmHealthIndicator]).keySet.asScala shouldBe Set("myOwnIndicator")
      }
  }

  it should "create nothing when llm4s.enabled=false (and so needs no provider settings)" in {
    runner.withPropertyValues("llm4s.enabled=false").run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[JLlmClient]).size shouldBe 0
      ctx.getBeansOfType(classOf[LLM4STemplate]).size shouldBe 0
      ctx.getBeansOfType(classOf[LlmHealthIndicator]).size shouldBe 0
    }
  }

  it should "be enabled when llm4s.enabled is true or absent" in {
    // Without UserBeans: that configuration supplies a template of its own, so counting templates
    // under it passed whether or not the auto-configurations fired, and pinned nothing about the
    // havingValue = "true" literal. Only the auto-configurations can register beans by these names.
    val autoConfigured = Set("llm4sClient", "llm4sTemplate", "llmHealthIndicator")
    Seq(Seq("llm4s.enabled=true"), Seq.empty[String]).foreach { enabled =>
      runner.withPropertyValues(enabled ++ Seq("llm4s.provider=ollama", "llm4s.model=llama3"): _*).run { ctx =>
        withClue(enabled.mkString(", ")) {
          ctx.getStartupFailure shouldBe null
          ctx.getBeanDefinitionNames.toSet.intersect(autoConfigured) shouldBe autoConfigured
        }
      }
    }
  }

  it should "not register the health indicator when actuator classes are absent, and still start" in {
    runner
      .withClassLoader(new FilteredClassLoader(classOf[HealthIndicator]))
      .withPropertyValues("llm4s.provider=ollama", "llm4s.model=llama3")
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[LLM4STemplate]).size shouldBe 1
        ctx.getBeansOfType(classOf[LlmHealthIndicator]).size shouldBe 0
      }
  }

  it should "list both auto-configurations in AutoConfiguration.imports, all loadable" in {
    val names =
      ImportCandidates.load(classOf[org.springframework.boot.autoconfigure.AutoConfiguration], getClass.getClassLoader)
    names.getCandidates.asScala.filter(_.startsWith("org.llm4s.")).toSet shouldBe Set(
      classOf[Llm4sAutoConfiguration].getName,
      classOf[LlmActuatorAutoConfiguration].getName
    )
  }

  "LlmActuatorAutoConfiguration" should "be ordered after Llm4sAutoConfiguration, so the client bean is already defined" in {
    // The alphabetical default order already happens to put Llm4s first, so only the declared
    // ordering protects @ConditionalOnBean(JLlmClient) if either class is ever renamed.
    val meta = classOf[LlmActuatorAutoConfiguration].getAnnotation(
      classOf[org.springframework.boot.autoconfigure.AutoConfiguration]
    )
    meta.after().toSeq should contain(classOf[Llm4sAutoConfiguration])
  }

  "configuration metadata" should "document exactly the bound properties plus llm4s.enabled, with defaults that match" in {
    // The Scala sources get no annotation-processor-generated metadata, so IDE completion depends
    // on this hand-written file staying in sync with Llm4sProperties.
    val stream = getClass.getClassLoader.getResourceAsStream("META-INF/additional-spring-configuration-metadata.json")
    stream should not be null
    val json = ujson.read(scala.io.Source.fromInputStream(stream, "UTF-8").mkString)
    val documented: Map[String, ujson.Value] =
      json("properties").arr.map(p => p("name").str -> p).toMap

    def kebab(s: String) = s.replaceAll("([A-Z])", "-$1").toLowerCase
    // Leaf properties of the bean, descending into nested property classes (llm4s.async.*, llm4s.health.*).
    def leaves(cls: Class[?], prefix: String): Set[String] =
      java.beans.Introspector
        .getBeanInfo(cls, classOf[Object])
        .getPropertyDescriptors
        .toSet
        .flatMap { d =>
          val name = s"$prefix.${kebab(d.getName)}"
          if (d.getPropertyType.getName.startsWith("org.llm4s.spring")) leaves(d.getPropertyType, name) else Set(name)
        }
    val bound = leaves(classOf[Llm4sProperties], "llm4s")

    documented.keySet shouldBe bound + "llm4s.enabled"
    documented.values.foreach(p => p("description").str should not be empty)

    val defaults = new Llm4sProperties
    documented("llm4s.context-window")("defaultValue").num.toInt shouldBe defaults.contextWindow
    documented("llm4s.reserve-completion")("defaultValue").num.toInt shouldBe defaults.reserveCompletion
    documented("llm4s.enabled")("defaultValue").bool shouldBe true
  }

  // ---- validation messages ----------------------------------------------------------------

  "startup validation" should "fail with an actionable message for each missing setting" in {
    val cases = Seq(
      Seq.empty[String]                                                   -> "llm4s.provider is required",
      Seq("llm4s.provider=openai")                                        -> "llm4s.model is required",
      Seq("llm4s.provider=openai", "llm4s.model=m")                       -> "llm4s.api-key is required for OpenAI",
      Seq("llm4s.provider=anthropic", "llm4s.model=m", "llm4s.api-key= ") -> "llm4s.api-key is required for Anthropic",
      Seq("llm4s.provider=  ", "llm4s.model=m")                           -> "llm4s.provider is required",
      Seq("llm4s.provider=nope", "llm4s.model=m")                         -> "Unknown provider: 'nope'"
    )
    cases.foreach { case (props, expected) =>
      runner.withPropertyValues(props: _*).run(ctx => failureText(ctx) should include(expected))
    }
  }

  it should "reject non-positive context windows and a reserve that does not fit" in {
    val base = Seq("llm4s.provider=ollama", "llm4s.model=m")
    runner
      .withPropertyValues(base :+ "llm4s.context-window=0": _*)
      .run(ctx => failureText(ctx) should include("llm4s.context-window must be positive"))
    runner
      .withPropertyValues(base ++ Seq("llm4s.context-window=1000", "llm4s.reserve-completion=1000"): _*)
      .run(ctx => failureText(ctx) should include("llm4s.reserve-completion"))
    runner
      .withPropertyValues(base :+ "llm4s.reserve-completion=-1": _*)
      .run(ctx => failureText(ctx) should include("llm4s.reserve-completion"))
  }

  it should "treat null properties (programmatic misuse) as missing, not as a NullPointerException" in {
    val p = new Llm4sProperties
    p.provider = null
    p.model = null
    val r = ProviderConfigParser.parse(p)
    r.isFailure shouldBe true
    r.getError().getMessage should include("llm4s.provider is required")

    p.provider = "openai"
    p.model = "m"
    p.apiKey = null
    ProviderConfigParser.parse(p).getError().getMessage should include("api-key is required")
  }

  // ---- secrets ----------------------------------------------------------------------------

  "the API key" should "not appear in startup failure text, bean toString, health details or config toString" in {
    // a failing context that DOES have a key set
    runner.withPropertyValues("llm4s.provider=openai", s"llm4s.api-key=$secret").run { ctx =>
      (failureText(ctx) should not).include(secret)
    }
    runner
      .withPropertyValues("llm4s.provider=openai", "llm4s.model=gpt-4o", s"llm4s.api-key=$secret")
      .run { ctx =>
        (ctx.getBean(classOf[Llm4sProperties]).toString should not).include(secret)
        (ctx.getBean(classOf[JLlmClient]).toString should not).include(secret)
        val details = ctx.getBean(classOf[LlmHealthIndicator]).health().getDetails.asScala.values.map(_.toString)
        details.exists(_.contains(secret)) shouldBe false
      }
    val p = new Llm4sProperties
    p.provider = "openai"; p.model = "m"; p.apiKey = secret
    (ProviderConfigParser.parse(p).get().toString should not).include(secret)
  }

  // ---- lifecycle --------------------------------------------------------------------------

  "context shutdown" should "close the JLlmClient bean exactly once" in {
    closes.set(0)
    runner.withUserConfiguration(classOf[UserBeans]).run(ctx => ctx.getBean(classOf[JLlmClient]) should not be null)
    closes.get shouldBe 1
  }

  it should "have a destroy method for the auto-configured client bean (Spring infers close())" in {
    runner.withPropertyValues("llm4s.provider=ollama", "llm4s.model=m").run { ctx =>
      val bf = ctx.getSourceApplicationContext.getBeanFactory
        .asInstanceOf[org.springframework.beans.factory.config.ConfigurableListableBeanFactory]
      bf.getMergedBeanDefinition("llm4sClient").getDestroyMethodName shouldBe AbstractBeanDefinition.INFER_METHOD
    }
  }

  // ---- template ---------------------------------------------------------------------------

  "LLM4STemplate" should "serve many threads sharing one instance without crosstalk" in {
    val calls = new AtomicInteger(0)
    val echo = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
        calls.incrementAndGet()
        val text = c.messages.last.content
        Right(Completion("id", 0L, text, "m", AssistantMessage(text)))
      }
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
    }
    val template = StarterTestSupport.template(JLlmClientTestFactory.create(echo))
    val threads  = 16
    val each     = 200
    val pool     = java.util.concurrent.Executors.newFixedThreadPool(threads)
    val start    = new java.util.concurrent.CountDownLatch(1)
    try {
      val futures = (0 until threads).map { t =>
        pool.submit(new java.util.concurrent.Callable[Boolean] {
          override def call(): Boolean = {
            start.await()
            (0 until each).forall(i => template.complete(s"t$t-$i") == s"t$t-$i")
          }
        })
      }
      start.countDown()
      futures.foreach(_.get(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true)
      calls.get shouldBe threads * each
    } finally pool.shutdownNow()
  }

  it should "hand the caller's options and conversation to the client unchanged" in {
    val seenOptions = new java.util.concurrent.atomic.AtomicReference[CompletionOptions]()
    val seenConv    = new java.util.concurrent.atomic.AtomicReference[Conversation]()
    val capturing = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
        seenConv.set(c); seenOptions.set(o)
        Right(Completion("id", 0L, "r", "m", AssistantMessage("r")))
      }
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
    }
    val template = StarterTestSupport.template(JLlmClientTestFactory.create(capturing))
    val conv     = Conversation(Seq(UserMessage("hi")))
    val opts     = CompletionOptions().withTemperature(0.123)
    template.complete(conv, opts) shouldBe "r"
    (seenOptions.get() should be).theSameInstanceAs(opts)
    seenConv.get() shouldBe conv
  }
}
