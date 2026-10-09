package org.llm4s.deploy

import org.llm4s.error.ConfigurationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.Using

class DeployServiceConfigSpec extends AnyFlatSpec with Matchers {

  private def source(block: String): ConfigSource =
    ConfigSource.string(s"llm4s.deploy-service { $block }")

  "DeployServiceConfig" should "read the host and port" in {
    DeployServiceConfig.load(source("""host = "127.0.0.1", port = 9000""")) shouldBe
      Right(DeployServiceConfig("127.0.0.1", 9000))
  }

  it should "accept the lowest and highest port" in {
    DeployServiceConfig.load(source("""host = "h", port = 1""")).map(_.port) shouldBe Right(1)
    DeployServiceConfig.load(source("""host = "h", port = 65535""")).map(_.port) shouldBe Right(65535)
  }

  it should "reject a port outside 1 to 65535" in {
    Seq(0, -1, 65536, 70000).foreach { port =>
      withClue(s"port $port: ") {
        val result = DeployServiceConfig.load(source(s"""host = "h", port = $port"""))
        result.left.map(_.message) match {
          case Left(message) => message should include("llm4s.deploy-service.port")
          case Right(config) => fail(s"accepted $config")
        }
      }
    }
  }

  it should "report the key that is missing when the port is rejected" in {
    DeployServiceConfig.load(source("""host = "h", port = 0""")) match {
      case Left(error: ConfigurationError) => error.missingKeys should contain("llm4s.deploy-service.port")
      case other                           => fail(s"expected a ConfigurationError, got $other")
    }
  }

  it should "reject an empty host" in {
    DeployServiceConfig.load(source("""host = "  ", port = 8080""")).left.map(_.message) match {
      case Left(message) => message should include("llm4s.deploy-service.host")
      case Right(config) => fail(s"accepted $config")
    }
  }

  it should "reject a port that is not a number" in {
    DeployServiceConfig.load(source("""host = "h", port = "eighty"""")).isLeft shouldBe true
  }

  it should "reject a block with a key missing" in {
    DeployServiceConfig.load(source("""host = "h"""")).isLeft shouldBe true
  }

  it should "reject a configuration with no llm4s.deploy-service block" in {
    DeployServiceConfig.load(ConfigSource.string("llm4s.other { x = 1 }")).isLeft shouldBe true
  }

  it should "load this process's configuration, whose defaults are valid" in {
    // `PORT` in the environment may override the default port, so assert only what is always true.
    DeployServiceConfig.load() match {
      case Right(config) =>
        config.host shouldBe "0.0.0.0"
        config.port should ((be >= 1).and(be <= 65535))
      case Left(error) => fail(error.message)
    }
  }

  "this module's reference.conf" should "default to 0.0.0.0:8080 and bind PORT, which is how the PORT variable reaches the service" in {
    val ours = getClass.getClassLoader
      .getResources("reference.conf")
      .asScala
      .map(url => Using.resource(Source.fromURL(url, "UTF-8"))(_.mkString))
      .find(_.contains("deploy-service"))
      .getOrElse(fail("this module's reference.conf was not found"))

    ours should include("""host = "0.0.0.0"""")
    ours should include("port = 8080")
    ours should include("port = ${?PORT}")
  }
}
