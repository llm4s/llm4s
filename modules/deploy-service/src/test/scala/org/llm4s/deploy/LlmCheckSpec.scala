package org.llm4s.deploy

import org.llm4s.config.Llm4sConfig
import org.llm4s.error.{ ConfigurationError, NetworkError }
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.testutil.MockLLMClients.SimpleMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LlmCheckSpec extends AnyFlatSpec with Matchers {

  import LlmCheckOutcome._

  /** A provider config that needs no network: core's test fixture provider, the test default. */
  private val config: ProviderConfig =
    Llm4sConfig.defaultProvider().fold(error => fail(error.message), identity)

  private class CountingClient extends SimpleMock("ok") {
    var closes                 = 0
    override def close(): Unit = closes += 1
  }

  "LlmCheck" should "be ready, naming the provider, when a client can be built, and close that client" in {
    val client = new CountingClient
    val check  = new LlmCheck(() => Right(config), _ => Right(client))

    check.run() shouldBe Ready("fixturechat")
    client.closes shouldBe 1
  }

  it should "close the client it built on every run" in {
    val client = new CountingClient
    val check  = new LlmCheck(() => Right(config), _ => Right(client))

    (1 to 3).foreach(_ => check.run())

    client.closes shouldBe 3
  }

  it should "still be ready when closing the client fails" in {
    val failing = new SimpleMock("ok") {
      override def close(): Unit = throw new IllegalStateException("close failed")
    }
    val check = new LlmCheck(() => Right(config), _ => Right(failing))

    check.run() shouldBe Ready("fixturechat")
  }

  it should "be unconfigured when no default provider loads" in {
    val check =
      new LlmCheck(() => Left(ConfigurationError("llm4s.providers.provider is not set")), _ => fail("no client"))

    check.run() shouldBe Unconfigured("ConfigurationError")
  }

  it should "be degraded, naming the provider, when a client cannot be built" in {
    val check = new LlmCheck(
      () => Right(config),
      _ => Left(NetworkError("could not reach the endpoint", None, "https://internal.example/v1"))
    )

    check.run() shouldBe Degraded("fixturechat", "NetworkError")
  }

  it should "report only the error type, never the message that can name a key or a URL" in {
    val secretish = "apiKey sk-LEAKED for https://internal.example/v1"
    val outcomes = Seq(
      new LlmCheck(() => Left(ConfigurationError(secretish)), _ => fail("no client")).run(),
      new LlmCheck(() => Right(config), _ => Left(NetworkError(secretish, None, "https://internal.example/v1"))).run()
    )

    outcomes.foreach { outcome =>
      val body = ujson.write(outcome.json)
      (body should not).include("sk-LEAKED")
      (body should not).include("internal.example")
    }
  }

  it should "work against the process's own configuration" in {
    // Core's test configuration makes `fixturechat-main` the default provider, with no network.
    LlmCheck.default().run() shouldBe Ready("fixturechat")
  }

  "LlmCheckOutcome" should "map to 200 when ready and 503 otherwise" in {
    Ready("p").httpStatus shouldBe 200
    Unconfigured("ConfigurationError").httpStatus shouldBe 503
    Degraded("p", "NetworkError").httpStatus shouldBe 503
  }

  it should "render a status, the provider when there is one, and the error when there is one" in {
    ujson.write(Ready("openai").json) shouldBe """{"status":"ready","provider":"openai"}"""
    ujson.write(
      Unconfigured("ConfigurationError").json
    ) shouldBe """{"status":"unconfigured","error":"ConfigurationError"}"""
    ujson.write(Degraded("openai", "NetworkError").json) shouldBe
      """{"status":"degraded","provider":"openai","error":"NetworkError"}"""
  }

  "LlmCheck.guarded" should "report an exception from the check as degraded rather than propagate it" in {
    val outcome = LlmCheck.guarded(() => throw new IllegalStateException("boom"))

    outcome shouldBe Degraded("unknown", "UnexpectedError")
  }

  it should "pass a normal outcome through" in {
    LlmCheck.guarded(() => Ready("p")) shouldBe Ready("p")
  }

  // The check `LlmCheck.default` runs - the default section through `Llm4sConfig`, the client through
  // `LLMConnect` - for a workload-identity section whose baseUrl the provider refuses. `refusedBaseUrl` reaches
  // the fake token server, so an exchange or request made before the refusal would show in its records.
  "LlmCheck on a workload-identity section" should "be unconfigured, without exchanging, for a baseUrl the rules refuse" in
    org.llm4s.testkit.FakeTokenExchangeServer.withServer { fake =>
      given org.llm4s.llmconnect.spi.ProviderRegistry = org.llm4s.llmconnect.spi.ProviderRegistry.default
      val hocon =
        s"""llm4s.providers {
           |  provider = "main"
           |  main {
           |    provider = "openai-compatible"
           |    model    = "m"
           |    baseUrl  = "${fake.refusedBaseUrl}/serving-endpoints"
           |    auth { identityToken = "eyJ.svid.sig", tokenUrl = "${fake.baseUrl}/oidc/v1/token" }
           |  }
           |}""".stripMargin
      val check = new LlmCheck(
        () => Llm4sConfig.providerFrom(pureconfig.ConfigSource.string(hocon)),
        config =>
          Llm4sConfig.modelRegistryService().flatMap { registry =>
            given org.llm4s.model.ModelRegistryService = registry
            org.llm4s.llmconnect.LLMConnect.getClient(config)
          }
      )

      check.run() shouldBe Unconfigured("ConfigurationError")
      fake.exchanges shouldBe empty
      fake.apiAuthorizations shouldBe empty
    }
}
