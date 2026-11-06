package org.llm4s.spring

import org.llm4s.javaapi.{ JAgent, JLlmClient, JLlmClientTestFactory, JTool, Llm4s, Param, Tool }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.aop.framework.ProxyFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.{ Bean, Configuration }

import java.time.Instant
import java.util.concurrent.{ ConcurrentLinkedQueue, LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

object Llm4sAgentAutoConfigurationSpec {

  /**
   * A model that asks for the weather in Paris, then answers with what the tool returned; it records what it was sent.
   * Each context gets its own, as a bean, so specs running in parallel do not share one.
   */
  final class Script {
    val sent: ConcurrentLinkedQueue[Conversation] = new ConcurrentLinkedQueue[Conversation]()

    val client: LLMClient = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
        sent.add(c)
        c.messages.lastOption match {
          case Some(t: ToolMessage) =>
            Right(
              Completion(
                "id",
                Instant.EPOCH,
                s"tool said: ${t.content}",
                "m",
                AssistantMessage(s"tool said: ${t.content}")
              )
            )
          case _ =>
            val call    = ToolCall("c1", "weather", ujson.Obj("city" -> "Paris"))
            val message = AssistantMessage(contentOpt = None, toolCalls = Seq(call))
            Right(Completion("id", Instant.EPOCH, "", "m", message, toolCalls = List(call)))
        }
      }
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
    }
  }

  @Configuration
  class ScriptedClientConfig {
    @Bean
    def script(): Script = new Script

    @Bean
    def llm4sClient(script: Script): JLlmClient = JLlmClientTestFactory.create(script.client)
  }

  /** An `llm4sTaskExecutor` that counts the tasks it is given. */
  final class CountingExecutor
      extends ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable]()) {
    val submitted                              = new AtomicInteger(0)
    override def execute(task: Runnable): Unit = { submitted.incrementAndGet(); super.execute(task) }
  }

  @Configuration
  class CountingExecutorConfig {
    @Bean(name = Array(Llm4sExecutors.BeanName), destroyMethod = "shutdownNow")
    def executor(): CountingExecutor = new CountingExecutor
  }

  /** A second bean with a tool named `weather`. */
  class OtherWeather {
    @Tool(name = "weather", description = "Weather again")
    def forecast(@Param(name = "city", description = "City name") city: String): String = city
  }

  /** A tool whose parameter type a tool cannot take. */
  class BrokenTools {
    @Tool(description = "Never readable")
    def when(@Param(name = "at", description = "A date") at: java.util.Date): String = String.valueOf(at)
  }

  @Configuration
  class ProxiedToolsConfig {
    @Bean
    def proxiedWeather(): WeatherTools = {
      val factory = new ProxyFactory(new WeatherTools)
      factory.setProxyTargetClass(true)
      factory.getProxy.asInstanceOf[WeatherTools]
    }
  }

  @Configuration
  class UserAgentConfig {
    @Bean
    def myAgent(client: JLlmClient): JAgent = Llm4s.agentBuilder("mine", client).build().get()
  }

  @Configuration
  class UserToolsConfig {
    @Bean
    def myTools(): Llm4sTools = new Llm4sTools(java.util.List.of[JTool]())
  }
}

/**
 * The `llm4sTools` and `llm4sAgent` beans (#2279): `@Tool` methods of the application's beans collected, an agent over
 * them configured by `llm4s.agent.*`, each backing off for a bean of the application's own.
 */
class Llm4sAgentAutoConfigurationSpec extends AnyFlatSpec with Matchers {
  import Llm4sAgentAutoConfigurationSpec._

