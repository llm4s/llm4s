package org.llm4s.config

import io.github.cdimascio.dotenv.Dotenv

object EnvLoader {
  private lazy val dotenv = Dotenv
    .configure()
    .ignoreIfMissing()
    .load()

  def get(key: String): Option[String] = Option(dotenv.get(key))

  def getOrElse(key: String, default: String): String = get(key).getOrElse(default)
}
