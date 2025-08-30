package org.llm4s.config

import io.github.cdimascio.dotenv.Dotenv

private[config] object EnvLoader extends ConfigReader {
  private lazy val dotenv = Dotenv
    .configure()
    .ignoreIfMissing()
    .load()

  def get(key: String): Option[String] = Option(dotenv.get(key))
}