  private val runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(classOf[Llm4sAutoConfiguration]))
    .withUserConfiguration(classOf[ScriptedClientConfig])

  private def failure(ctx: org.springframework.boot.test.context.assertj.AssertableApplicationContext): String =
    Iterator
      .iterate[Throwable](ctx.getStartupFailure)(_.getCause)
      .takeWhile(_ != null)
      .map(e => String.valueOf(e.getMessage))
      .mkString("\n")

  "The llm4sAgent bean" should "call a @Tool method of a @Component and answer with its result" in {
    runner
      .withUserConfiguration(classOf[WeatherTools])
      .withPropertyValues("llm4s.agent.system-prompt=You report the weather.")
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[JAgent]).keySet.asScala shouldBe Set("llm4sAgent")
        ctx.getBean(classOf[Llm4sTools]).tools().asScala.map(_.name()) shouldBe Seq("weather")
        ctx.getBean(classOf[Llm4sTools]).toString shouldBe "Llm4sTools(weather)"

        val answer = ctx.getBean(classOf[JAgent]).run("Weather in Paris?").get().answer().orElseThrow()
        answer shouldBe "tool said: Sunny in Paris"
        ctx.getBean(classOf[Script]).sent.asScala.head.messages.head shouldBe SystemMessage("You report the weather.")
      }
  }

  it should "send no system prompt when llm4s.agent.system-prompt is empty, and stream when asked" in {
    runner
      .withUserConfiguration(classOf[WeatherTools])
      .withPropertyValues("llm4s.agent.streaming=true", "llm4s.agent.id=weather-bot")
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBean(classOf[JAgent]).run("Weather?").get().answer().orElseThrow() shouldBe "tool said: Sunny in Paris"
        ctx.getBean(classOf[Script]).sent.asScala.head.messages.collect { case s: SystemMessage => s } shouldBe empty
      }
  }

  it should "run runAsync turns on the llm4sTaskExecutor" in {
    runner.withUserConfiguration(classOf[WeatherTools], classOf[CountingExecutorConfig]).run { ctx =>
      val result = ctx.getBean(classOf[JAgent]).runAsync("Weather?").get(10, TimeUnit.SECONDS)
      result.answer().orElseThrow() shouldBe "tool said: Sunny in Paris"
      ctx.getBean(classOf[CountingExecutor]).submitted.get() should be > 0
    }
  }

  it should "be absent with llm4s.agent.enabled=false, the tools bean still present" in {
    runner.withUserConfiguration(classOf[WeatherTools]).withPropertyValues("llm4s.agent.enabled=false").run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[JAgent]).size shouldBe 0
      ctx.getBeansOfType(classOf[Llm4sTools]).size shouldBe 1
    }
  }

  it should "be absent, with the tools bean, when llm4s.enabled=false" in {
    runner.withUserConfiguration(classOf[WeatherTools]).withPropertyValues("llm4s.enabled=false").run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[JAgent]).size shouldBe 0
      ctx.getBeansOfType(classOf[Llm4sTools]).size shouldBe 0
    }
  }

  it should "back off for a JAgent bean of the application's own" in {
    runner.withUserConfiguration(classOf[UserAgentConfig]).run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[JAgent]).keySet.asScala shouldBe Set("myAgent")
    }
  }

  it should "fail the context, naming the property's problem, for an invalid agent id" in {
    runner.withPropertyValues("llm4s.agent.id=   ").run { ctx =>
      ctx.getStartupFailure should not be null
      failure(ctx) should include("llm4sAgent")
    }
  }

  "The llm4sTools bean" should "be empty when no bean has a @Tool method" in {
    runner.run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBean(classOf[Llm4sTools]).tools() shouldBe empty
      ctx.getBean(classOf[Llm4sTools]).toString shouldBe "Llm4sTools()"
    }
  }

  it should "back off for an Llm4sTools bean of the application's own, which the agent then offers" in {
    runner.withUserConfiguration(classOf[WeatherTools], classOf[UserToolsConfig]).run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[Llm4sTools]).keySet.asScala shouldBe Set("myTools")
      ctx.getBean(classOf[JAgent]).run("Weather?").get().answer().orElseThrow() should include("weather")
    }
  }

  it should "fail the context, naming both beans, when two tools share a name" in {
    runner
      .withUserConfiguration(classOf[WeatherTools])
      .withBean("otherWeather", classOf[OtherWeather], () => new OtherWeather)
      .run { ctx =>
        failure(ctx) should include("two @Tool methods are named 'weather', on beans")
        failure(ctx) should include("otherWeather")
      }
  }

  it should "fail the context, naming the bean, when its @Tool methods cannot be read" in {
    runner.withBean("brokenTools", classOf[BrokenTools], () => new BrokenTools).run { ctx =>
      failure(ctx) should include("the @Tool methods of bean 'brokenTools'")
      failure(ctx) should include("java.util.Date is not supported")
    }
  }

  it should "refuse a tool bean that is an AOP proxy, rather than skip its advice" in {
    runner.withUserConfiguration(classOf[ProxiedToolsConfig]).run { ctx =>
      failure(ctx) should include(
        "bean 'proxiedWeather' (org.llm4s.spring.WeatherTools) has @Tool methods but is an AOP proxy"
      )
    }
  }
}
