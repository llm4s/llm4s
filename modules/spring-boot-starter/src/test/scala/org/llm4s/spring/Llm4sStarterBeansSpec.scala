package org.llm4s.spring

import org.llm4s.agent.graph.{ Checkpointer, InMemoryCheckpointer, ThreadId }
import org.llm4s.config.Llm4sConfig
import org.llm4s.javaapi.{ AgentStatusKind, JAgent, JEmbeddingClient, Llm4s }
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.trace.{ NoOpTracing, TraceEvent, Tracing }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.{ Bean, Configuration }
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import reactor.core.publisher.Flux

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._

object Llm4sStarterBeansSpec {

  /** An embedding client of the application's own, over a provider that answers one 2-long vector per text. */
  @Configuration
  class UserEmbeddingConfig {
    @Bean
    def myEmbeddings(): JEmbeddingClient = {
      val provider = new EmbeddingProvider {
        override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
          Right(EmbeddingResponse(embeddings = request.input.map(_ => Vector(1.0, 0.0))))
      }
      val registry = Llm4sConfig.modelRegistryService().fold(e => throw new IllegalStateException(e.message), identity)
      Llm4s.wrapEmbeddingClient(new EmbeddingClient(provider)(using registry), EmbeddingModelConfig("mine", 2))
    }
  }

  @Configuration
  class UserCheckpointerConfig {
    @Bean
    def myCheckpointer(): Checkpointer = new InMemoryCheckpointer()
  }

  @Configuration
  class UserTracingConfig {
    @Bean
    def myTracing(): Tracing = new RecordingTracing(Map("mine" -> "yes"))
  }

  @Configuration
  class UserStreamingConfig {
    @Bean
    def myReactive(): ReactiveLLM4STemplate = new ReactiveLLM4STemplate(null)

    @Bean
    def mySse(): SseLLM4STemplate = new SseLLM4STemplate(null)
  }
}

/**
 * The starter's embedding, checkpointer, tracing and streaming beans, and `llm4s.agent.max-steps` (#2279): each created
 * from properties (or from the classpath, for the streaming templates), absent without them, backing off to a bean of
 * the application's own, and switched off by `llm4s.enabled=false`.
 */
class Llm4sStarterBeansSpec extends AnyFlatSpec with Matchers {
  import Llm4sAgentAutoConfigurationSpec.ScriptedClientConfig
  import Llm4sStarterBeansSpec._

  private val runner = new ApplicationContextRunner()
    .withConfiguration(
      AutoConfigurations.of(
        classOf[Llm4sAutoConfiguration],
        classOf[Llm4sReactiveAutoConfiguration],
        classOf[Llm4sSseAutoConfiguration]
      )
    )
    .withUserConfiguration(classOf[ScriptedClientConfig])

  private val embeddingProperties = Seq(
    "llm4s.embeddings.model=fixtureembedding/fixture-embed-small",
    "llm4s.embeddings.fixtureembedding.api-key=spring-embedding-key"
  )

  private def failure(ctx: AssertableApplicationContext): String =
    Iterator
      .iterate[Throwable](ctx.getStartupFailure)(_.getCause)
      .takeWhile(_ != null)
      .map(e => String.valueOf(e.getMessage))
      .mkString("\n")

  private def runEnded(tracing: RecordingTracing): Seq[TraceEvent.AgentRunEnded] =
    tracing.events.asScala.toSeq.collect { case e: TraceEvent.AgentRunEnded => e }

