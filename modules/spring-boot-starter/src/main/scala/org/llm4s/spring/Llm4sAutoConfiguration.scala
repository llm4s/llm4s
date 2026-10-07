package org.llm4s.spring

import org.llm4s.javaapi.{ JLlmClient, Llm4s }
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.{
  ConditionalOnClass,
  ConditionalOnMissingBean,
  ConditionalOnProperty
}
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.{ Bean, Configuration }

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

@AutoConfiguration
@Configuration
@ConditionalOnClass(name = Array("org.llm4s.javaapi.Llm4s$"))
@ConditionalOnProperty(prefix = "llm4s", name = Array("enabled"), havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(Array(classOf[Llm4sProperties]))
class Llm4sAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  def llm4sClient(properties: Llm4sProperties): JLlmClient =
    ProviderConfigParser
      .parse(properties)
      .map(config => Llm4s.createClient(config).get())
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
}
