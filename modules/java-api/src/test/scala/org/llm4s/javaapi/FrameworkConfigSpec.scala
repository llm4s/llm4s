package org.llm4s.javaapi

import org.llm4s.testutil.FixtureChatClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.LoneElement

import java.util.{ List => JList }
import scala.jdk.CollectionConverters._

/** The bridge the Quarkus extension builds its clients through, over the fixture providers (#2280). */
class FrameworkConfigSpec extends AnyFlatSpec with Matchers with LoneElement {

  private def props(entries: (String, String)*): java.util.Map[String, String] = Map(entries: _*).asJava

  private val fixture = Seq(
    "llm4s.providers.main.provider" -> "fixturechat",
    "llm4s.providers.main.model"    -> "fixture-model",
    "llm4s.providers.main.api-key"  -> "fixture-key"
  )

  "sections" should "list each section of llm4s.providers once, ignoring the default pointer and other keys" in {
    val p = props(
      (fixture ++ Seq(
        "llm4s.providers.provider"       -> "main",
        "llm4s.providers.other.provider" -> "fixturechat",
        "llm4s.health.probe"             -> "true"
      )): _*
    )
    FrameworkConfig.sections(p) shouldBe JList.of("main", "other")
  }

  "defaultSection" should "be the named section, else the only one, else empty" in {
    FrameworkConfig.defaultSection(props("llm4s.providers.provider" -> " b ")).get shouldBe "b"
    FrameworkConfig.defaultSection(props(fixture: _*)).get shouldBe "main"
    FrameworkConfig
      .defaultSection(props("llm4s.providers.a.model" -> "x", "llm4s.providers.b.model" -> "y"))
      .isPresent shouldBe false
  }

  "embeddingModel" should "be llm4s.embeddings.model when it is set" in {
    FrameworkConfig.embeddingModel(props("llm4s.embeddings.model" -> "a/b")).get shouldBe "a/b"
    FrameworkConfig.embeddingModel(props("llm4s.embeddings.model" -> " ")).isPresent shouldBe false
  }

  "problems" should "be empty for a complete section of a registered provider" in {
    FrameworkConfig.problems(props(fixture: _*)) shouldBe empty
  }

  it should "name the property of a section with no provider or no model" in {
    val found = FrameworkConfig.problems(props("llm4s.providers.main.base-url" -> "http://x")).asScala
    found.exists(_.startsWith("llm4s.providers.main.provider is not set")) shouldBe true
    found.exists(_.contains("fixturechat")) shouldBe true
    found.exists(_.startsWith("llm4s.providers.main.model is not set")) shouldBe true
  }

  it should "name the property and the registered providers for a provider not on the classpath" in {
    val found =
      FrameworkConfig.problems(props("llm4s.providers.main.provider" -> "nope", "llm4s.providers.main.model" -> "m"))
    found.size shouldBe 1
    found.get(0) should (include("llm4s.providers.main.provider").and(include("nope")).and(include("fixturechat")))
  }

  it should "report a default that names no section" in {
    val found = FrameworkConfig.problems(props((fixture :+ ("llm4s.providers.provider" -> "missing")): _*))
    found.asScala.loneElement should (startWith("llm4s.providers.provider").and(include("sections: main")))
    FrameworkConfig
      .problems(props("llm4s.providers.provider" -> "missing"))
      .asScala
      .loneElement should include("no section is configured")
  }

  it should "check llm4s.embeddings.model's form and provider" in {
    FrameworkConfig.problems(props("llm4s.embeddings.model" -> "fixtureembedding/fixture-embed-small")) shouldBe empty
    FrameworkConfig.problems(props("llm4s.embeddings.model" -> "no-slash")).asScala.loneElement should include(
      "expected provider/model"
    )
    FrameworkConfig.problems(props("llm4s.embeddings.model" -> "nope/x")).asScala.loneElement should (include(
      "llm4s.embeddings.model"
    ).and(include("fixtureembedding")))
  }

  "client" should "build the section's client from kebab-case properties" in {
    val client = FrameworkConfig.client(props(fixture: _*), "main", null)
    client.isSuccess shouldBe true
    client.get().complete("hi").get() shouldBe FixtureChatClient.Reply
  }

  it should "fail with the loader's message when the section cannot be resolved" in {
    val client = FrameworkConfig.client(
      props("llm4s.providers.main.provider" -> "nope", "llm4s.providers.main.model" -> "m"),
      "main",
      null
    )
    client.isFailure shouldBe true
    client.getError().getMessage should include("nope")
  }

  it should "not read a section from application.conf: the sections are the properties alone" in {
    // core's test application.conf defines llm4s.providers.fixturechat-main.
    FrameworkConfig.client(props(), "fixturechat-main", null).isFailure shouldBe true
  }

  "withoutProviders" should "drop llm4s.providers from the defaults, and report defaults that cannot be read" in {
    val kept = FrameworkConfig.withoutProviders(
      pureconfig.ConfigSource.string("llm4s { providers.a.model = m, credentials.x.apiKey = k }")
    )
    val config = kept.flatMap(_.config().left.map(_ => fail("unreadable")))
    config.map(_.hasPath("llm4s.providers")) shouldBe Right(false)
    config.map(_.getString("llm4s.credentials.x.apiKey")) shouldBe Right("k")

    FrameworkConfig.withoutProviders(pureconfig.ConfigSource.string("a = {")).left.map(_.message) should matchPattern {
      case Left(message: String) if message.startsWith("Cannot read llm4s's configuration") =>
    }
  }

  "client" should "report a key set both as a value and as a section" in {
    val client = FrameworkConfig.client(
      props((fixture ++ Seq("llm4s.http.proxy" -> "x", "llm4s.http.proxy.host" -> "h")): _*),
      "main",
      null
    )
    client.getError().getMessage should include("both as a value and as a section")
  }

  "embeddingClient" should "build the model's client from the provider's embeddings block" in {
    val client = FrameworkConfig.embeddingClient(
      props("llm4s.embeddings.fixtureembedding.api-key" -> "k"),
      "fixtureembedding/fixture-embed-small"
    )
    client.isSuccess shouldBe true
    client.get().model shouldBe "fixture-embed-small"
    client.get().embed(JList.of("a")).get().vectors().get(0).length shouldBe 4
  }

  "hoconPath" should "camel-case names except header names, and ignore keys that are not llm4s's own" in {
    FrameworkConfig.hoconPath("llm4s.providers.my-sec.api-key") shouldBe Some(
      List("llm4s", "providers", "my-sec", "apiKey")
    )
    FrameworkConfig.hoconPath("llm4s.providers.s.headers.X-Team_Id") shouldBe Some(
      List("llm4s", "providers", "s", "headers", "X-Team_Id")
    )
    FrameworkConfig.hoconPath("llm4s.providers.s.timeouts.read-timeout") shouldBe Some(
      List("llm4s", "providers", "s", "timeouts", "readTimeout")
    )
    FrameworkConfig.hoconPath("llm4s.credentials.openai.api_key") shouldBe Some(
      List("llm4s", "credentials", "openai", "apiKey")
    )
    FrameworkConfig.hoconPath("llm4s.http.proxy.non-proxy-hosts") shouldBe Some(
      List("llm4s", "http", "proxy", "nonProxyHosts")
    )
    FrameworkConfig.hoconPath("llm4s.health.probe") shouldBe None
    FrameworkConfig.camel("plain") shouldBe "plain"
  }
}
