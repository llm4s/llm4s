package org.llm4s.config

trait ConfigReader {
  def get(key: String): Option[String]
  def getOrElse(key: String, default: String): String = get(key).getOrElse(default)
}

object ConfigReader {

  def from(map: Map[String, String]): ConfigReader = new ConfigReader {
    override def get(key: String): Option[String] = map.get(key)
  }

  def LLMConfig(): ConfigReader =
    EnvLoader
}