  "The llm4sEmbeddingClient bean" should "be created from the llm4s.embeddings properties and embed" in {
    runner.withPropertyValues(embeddingProperties*).run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[JEmbeddingClient]).keySet.asScala shouldBe Set("llm4sEmbeddingClient")
      val client = ctx.getBean(classOf[JEmbeddingClient])
      client.model shouldBe "fixture-embed-small"
      client.dimensions.getAsInt shouldBe 256
      val embedded = client.embedAsync(java.util.List.of("one", "two")).get(10, TimeUnit.SECONDS)
      embedded.vectors().size shouldBe 2
    }
  }

  it should "take the vendor's shared key from llm4s.credentials when its block sets none" in {
    runner
      .withPropertyValues(
        "llm4s.embeddings.model=fixtureembedding/fixture-embed-small",
        "llm4s.credentials.fixtureembedding.api-key=shared-key"
      )
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBean(classOf[JEmbeddingClient]).embed(java.util.List.of("one")).get().vectors().size shouldBe 1
      }
  }

  it should "be absent without llm4s.embeddings.model" in {
    runner.withPropertyValues("llm4s.embeddings.fixtureembedding.api-key=k").run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[JEmbeddingClient]).size shouldBe 0
    }
  }

  it should "back off for a JEmbeddingClient bean of the application's own" in {
    runner.withUserConfiguration(classOf[UserEmbeddingConfig]).withPropertyValues(embeddingProperties*).run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[JEmbeddingClient]).keySet.asScala shouldBe Set("myEmbeddings")
    }
  }

  it should "be absent when llm4s.enabled=false" in {
    runner.withPropertyValues(embeddingProperties :+ "llm4s.enabled=false"*).run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[JEmbeddingClient]).size shouldBe 0
    }
  }

  it should "fail the context, naming the provider, when its key is missing" in {
    runner.withPropertyValues("llm4s.embeddings.model=fixtureembedding/fixture-embed-small").run { ctx =>
      ctx.getStartupFailure should not be null
      failure(ctx) should include("fixtureembedding")
    }
  }

  "The llm4sCheckpointer bean" should "be created in memory and keep the agent's threads" in {
    runner.withUserConfiguration(classOf[WeatherTools]).run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[Checkpointer]).keySet.asScala shouldBe Set("llm4sCheckpointer")
      ctx.getBean(classOf[Checkpointer]) shouldBe an[InMemoryCheckpointer]
      val result = ctx.getBean(classOf[JAgent]).run("Weather?").get()
      ctx.getBean(classOf[Checkpointer]).latest(ThreadId(result.threadId())).map(_.isDefined) shouldBe Right(true)
    }
  }

  it should "back off for a Checkpointer bean of the application's own, which the agent then uses" in {
    runner.withUserConfiguration(classOf[WeatherTools], classOf[UserCheckpointerConfig]).run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[Checkpointer]).keySet.asScala shouldBe Set("myCheckpointer")
      val result = ctx.getBean(classOf[JAgent]).run("Weather?").get()
      ctx.getBean(classOf[Checkpointer]).latest(ThreadId(result.threadId())).map(_.isDefined) shouldBe Right(true)
    }
  }

  it should "be absent with llm4s.agent.enabled=false and with llm4s.enabled=false" in {
    Seq("llm4s.agent.enabled=false", "llm4s.enabled=false").foreach { property =>
      runner.withPropertyValues(property).run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[Checkpointer]).size shouldBe 0
      }
    }
  }

  "llm4s.agent.max-steps" should "end a turn STEP_LIMIT_REACHED when the agent needs more steps" in {
    runner.withUserConfiguration(classOf[WeatherTools]).withPropertyValues("llm4s.agent.max-steps=1").run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBean(classOf[JAgent]).run("Weather?").get().status().kind() shouldBe AgentStatusKind.STEP_LIMIT_REACHED
    }
  }

  it should "fail the context when below 1" in {
    runner.withPropertyValues("llm4s.agent.max-steps=0").run { ctx =>
      ctx.getStartupFailure should not be null
      failure(ctx) should include("maxSteps")
    }
  }

  "The llm4sTracing bean" should "be created from llm4s.tracing.* for a TracingBackend on the classpath" in {
    runner
      .withUserConfiguration(classOf[WeatherTools])
      .withPropertyValues(
        s"llm4s.tracing.mode=${RecordingTracingBackend.Mode}",
        s"llm4s.tracing.${RecordingTracingBackend.Mode}.service-name=orders",
        s"llm4s.tracing.${RecordingTracingBackend.Mode}.headers.X-Team-Id=t1"
      )
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[Tracing]).keySet.asScala shouldBe Set("llm4sTracing")
        val tracing = ctx.getBean(classOf[Tracing]).asInstanceOf[RecordingTracing]
        tracing.extras("serviceName") shouldBe "orders"
        tracing.extras.values should contain("t1")
        tracing.extras.keys.exists(_.contains("X-Team-Id")) shouldBe true
        ctx.getBean(classOf[JAgent]).run("Weather?").get()
        runEnded(tracing).map(_.agent) shouldBe Seq("assistant")
        ctx.close()
        tracing.shutDown shouldBe true
      }
  }

  it should "be llm4s's own tracer for a built-in mode" in {
    runner.withPropertyValues("llm4s.tracing.mode=none").run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBean(classOf[Tracing]) shouldBe a[NoOpTracing]
    }
  }

  it should "be absent without llm4s.tracing.mode, the agent then untraced" in {
    runner.run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[Tracing]).size shouldBe 0
      ctx.getBeansOfType(classOf[JAgent]).size shouldBe 1
    }
  }

  it should "back off for a Tracing bean of the application's own, which the agent then reports to" in {
    runner
      .withUserConfiguration(classOf[WeatherTools], classOf[UserTracingConfig])
      .withPropertyValues(s"llm4s.tracing.mode=${RecordingTracingBackend.Mode}")
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[Tracing]).keySet.asScala shouldBe Set("myTracing")
        ctx.getBean(classOf[JAgent]).run("Weather?").get()
        runEnded(ctx.getBean(classOf[Tracing]).asInstanceOf[RecordingTracing]) should have size 1
      }
  }

  it should "be absent when llm4s.enabled=false" in {
    runner.withPropertyValues("llm4s.tracing.mode=none", "llm4s.enabled=false").run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[Tracing]).size shouldBe 0
    }
  }

  it should "fail the context, naming the property, for a mode no backend serves" in {
    runner.withPropertyValues("llm4s.tracing.mode=nowhere").run { ctx =>
      ctx.getStartupFailure should not be null
      failure(ctx) should include("llm4s.tracing: ")
      failure(ctx) should include("nowhere")
    }
  }

  "The streaming templates" should "be created when Reactor and Spring MVC are on the classpath" in {
    runner.run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[ReactiveLLM4STemplate]).keySet.asScala shouldBe Set("llm4sReactiveTemplate")
      ctx.getBeansOfType(classOf[SseLLM4STemplate]).keySet.asScala shouldBe Set("llm4sSseTemplate")
    }
  }

  it should "be absent without Reactor, and without Spring MVC, the other still there" in {
    runner.withClassLoader(new FilteredClassLoader(classOf[Flux[?]])).run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[ReactiveLLM4STemplate]).size shouldBe 0
      ctx.getBeansOfType(classOf[SseLLM4STemplate]).size shouldBe 1
    }
    runner.withClassLoader(new FilteredClassLoader(classOf[SseEmitter])).run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[ReactiveLLM4STemplate]).size shouldBe 1
      ctx.getBeansOfType(classOf[SseLLM4STemplate]).size shouldBe 0
    }
  }

  it should "back off for templates of the application's own" in {
    runner.withUserConfiguration(classOf[UserStreamingConfig]).run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[ReactiveLLM4STemplate]).keySet.asScala shouldBe Set("myReactive")
      ctx.getBeansOfType(classOf[SseLLM4STemplate]).keySet.asScala shouldBe Set("mySse")
    }
  }

  it should "be absent when llm4s.enabled=false" in {
    runner.withPropertyValues("llm4s.enabled=false").run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[ReactiveLLM4STemplate]).size shouldBe 0
      ctx.getBeansOfType(classOf[SseLLM4STemplate]).size shouldBe 0
    }
  }

}
