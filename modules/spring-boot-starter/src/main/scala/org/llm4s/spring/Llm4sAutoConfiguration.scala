package org.llm4s.spring

import org.llm4s.agent.graph.{ Checkpointer, InMemoryCheckpointer }
import org.llm4s.javaapi.{ JAgent, JEmbeddingClient, JLlmClient, Llm4s }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.trace.Tracing
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.{
  ConditionalOnClass,
  ConditionalOnMissingBean,
  ConditionalOnProperty
}
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.{ Bean, Configuration }
import org.springframework.core.env.ConfigurableEnvironment

import java.util.concurrent.{ ExecutorService, LinkedBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger

/** Name of the executor bean behind `completeAsync` and the health probe; define a bean of this name to replace it. */
object Llm4sExecutors {
  final val BeanName = "llm4sTaskExecutor"

  /**
   * A bounded pool of daemon threads named `llm4s-async-N`: at most `maxThreads` workers (idle
   * ones time out, so an idle application holds none) and `queueCapacity` queued calls; beyond that
   * `submit` is rejected and `completeAsync` fails its future instead of growing without bound.
   * Bounded rather than virtual threads.
   */
  def create(p: AsyncProperties): ExecutorService = {
    require(p.maxThreads >= 1, s"llm4s.async.max-threads must be >= 1, got ${p.maxThreads}")
    require(p.queueCapacity >= 1, s"llm4s.async.queue-capacity must be >= 1, got ${p.queueCapacity}")
    val counter = new AtomicInteger(0)
    val factory: ThreadFactory = r => {
      val t = new Thread(r, s"llm4s-async-${counter.incrementAndGet()}")
      t.setDaemon(true)
      t
    }
    val pool = new ThreadPoolExecutor(
      p.maxThreads,
      p.maxThreads,
      30,
      TimeUnit.SECONDS,
      new LinkedBlockingQueue[Runnable](p.queueCapacity),
      factory
    )
    pool.allowCoreThreadTimeOut(true)
    pool
  }
}

object Llm4sAutoConfiguration {

  /** All `MetricsCollector` beans as one: none is `noop`, one is itself, several are composed in bean order. */
  private[spring] def collector(beans: ObjectProvider[MetricsCollector]): MetricsCollector = {
    val all = beans.orderedStream().toArray().toSeq.collect { case c: MetricsCollector => c }
    all match {
      case Seq()  => MetricsCollector.noop
      case Seq(c) => c
      case many   => MetricsCollector.compose(many*)
    }
  }
}

@AutoConfiguration
@Configuration
@ConditionalOnClass(name = Array("org.llm4s.javaapi.Llm4s$"))
@ConditionalOnProperty(prefix = "llm4s", name = Array("enabled"), havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(Array(classOf[Llm4sProperties]))
class Llm4sAutoConfiguration {

  /**
   * The client, reporting its calls to every `MetricsCollector` bean in the context: the Micrometer one
   * `LlmMetricsAutoConfiguration` registers, any of the application's own, or none.
   */
  @Bean
  @ConditionalOnMissingBean
  def llm4sClient(
    properties: Llm4sProperties,
    environment: ConfigurableEnvironment,
    metrics: ObjectProvider[MetricsCollector]
  ): JLlmClient =
    ProviderSelection
      .resolve(properties, environment)
      .map(config => Llm4s.createClient(config, Llm4sAutoConfiguration.collector(metrics)).get())
      .get()

  // shutdownNow, not Spring's inferred close(): close() waits for running LLM calls to finish,
  // while shutdownNow interrupts them, which is how llm4s cancels a provider call.
  @Bean(destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = Array(Llm4sExecutors.BeanName))
  def llm4sTaskExecutor(properties: Llm4sProperties): ExecutorService =
    Llm4sExecutors.create(properties.async)

  @Bean
  @ConditionalOnMissingBean
  def llm4sTemplate(client: JLlmClient, @Qualifier(Llm4sExecutors.BeanName) executor: ExecutorService): LLM4STemplate =
    new LLM4STemplate(client, executor)

  /**
   * The embedding client `llm4s.embeddings.*` configures (#2279): registered when `llm4s.embeddings.model` is set, its
   * `embedAsync` calls on the `llm4sTaskExecutor`. A bean of type `JEmbeddingClient` replaces it.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "llm4s.embeddings", name = Array("model"))
  def llm4sEmbeddingClient(
    environment: ConfigurableEnvironment,
    @Qualifier(Llm4sExecutors.BeanName) executor: ExecutorService
  ): JEmbeddingClient =
    SpringProviderSource.embeddings(environment).get().withAsyncExecutor(executor)

  /**
   * The tracer `llm4s.tracing.*` configures (#2279), which the `llm4sAgent` reports to: registered when
   * `llm4s.tracing.mode` is set, for `console`, `none` or the mode of a `TracingBackend` on the classpath, such as
   * `llm4s-observability-otel`'s `opentelemetry`. A mode no backend serves fails startup. A bean of type `Tracing`
   * replaces it.
   */
  @Bean(destroyMethod = "shutdown")
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "llm4s.tracing", name = Array("mode"))
  def llm4sTracing(environment: ConfigurableEnvironment): Tracing =
    SpringProviderSource
      .tracing(environment)
      .flatMap(Tracing.fromSettings)
      .fold(error => throw new IllegalStateException(s"llm4s.tracing: ${error.message}"), identity)

  /** The `@Tool` methods of the application's beans (#2279); a bean of type `Llm4sTools` replaces them. */
  @Bean
  @ConditionalOnMissingBean
  def llm4sTools(beans: ConfigurableListableBeanFactory): Llm4sTools = Llm4sTools.collect(beans)

  /**
   * Where the `llm4sAgent` keeps its threads between turns (#2279): in memory. A bean of type `Checkpointer`, such as
   * `llm4s-agent-checkpoint-sqlite`'s or `-postgres`'s, replaces it; `llm4s.agent.enabled=false` registers none.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "llm4s.agent", name = Array("enabled"), havingValue = "true", matchIfMissing = true)
  def llm4sCheckpointer(): Checkpointer = new InMemoryCheckpointer()

  /**
   * An agent over the client and the collected tools, configured by `llm4s.agent.*` (#2279), its threads in the
   * `Checkpointer` bean, reporting to the `Tracing` bean when there is one, its `runAsync` calls on the
   * `llm4sTaskExecutor`. A bean of type `JAgent` replaces it; `llm4s.agent.enabled=false` registers none.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "llm4s.agent", name = Array("enabled"), havingValue = "true", matchIfMissing = true)
  def llm4sAgent(
    client: JLlmClient,
    tools: Llm4sTools,
    checkpointer: Checkpointer,
    tracing: ObjectProvider[Tracing],
    properties: Llm4sProperties,
    @Qualifier(Llm4sExecutors.BeanName) executor: ExecutorService
  ): JAgent = {
    val agent = properties.agent
    val base = Llm4s
      .agentBuilder(agent.id, client)
      .tools(tools.tools())
      .streaming(agent.streaming)
      .maxSteps(agent.maxSteps)
      .checkpointer(checkpointer)
    val prompted = if (agent.systemPrompt.isEmpty) base else base.systemPrompt(agent.systemPrompt)
    val traced   = Option(tracing.getIfUnique()).fold(prompted)(prompted.tracing)
    traced.build().get().withAsyncExecutor(executor)
  }
}
