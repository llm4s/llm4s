package org.llm4s.spring

import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.llm4s.javaapi.JLlmClient
import org.llm4s.metrics.{ MetricsCollector, MicrometerMetrics, Outcome }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.metrics.{
  CompositeMeterRegistryAutoConfiguration,
  MetricsAutoConfiguration,
  MetricsEndpointAutoConfiguration
}
import org.springframework.boot.actuate.autoconfigure.metrics.`export`.simple.SimpleMetricsExportAutoConfiguration
import org.springframework.boot.actuate.metrics.MetricsEndpoint
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.{ Bean, Configuration }

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

object LlmMetricsAutoConfigurationSpec {

  /** A collector of the application's own, recording which requests it saw. */
  final class RecordingCollector extends MetricsCollector {
    val requests = new ConcurrentLinkedQueue[(String, String, Outcome)]()
    override def observeRequest(provider: String, model: String, outcome: Outcome, duration: FiniteDuration): Unit =
      requests.add((provider, model, outcome))
    override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit = ()
    override def recordCost(provider: String, model: String, costUsd: Double): Unit                      = ()
  }

  @Configuration
  class OwnCollectorConfig {
    @Bean def myCollector(): MetricsCollector = new RecordingCollector
  }

  @Configuration
  class TwoCollectorsConfig {
    @Bean def firstCollector(): MetricsCollector  = new RecordingCollector
    @Bean def secondCollector(): MetricsCollector = new RecordingCollector
  }

  @Configuration
  class OwnRegistryConfig {
    @Bean def meterRegistry(): MeterRegistry = new SimpleMeterRegistry()
  }
}

/**
 * #1466: with Spring Boot Actuator and `llm4s-observability-micrometer` on the classpath, the starter's client records
 * into the application's `MeterRegistry` with no user wiring, and the meters are on `/actuator/metrics` (the
 * `MetricsEndpoint` bean that endpoint serves).
 */
class LlmMetricsAutoConfigurationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  import LlmMetricsAutoConfigurationSpec.*

  // A loopback Ollama `/api/chat`, so the real provider client runs end to end and sends nothing off the machine.
  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  server.createContext(
    "/api/chat",
    exchange => {
      val reply =
        """{"model":"llama3.2","message":{"role":"assistant","content":"hi"},"done":true,"prompt_eval_count":5,"eval_count":3}"""
      val bytes = reply.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    }
  )
  server.start()
  override def afterAll(): Unit = server.stop(0)

  private val ollama = Seq(
    "llm4s.provider=ollama",
    "llm4s.model=llama3.2",
    s"llm4s.base-url=http://127.0.0.1:${server.getAddress.getPort}"
  )

  private val llm4sOnly =
    AutoConfigurations.of(classOf[Llm4sAutoConfiguration], classOf[LlmMetricsAutoConfiguration])

  // What an application with spring-boot-starter-actuator gets: a MeterRegistry and the metrics endpoint.
  private val withActuator = new ApplicationContextRunner()
    .withConfiguration(
      AutoConfigurations.of(
        classOf[MetricsAutoConfiguration],
        classOf[CompositeMeterRegistryAutoConfiguration],
        classOf[SimpleMetricsExportAutoConfiguration],
        classOf[EndpointAutoConfiguration],
        classOf[MetricsEndpointAutoConfiguration]
      )
    )
    .withConfiguration(llm4sOnly)
    .withPropertyValues(
      (ollama ++ Seq(
        "management.endpoints.web.exposure.include=metrics",
        "management.endpoints.jmx.exposure.include=metrics"
      ))*
    )

  private def count(endpoint: MetricsEndpoint, name: String, tags: String*): Double =
    Option(endpoint.metric(name, tags.asJava))
      .flatMap(_.getMeasurements.asScala.find(_.getStatistic.name == "COUNT"))
      .map(_.getValue.doubleValue)
      .getOrElse(0.0)

  "LlmMetricsAutoConfiguration" should "put the client's request, duration and token meters on /actuator/metrics with no wiring" in {
    withActuator.run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBean(classOf[MetricsCollector]) shouldBe a[MicrometerMetrics]

      ctx.getBean(classOf[JLlmClient]).complete("hello").get() shouldBe "hi"

      val endpoint = ctx.getBean(classOf[MetricsEndpoint])
      (endpoint.listNames().getNames.asScala should contain).allOf(
        MicrometerMetrics.Names.Requests,
        MicrometerMetrics.Names.RequestDuration,
        MicrometerMetrics.Names.Tokens
      )
      count(
        endpoint,
        MicrometerMetrics.Names.Requests,
        "provider:ollama",
        "model:llama3.2",
        "status:success"
      ) shouldBe 1.0
      count(endpoint, MicrometerMetrics.Names.RequestDuration, "provider:ollama") shouldBe 1.0
      count(endpoint, MicrometerMetrics.Names.Tokens, "type:input") shouldBe 5.0
      count(endpoint, MicrometerMetrics.Names.Tokens, "type:output") shouldBe 3.0
    }
  }

  it should "record into a MeterRegistry the application defines itself, without Actuator" in {
    new ApplicationContextRunner()
      .withConfiguration(llm4sOnly)
      .withUserConfiguration(classOf[OwnRegistryConfig])
      .withPropertyValues(ollama*)
      .run { ctx =>
        ctx.getBean(classOf[JLlmClient]).complete("hello").get() shouldBe "hi"
        val registry = ctx.getBean(classOf[MeterRegistry])
        registry.find(MicrometerMetrics.Names.Requests).tag("status", "success").counter().count() shouldBe 1.0
      }
  }

  it should "register no collector when there is no MeterRegistry, and still build the client" in {
    new ApplicationContextRunner()
      .withConfiguration(llm4sOnly)
      .withPropertyValues(ollama*)
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[MetricsCollector]).size shouldBe 0
        ctx.getBean(classOf[JLlmClient]).complete("hello").get() shouldBe "hi"
      }
  }

  it should "register no collector when llm4s-observability-micrometer is not on the classpath" in {
    withActuator
      .withClassLoader(new FilteredClassLoader(classOf[MicrometerMetrics]))
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[MetricsCollector]).size shouldBe 0
        ctx.getBean(classOf[JLlmClient]) should not be null
      }
  }

  it should "back off for the application's own MetricsCollector, and the client reports to that one" in {
    withActuator
      .withUserConfiguration(classOf[OwnCollectorConfig])
      .run { ctx =>
        ctx.getBeansOfType(classOf[MetricsCollector]).keySet.asScala shouldBe Set("myCollector")
        ctx.getBean(classOf[JLlmClient]).complete("hello").get() shouldBe "hi"
        val own = ctx.getBean(classOf[MetricsCollector]).asInstanceOf[RecordingCollector]
        own.requests.asScala.toList shouldBe List(("ollama", "llama3.2", Outcome.Success))
      }
  }

  it should "report to every MetricsCollector bean when the application has several" in {
    new ApplicationContextRunner()
      .withConfiguration(llm4sOnly)
      .withUserConfiguration(classOf[TwoCollectorsConfig])
      .withPropertyValues(ollama*)
      .run { ctx =>
        ctx.getBean(classOf[JLlmClient]).complete("hello").get() shouldBe "hi"
        val collectors = ctx.getBeansOfType(classOf[MetricsCollector]).values.asScala.toList
        collectors should have size 2
        collectors.foreach(_.asInstanceOf[RecordingCollector].requests.size shouldBe 1)
      }
  }

  it should "create nothing when llm4s.enabled=false" in {
    withActuator
      .withPropertyValues("llm4s.enabled=false")
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[MetricsCollector]).size shouldBe 0
      }
  }
}
