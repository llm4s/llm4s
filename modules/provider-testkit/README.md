# llm4s-provider-testkit

Test checks and verification harness for authors of llm4s provider modules.

A provider module is a dependency, not an edit to llm4s: its `Llm4sProviderModule` is discovered through
`META-INF/services`. A typo in the services file, an `object` where a `class` is expected, an ID collision,
or a missing `reference.conf` credential binding would fail silently or at user runtime.
`llm4s-provider-testkit` catches these issues during the provider module's own build.

Beta (see [`docs/reference/v1-scope.md`](../../docs/reference/v1-scope.md)). Requires JDK 21+ for virtual thread
execution.

> This module is for people who **write provider modules**. The testkit for testing application code that uses
> llm4s is [`llm4s-testkit`](../testkit/README.md).

## Install

```scala
// same version as llm4s-core; test scope only
libraryDependencies += "org.llm4s" %% "llm4s-provider-testkit" % "<version>" % Test
```

Requires JDK 21+: interruption checks and `LocalProviderTestServer` run on virtual threads.

## What it provides

| Helper | Type | What it does |
|---|---|---|
| `ProviderModuleChecks` | Trait & object | Assertions for `Llm4s<Name>ModuleSpec`: discovery (`assertModule`, `assertDiscovered`, `assertSoleSupplier`, `assertRegistrableWith`), client building (`assertBuildsClient`, `assertRefusesForeignConfig`), streaming (`assertStreams`), cancellation when interrupted on virtual threads (`assertCancelsWhenInterrupted`, `assertCancelsStreamWhenInterrupted`, `assertEmbeddingCancelsWhenInterrupted`), tool-result contract (`assertOneToolResultPerCall`), and credential bindings (`assertCredentialBindings`, `assertEmbeddingCredentialBindings`). |
| `ProviderTestConfig` | Object | Loads provider sections as an application does (`loadSection`, `loadProvider`, `loadEmbeddings`), resolving `${?VAR}` substitutions against a test-injected environment map with the real process environment disabled. |
| `CredentialsRoundTrip` | Object | Proves that a provider module's `reference.conf` binds what its descriptor declares (`chatBindings`, `chatSectionKey`, `embeddingBindings`, `embeddingsKey`), testing credential fallbacks without leaking developer secrets. |
| `LocalProviderTestServer` | Object | Ephemeral in-process JDK HTTP server on virtual threads for testing client requests, SSE streaming (`sendSseResponse`, `openAISseBody`), and cancellation (`holdOpen`, `streamThenHold`) with zero external network dependencies. |

## Module test skeleton

Mix `ProviderModuleChecks` into a spec of any ScalaTest style.

> **Note:** The skeleton below is an adaptation requiring the provider classes (`AcmeProvider`, `AcmeClient`, `Llm4sAcmeModule`), `META-INF/services` registration, and `reference.conf` credential setup from [Writing a provider](../../docs/guide/writing-a-provider.md), rather than a standalone copy-pasteable example. For complete working specs, see [Working examples](#working-examples) below.

```scala
package com.acme.llm4s

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ CredentialsRoundTrip, ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.testkit.LocalProviderTestServer.{ sendSseResponse, withServer }
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class Llm4sAcmeModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  private val section = NamedProviderConfig(
    provider = AcmeProvider.id,
    model = ModelName("acme-large"),
    baseUrl = None,
    apiKey = Some(ApiKey("test-key")),
    extras = Map("region" -> "eu-west")
  )

  private val streamBody = "data: {\"delta\":\"Hi\"}\n\ndata: [DONE]\n\n"

  "the llm4s-acme services entry" should {
    "register the module through META-INF/services" in assertModule(new Llm4sAcmeModule)
  }

  "AcmeProvider" should {
    "build an AcmeClient from a section, and refuse foreign configs" in {
      assertBuildsClient(AcmeProvider, section) shouldBe an[AcmeClient]
      assertRefusesForeignConfig(AcmeProvider)
    }

    "actually stream" in {
      withServer("/v1/chat")(exchange => sendSseResponse(exchange, streamBody)) { baseUrl =>
        assertStreams(assertBuildsClient(AcmeProvider, section.withBaseUrl(Some(BaseUrl(baseUrl)))))
      }
    }

    "load from a named section as an application does" in {
      given ProviderRegistry = ProviderRegistry.default
      ProviderTestConfig
        .loadProvider(
          "acme-main",
          """llm4s.providers.acme-main { provider = "acme", model = "acme-large" }""",
          Map("ACME_API_KEY" -> "test-key")
        )
        .map(_.asInstanceOf[AcmeConfig].region) shouldBe Right("eu-west")
    }
  }

  "the llm4s-acme reference.conf" should {
    "bind ACME_API_KEY to llm4s.credentials.acme.apiKey" in {
      assertCredentialBindings(AcmeProvider)
    }
  }
```

## Working examples

Full working module specs in this repository:

- `modules/providers/bedrock/src/test/scala/org/llm4s/llmconnect/provider/Llm4sBedrockModuleSpec.scala`
- `modules/providers/voyage/src/test/scala/org/llm4s/llmconnect/provider/Llm4sVoyageModuleSpec.scala`
- `modules/providers/jina/src/test/scala/org/llm4s/llmconnect/provider/Llm4sJinaModuleSpec.scala`

## Tests

```bash
sbt providerTestkit/test   # unit tests for provider-testkit itself
```

## See also

- [Writing a provider](../../docs/guide/writing-a-provider.md) - the complete guide to implementing a provider module
- [llm4s-testkit](../testkit/README.md) - application-level test harness with scripted LLM clients
- [1.0 Scope](../../docs/reference/v1-scope.md) - stability and module maturity
