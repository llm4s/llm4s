package org.llm4s.spring

import org.springframework.boot.context.properties.ConfigurationProperties

import java.time.Duration
import scala.beans.BeanProperty

/** Sizing of the executor behind [[LLM4STemplate]]'s `completeAsync` (`llm4s.async.*`). */
class AsyncProperties {

  /** Maximum worker threads of the default `llm4sTaskExecutor` (idle threads time out). */
  @BeanProperty var maxThreads: Int = 16

  /** Pending `completeAsync` calls the default executor queues before rejecting new ones. */
  @BeanProperty var queueCapacity: Int = 1000
}

/** Health indicator settings (`llm4s.health.*`). */
class HealthProperties {

  /** Opt in to a real provider call (one token) per probe; off by default because it bills. */
  @BeanProperty var probe: Boolean = false

  /** How long a probe result is reused, so health polling does not hit the provider each time. */
  @BeanProperty var probeTtl: Duration = Duration.ofSeconds(60)

  /** A probe slower than this is cancelled (interrupting the provider call) and reports DOWN. */
  @BeanProperty var probeTimeout: Duration = Duration.ofSeconds(10)
}

@ConfigurationProperties(prefix = "llm4s")
class Llm4sProperties {

  @BeanProperty var enabled: Boolean = true

  @BeanProperty var provider: String = ""

  @BeanProperty var model: String = ""

  @BeanProperty var apiKey: String = ""

  @BeanProperty var baseUrl: String = ""

  @BeanProperty var organization: String = ""

  @BeanProperty var contextWindow: Int = 128000

  @BeanProperty var reserveCompletion: Int = 4096

  @BeanProperty var async: AsyncProperties = new AsyncProperties

  @BeanProperty var health: HealthProperties = new HealthProperties
}
