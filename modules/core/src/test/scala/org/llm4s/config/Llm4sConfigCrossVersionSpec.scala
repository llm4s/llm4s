package org.llm4s.config

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

final case class DatabaseConfig(url: String, poolSize: Int)

final case class AppConfig(appName: String, database: DatabaseConfig, debug: Boolean, extraTag: Option[String])

class Llm4sConfigCrossVersionSpec extends AnyFlatSpec with Matchers {

  implicit val databaseConfigReader: PureConfigReader[DatabaseConfig] =
    PureConfigReader.forProduct2("url", "poolSize")(DatabaseConfig.apply)

  implicit val appConfigReader: PureConfigReader[AppConfig] =
    PureConfigReader.forProduct4("appName", "database", "debug", "extraTag")(AppConfig.apply)

  private def loadFromHocon[A: PureConfigReader](
    path: String,
    hocon: String
  ): Either[pureconfig.error.ConfigReaderFailures, A] =
    ConfigSource.string(hocon).at(path).load[A]

  "PureConfig" should "load a simple nested configuration identically in Scala 2 and Scala 3" in {
    val hocon =
      """
        |sample-app {
        |  appName = "llm4s-demo"
        |  debug   = true
        |
        |  database {
        |    url      = "jdbc:postgresql://localhost:5432/llm4s"
        |    poolSize = 8
        |  }
        |
        |  # Optional values can be omitted without failing the load
        |  # extraTag is intentionally not provided here
        |}
        |""".stripMargin

    val loaded = loadFromHocon[AppConfig]("sample-app", hocon)

    loaded match {
      case Right(appCfg) =>
        appCfg shouldBe AppConfig(
          appName = "llm4s-demo",
          database = DatabaseConfig(
            url = "jdbc:postgresql://localhost:5432/llm4s",
            poolSize = 8
          ),
          debug = true,
          extraTag = None
        )
      case Left(err) =>
        fail(err.toString)
    }
  }

  it should "support overriding values via HOCON merging" in {
    val base =
      """
        |sample-app {
        |  appName = "llm4s-demo"
        |  debug   = false
        |  database {
        |    url      = "jdbc:postgresql://localhost:5432/llm4s"
        |    poolSize = 4
        |  }
        |}
        |""".stripMargin

    val overrideConf =
      """
        |sample-app {
        |  debug   = true
        |  database {
        |    poolSize = 16
        |  }
        |  extraTag = "from-override"
        |}
        |""".stripMargin

    val merged = ConfigFactory
      .parseString(overrideConf)
      .withFallback(ConfigFactory.parseString(base))

    val loaded = ConfigSource
      .fromConfig(merged.resolve())
      .at("sample-app")
      .load[AppConfig]

    loaded match {
      case Right(appCfg) =>
        appCfg shouldBe AppConfig(
          appName = "llm4s-demo",
          database = DatabaseConfig(
            url = "jdbc:postgresql://localhost:5432/llm4s",
            poolSize = 16
          ),
          debug = true,
          extraTag = Some("from-override")
        )
      case Left(err) =>
        fail(err.toString)
    }
  }
}
