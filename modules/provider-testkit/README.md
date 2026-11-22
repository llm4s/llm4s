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
| `ProviderModuleChecks` | Trait & object | Assertions for `Llm4s<Name>ModuleSpec`: discovery (`assertModule`, `assertDiscovered`, `assertSoleSupplier`, `assertRegistrableWith`), client building (`assertBuildsClient`, `assertRefusesForeignConfig`), the model call contract (`assertModelCallContract`: how a reply ended, on `complete` and on a stream alike, and that the client really streams; see [below](#the-model-call-contract)), cancellation when interrupted on virtual threads (`assertCancelsWhenInterrupted`, `assertCancelsStreamWhenInterrupted`, `assertEmbeddingCancelsWhenInterrupted`), tool-result contract (`assertOneToolResultPerCall`), and credential bindings (`assertCredentialBindings`, `assertEmbeddingCredentialBindings`). |
| `ProviderTestConfig` | Object | Loads provider sections as an application does (`loadSection`, `loadProvider`, `loadEmbeddings`), resolving `${?VAR}` substitutions against a test-injected environment map with the real process environment disabled. |
| `CredentialsRoundTrip` | Object | Proves that a provider module's `reference.conf` binds what its descriptor declares (`chatBindings`, `chatSectionKey`, `embeddingBindings`, `embeddingsKey`), testing credential fallbacks without leaking developer secrets. |
| `ContractCase`, `Applicability`, `RecordedReply` | Enum & case classes | The nine RFC 0009 endings `assertModelCallContract` checks, whether each applies to a provider, and the provider's `complete` and stream replies for one. |
| `LocalProviderTestServer` | Object | Ephemeral in-process JDK HTTP server on virtual threads for testing client requests, SSE streaming (`sendSseResponse`, `openAISseBody`), and cancellation (`holdOpen`, `streamThenHold`) with zero external network dependencies. |

## Module test skeleton

Mix `ProviderModuleChecks` into a spec of any ScalaTest style.

> **Note:** The skeleton below is an adaptation requiring the provider classes (`AcmeProvider`, `AcmeClient`, `Llm4sAcmeModule`), `META-INF/services` registration, and `reference.conf` credential setup from [Writing a provider](../../docs/guide/writing-a-provider.md), rather than a standalone copy-pasteable example. For complete working specs, see [Working examples](#working-examples) below.

```scala
package com.acme.llm4s

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ CredentialsRoundTrip, ProviderModuleChecks, ProviderTestConfig }
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

  "the llm4s-acme services entry" should {
    "register the module through META-INF/services" in assertModule(new Llm4sAcmeModule)
  }

  "AcmeProvider" should {
    "build an AcmeClient from a section, and refuse foreign configs" in {
      assertBuildsClient(AcmeProvider, section) shouldBe an[AcmeClient]
      assertRefusesForeignConfig(AcmeProvider)
    }

    "report how each reply ended, on complete and on a stream alike, and really stream (RFC 0009)" in {
      assertModelCallContract(AcmeContractFixtures.replies, "/v1/chat") { baseUrl =>
        assertBuildsClient(AcmeProvider, section.withBaseUrl(Some(BaseUrl(baseUrl))))
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

## The model call contract

`assertModelCallContract(cases, path)(baseUrl => client)` holds a chat client to
[RFC 0009](../../docs/rfcs/0009-model-call-contract.md): for each `ContractCase` it serves the provider's reply
from a `LocalProviderTestServer`, runs `complete` and `streamComplete`, and checks the result.

| `ContractCase` | Required result |
|---|---|
| `CleanText` | `Right`, `FinishReason.Stop`, non-empty text, no tool call |
| `TextCutByLimit` | `Right`, `FinishReason.Length`, the partial text |
| `ToolCall` | `Right`, `FinishReason.ToolCalls`, each call's arguments a JSON object |
| `NoArgumentToolCall` | `Right`, `FinishReason.ToolCalls`, arguments `{}` |
| `ToolCallCutByLimit` | `Left(ProcessingError)`, the malformed tool call: no call in a `Length` reply runs |
| `MalformedArguments` | `Left(ProcessingError)`, the malformed tool call: never `{}`, never dropped |
| `ContentFiltered` | `Left(ContentFilteredError)` |
| `StreamEndsEarly` (stream only) | `Left(NetworkError)`, or `Left(ServiceError)` with status 502 |
| `ErrorEvent` (stream only) | a `Left` whose message carries `ContractCase.ProviderErrorMessage` |

- Declare every case: `Applicability.Applies(RecordedReply(complete, stream))` with the provider's bytes (the
  stream-only cases use `RecordedReply.streamOnly(stream)`), or `Applicability.NotApplicable(reason)` with a
  non-blank reason when the provider cannot produce it. A case left out fails the check.
- `CleanText` must apply: its stream is what proves the client really streams. A successful stream's chunks must add
  up to its `Completion` (text, last finish reason, tool call ids).
- The `complete` path's tool-call rules (a tool call in a `Length` reply, arguments that are not a JSON object) are
  the client's own to apply; see [Writing a provider](../../docs/guide/writing-a-provider.md#the-model-call-contract).

`assertStreams` is gone: `assertModelCallContract` proves streaming too. Replace an
`assertStreams(client)` test with the full check.

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
