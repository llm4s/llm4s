package org.llm4s.spring

import org.springframework.boot.context.properties.ConfigurationProperties

import scala.beans.BeanProperty

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
}
