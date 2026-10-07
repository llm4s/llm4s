# Contributing to LLM4S

Thank you for your interest in contributing to LLM4S!

## Code of Conduct

This project follows our [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold these standards.

## Developer Certificate of Origin (DCO)

All commits must be signed off to certify you have the right to submit the code under the project's [MIT license](LICENSE), per the [Developer Certificate of Origin](https://developercertificate.org/). Add the `Signed-off-by` trailer by committing with `-s`:

```bash
git commit -s -m "[FEATURE] Add my change"
```

If you forget, sign off your most recent commit with:

```bash
git commit --amend -s --no-edit
```

Pull requests are checked for DCO sign-off automatically in CI.

## Getting Started as a Contributor

### 1. Prerequisites checklist
- [ ] JDK 21+ installed (`java -version`)
- [ ] sbt 1.9+ installed (`sbt -version`)
- [ ] Git configured with your name and email
- [ ] Fork and clone the repo

### 2. First build
```bash
git clone https://github.com/YOUR-USERNAME/llm4s.git
cd llm4s
git remote add upstream https://github.com/llm4s/llm4s.git   # to sync your fork later
sbt compile       # should succeed in ~3 minutes first time
sbt core/test     # run just the unit tests (no API keys needed)
```

### 3. Install the pre-commit hook
```bash
./hooks/install.sh
```

### 4. Your first change (5-minute exercise)
Pick a [good first issue](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) and follow this workflow:
```bash
git checkout -b my-first-contribution
# make the change
sbt scalafmtAll   # format
sbt core/test     # verify
git commit -m "[TEST] Add tests for XYZ"
git push origin my-first-contribution
# Open PR against main
```

### 5. How to run a specific test
```bash
sbt "core/testOnly *PIIDetectorSpec"
```

### 6. How to run a sample
The samples read `modules/samples/src/main/resources/application.conf`, whose default provider
is a local Ollama section (`ollama-local`) using the model `llama3:latest`. With Ollama running,
pull that model once and run a sample:
```bash
ollama pull llama3
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```
To use a model you already have instead, name it with `OLLAMA_MODEL` (the samples'
`application.conf` binds it; `ollama list` shows what is installed), e.g.
`export OLLAMA_MODEL=llama3.2`. `OLLAMA_BASE_URL` points at a server other than
`http://localhost:11434`.
To use a cloud provider, add a named section to
`modules/samples/src/main/resources/application.local.conf` (ignored by git, included by the
samples' `application.conf`) and select it with `LLM4S_PROVIDER`, which the samples'
`application.conf` binds:
```hocon
llm4s.providers.openai-main {
  provider = "openai"
  model    = "gpt-4o-mini"
}
```
```bash
export OPENAI_API_KEY=sk-...
export LLM4S_PROVIDER=openai-main
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```
The section needs no `apiKey`: `llm4s-openai` binds `OPENAI_API_KEY` to OpenAI's shared key,
`llm4s.credentials.openai.apiKey`, which a section without a key of its own uses. llm4s reads no
`LLM_MODEL`; see [Running the samples](docs/getting-started/configuration.md#running-the-samples)
and [API keys](docs/getting-started/configuration.md#api-keys).

### 7. Where to ask for help
- **Discord:** [Join the community](https://discord.gg/4uvTPn6qww)
- **Issues:** [Open an issue](https://github.com/llm4s/llm4s/issues) for bugs, questions, or feature requests
- **Dev Hour:** We host a contributor dev hour on Discord every Tuesday at 17:00 UTC.
- **Support guide:** [SUPPORT.md](SUPPORT.md) lists where to ask questions, report bugs and report vulnerabilities.

## Pull Request Workflow

1. **Read the docs:**
   - [AGENTS.md](AGENTS.md) - Repository structure, build commands, and testing
   - [CLAUDE.md](CLAUDE.md) - Code conventions, patterns, and guidelines

2. **Open an issue first:**
   - Search [existing issues](https://github.com/llm4s/llm4s/issues) to avoid duplicates
   - Use [issue templates](https://github.com/llm4s/llm4s/issues/new/choose) for bugs, features, or enhancements
   - Wait for maintainer feedback before coding

3. **Make your changes:**
   - Follow code conventions in [CLAUDE.md](CLAUDE.md)
   - Use `Result[A]` for errors (not exceptions)
   - Configure at app edge only (see [AGENTS.md](AGENTS.md#configuration-boundary))
   - Write tests mirroring source structure
   - Run `sbt scalafixAll` to catch boundary and syntax violations early
   - Run `sbt scalafmtAll` before committing

4. **Test thoroughly:**
   ```bash
   sbt scalafixAll        # Run scalafix checks (enforced in CI/compile)
   sbt scalafmtAll        # Format code
   sbt +compile           # Compile all versions
   sbt +test              # Run all tests
   sbt buildAll           # Full pipeline check
   ```

5. **Submit PR:**
   - Write clear title: `[FEATURE]`, `[BUG FIX]`, `[DOCS]`, etc.
   - Describe what changed and why
   - Reference related issues: `Fixes #123`
   - Respond to reviewer feedback

## Code Conventions

See [CLAUDE.md](CLAUDE.md) for detailed guidelines. Key points:

- **Naming:** Types `PascalCase`, values `camelCase`, constants `SCREAMING_SNAKE_CASE`
- **Error handling:** Use `Result[A]`, not exceptions
- **Configuration:** Only at app edge (samples, CLIs, tests) - never in core code
- **Type safety:** Use newtypes for domain values (`ApiKey`, `ModelName`)
- **Immutability:** Prefer immutable data structures

### Provider-specific config keys

A provider is a `ProviderDescriptor` in its own module (see [CLAUDE.md](CLAUDE.md), invariant 8).
When its named section needs a setting the built-in fields (`provider`, `model`, `baseUrl`,
`apiKey`, `headers`) do not name - a cloud region, a project id, a credentials profile - declare
it in the descriptor's `ProviderConfigSpec.extras`. That holds even for a key another provider
already uses: Azure's `endpoint` and `apiVersion`, OpenAI's `organization` and the generic
provider's `contextWindow` are each declared by their own descriptors
([#1133](https://github.com/llm4s/llm4s/issues/1133)), and never become fields of
`NamedProviderConfig`, which `llm4s-core` freezes. Do **not** reuse `baseUrl` for something
else: that is the workaround [#1215](https://github.com/llm4s/llm4s/issues/1215) removed from
Vertex AI.

```scala
val configSpec = ProviderConfigSpec(
  extras = Seq(
    ProviderConfigKey.required("region", "the AWS region hosting the model, e.g. us-east-1"),
    ProviderConfigKey.optional("profile", "the AWS named profile to authenticate with")
  )
)

def buildConfig(providerName: String, section: NamedProviderConfig)(using ContextWindowResolver) =
  for
    region <- ProviderDescriptor.requireExtra(providerName, section, "region")
    config <- BedrockConfig.fromValues(section.model.asString, region, section.extra("profile"))
  yield config
```

Validation does the rest before `buildConfig` runs: a missing required key fails with its name,
the section and your `description`; `default`s are filled in; `deprecatedAliases` let you rename a
key without breaking configs, with a warning (an alias may be any former key, or `baseUrl` or
`apiKey`, the built-in fields in `ProviderConfigSpec.BuiltinAliasKeys`); undeclared keys are
dropped with a warning. Values are strings - parse and reject a malformed one in `buildConfig`.
Set `env` (or `baseUrlEnv`) to the key's conventional environment variable, if it has one: a
section's extra keys and `baseUrl` read no variable by themselves, so the missing-key error shows
the `key = ${?VAR}` binding that would read it, never a bare "set VAR". The API key is different:
it falls back to the vendor's shared `llm4s.credentials.<id>.apiKey`, which the module binds in
its `reference.conf`, and `apiKeyEnv` names that variable in the error.

## Testing

See [AGENTS.md](AGENTS.md#testing-guidelines) for details:

- Place tests in `modules/core/src/test/scala/org/llm4s/`
- Name tests with `Spec` suffix
- Use ScalaTest's FlatSpec style
- Test both happy path and error cases
- Maintain 80%+ coverage

### Tests that need a real service

Suites needing a database, a container, a local model server or an API key go in
`modules/it`, and each one must declare which tier runs it by annotating the class with
exactly one tag from `org.llm4s.it.tags`. `sbt it/itTierCheck` (run in CI) fails the build
if a suite declares none, so a new suite cannot end up being run by nothing:

| Tag | Needs | Command |
|---|---|---|
| `@Local` | nothing external | `sbt test` |
| `@Docker` | Postgres/pgvector, Qdrant or Neo4j | `sbt testIntegration` |
| `@Workspace` | Docker + a built `workspace-runner` image | `sbt testWorkspace` |
| `@Ollama` | a local Ollama server | `sbt testOllama` |
| `@Cloud` | live provider API keys | `sbt testSmoke` |

Gate on `Tier.require(...)` rather than `assume(...)`: with `LLM4S_IT_STRICT=true`, which
every tier's CI job sets, an unavailable dependency then fails the build instead of skipping
quietly. Full details in the
[Testing Guide](docs/reference/testing-guide.md#9-integration-test-tiers-modulesit).

## Adding an OpenAI-compatible provider (a dialect)

`llm4s-openai-compatible` (`modules/openai-compatible`) has one SDK-free client for every
provider that speaks OpenAI's `/chat/completions` API: `OpenAICompatibleClient`. DeepSeek, Z.ai,
OpenRouter, Mistral and Cohere are each a few lines on top of it - an `OpenAICompatibleDialect`
for where the provider departs from the standard format, and a `ProviderDescriptor` to register
it. Extend it this way; never copy the client. (The module used to hold three ~400-line copies,
which drifted apart; see [#1132](https://github.com/llm4s/llm4s/issues/1132).)

### 1. Check whether the generic provider already covers it

Many endpoints need no code at all. The generic provider, `provider = "openai-compatible"`, sends
the standard format to any `baseUrl`, with an optional `apiKey`, extra `headers`, and the model's
`contextWindow`. Try the endpoint with a named section first - the
[providers guide](docs/guide/providers.md#openai-compatible-endpoints) has recipes for Groq,
Together, Fireworks, xAI, vLLM, LM Studio and others. If that works, a recipe in the guide is the
whole contribution.

Write a dialect only when the provider needs something the generic path
[does not do](docs/guide/providers.md#what-the-generic-path-does-and-does-not-do): reasoning
parameters, reading its thinking or reasoning tokens, a different message or `response_format`
encoding, a different system role, tool-call ids in its own format, or refusing a field the
standard format sends.

### 2. The dialect hooks

`OpenAICompatibleDialect` is a trait whose every member defaults to the standard format, so a
dialect overrides only where the provider differs:

| Hook | Default | Override when the provider... |
|---|---|---|
| `headers` | none | needs extra headers on every request (OpenRouter's `HTTP-Referer`, a `User-Agent`) |
| `systemRole` | `"system"` | takes system instructions under another role (Cohere: `"developer"`) |
| `encodeContent(text)` | a JSON string | wants message text in another shape (Z.ai: an array of text parts) |
| `alwaysSendAssistantContent` | `false` | needs `content` on an assistant turn that has only tool calls (OpenRouter) |
| `sendEmptyAssistantTurns` | `true` | rejects an assistant turn with neither text nor tool calls (Mistral) |
| `encodeToolCallId(id)` | the id unchanged | accepts only its own id format (Mistral: nine alphanumerics); must be deterministic |
| `encodeResponseFormat(format)` | OpenAI's `json_object` / `json_schema` | has its own structured-output shape (Cohere), or `None` to send none |
| `streamUsageOption` | `true` | rejects or ignores `stream_options.include_usage` on streams (Mistral, Z.ai, Cohere, OpenRouter) |
| `addReasoning(body, model, options)` | adds nothing | takes reasoning parameters (OpenRouter's `thinking` / `reasoning_effort`) |
| `decodeContent(value)` | a JSON string | returns `content` in another shape (Z.ai's parts, Mistral's chunks) |
| `thinking(obj)` | none | returns the model's reasoning text (DeepSeek's `reasoning_content`) |
| `reasoningTokens(usage)` | none | reports reasoning tokens in `usage` (DeepSeek's `completion_tokens_details`) |
| `parseToolCalls(json)` | lenient: missing fields defaulted | needs stricter parsing (OpenRouter) |

Check each against the provider's API reference, and say in the member's Scaladoc which
document the choice came from - `streamUsageOption` especially, since an unknown field is
ignored by some providers and a 400 or 422 from others. If a difference fits none of these,
add a member to the trait, defaulting to today's behaviour, rather than forking the client.

### 3. A worked example: Cohere

Cohere's OpenAI-compatibility API differs in three places, so `CohereClient.scala` holds a
dialect of three members and a thin client:

```scala
private[llm4s] object CohereDialect extends OpenAICompatibleDialect:
  override val systemRole: String = "developer"

  /** Not on the Compatibility API's parameter lists, which do not say what an unknown field does. */
  override val streamUsageOption: Boolean = false

  override def encodeResponseFormat(format: ResponseFormat): Option[ujson.Value] =
    format match
      case ResponseFormat.Json => Some(ujson.Obj("type" -> "json_object"))
      case ResponseFormat.JsonSchema(schema, _, _) =>
        Some(ujson.Obj("type" -> "json_object", "schema" -> schema))

class CohereClient(
  config: CohereConfig,
  metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using ModelRegistryService)
    extends OpenAICompatibleClient(
      OpenAICompatibleClient.Settings(
        providerName = "cohere",   // metrics, exchange log and error label
        displayName = "Cohere",    // log lines and the "already closed" error
        model = config.model,
        baseUrl = config.baseUrl,  // requests go to <baseUrl>/chat/completions
        apiKey = Some(config.apiKey),
        contextWindow = config.contextWindow,
        reserveCompletion = config.reserveCompletion
      ),
      CohereDialect,
      metrics,
      exchangeLogging
    )
```

plus a companion `apply` returning `Result[CohereClient]`. Everything else - the HTTP round trip,
SSE streaming, tool-call fan-out, token usage, error mapping, exchange logging, timeouts - comes
from `OpenAICompatibleClient`.

### 4. Config, descriptor and registration

All in `modules/openai-compatible`, keeping the `org.llm4s.*` packages:

- **Config**: `llmconnect/config/<Name>Config.scala`, a `ProviderConfig` case class with a fixed
  `providerId`, the API key redacted in `toString`, a `DEFAULT_BASE_URL`, and a `fromValues`
  returning `Result` that resolves the context window through `ContextWindowResolver`
  (`CohereConfig` is the example). A provider with no config of its own can reuse
  `OpenAIConfig`, as OpenRouter does - then pass its id to `OpenAIConfig.fromValues(...,
  providerId = Some(id))`, or the config reports `openai`.
- **Descriptor**: `llmconnect/provider/<Name>Provider.scala`, an `object` extending
  `ProviderDescriptor` with its `id`, a `configSpec`
  (`ProviderConfigSpec.apiKeyAndDefaultBaseUrl(DEFAULT_BASE_URL, Seq(<NAME>_API_KEY))` for the
  usual case - `apiKeyEnv` names the vendor's variable in the missing-key error),
  `buildConfig` from the named section and `buildClient` through
  `ProviderDescriptor.expectConfig`. Give it a `modelLister` if the provider has a `/models`
  endpoint (`OpenAICompatibleModelListers.scala`).
- **Registration**: add the descriptor to `chatProviders` in `Llm4sOpenAICompatibleModule`. The
  module is already declared in `META-INF/services`, so nothing else is needed - and nothing in
  `llm4s-core` changes.
- **Shared key**: `llm4s.credentials.<id>.apiKey = ${?<NAME>_API_KEY}` in the module's
  `reference.conf`, using the variable the vendor's own SDK or docs use. Keys only - never
  `baseUrl` or `model`. The module's round-trip spec proves the binding and `apiKeyEnv` agree.
- **Example config**: a commented `<name>-main` section in the module's `reference.conf` - just
  `provider` and `model` - plus, if useful, one with its own `apiKey` for a second account.
- **Environment variable names** go in `OpenAICompatibleConfigKeys`.

### 5. Tests

In `modules/openai-compatible/src/test`, with no network:

- **Dialect spec** (`CohereDialectSpec`, `MistralDialectSpec`): each overridden member, through
  the client's `createRequestBody`, `parseCompletion` and `parseStreamingChunks`.
- **Client spec** over `LocalProviderTestServer` (`CohereClientSpec`,
  `CohereClientStreamingSpec`): a completion, a stream with text and tool calls, an error status
  mapped to its typed error, and the headers and path the request went to.
- **Closed-state test** (`CohereClientClosedStateTest`): calls after `close()` fail with an
  error naming the model.
- **Registration**: add a row to `expectations` in `Llm4sOpenAICompatibleModuleSpec`, which proves
  discovery and the config-to-client round trip for every descriptor.
- **Named-provider spec** (`DeepSeekNamedProviderSpec`): a HOCON section loads to your config,
  with its default base URL.
- **Streamed usage**: add the dialect to the `streamUsageOption` cases in
  `OpenAICompatibleStreamedUsageSpec`.

Then a **`@Cloud` smoke spec** in `modules/it/src/test/scala/org/llm4s/llmconnect/smoke/`,
modelled on `CohereSmokeSpec`: a completion, a stream and a bad key against the live API, gated
with `Tier.require(apiKey.isDefined, "<NAME>_API_KEY not set")` and run by `sbt testSmoke`.
[#1213](https://github.com/llm4s/llm4s/issues/1213) tracks the providers that still lack one.

Keep `openaiCompatible`'s coverage at or above its floor in `build.sbt`
(`sbt coverage openaiCompatible/test openaiCompatible/coverageReport`).

### 6. Docs

- A section for the provider in [`docs/guide/providers.md`](docs/guide/providers.md) - setup, a
  config example, and what it supports - and the provider in the lists at the top of that guide
  and in [the installation guide](docs/getting-started/installation.md).
- The `Llm4sOpenAICompatibleModule` Scaladoc, which lists the module's providers, and the
  module's line in `CLAUDE.md`'s repository structure.
- An entry under **Added** in [`CHANGELOG.md`](CHANGELOG.md).
- If `llm4s-config-policy`'s presets should allow it, their provider lists in `ConfigPolicy`.

## Build Commands

See [AGENTS.md](AGENTS.md#build-test-and-development-commands) for complete list:

```bash
sbt compile           # Compile active Scala version
sbt +compile          # Compile all versions
sbt test              # Run tests
sbt +test             # Run tests all versions
sbt buildAll          # Full pipeline (compile + test all versions)
sbt scalafixAll       # Run Scalafix rules
sbt scalafmtAll       # Format code
```

## Documentation

- **Code:** Add Scaladoc to public APIs with `@param`, `@return`, `@example`
- **Guides:** Add to `docs/guide/` for new features
- **Examples:** Add to `modules/samples/` with runnable code
- **API:** Generated from Scaladoc automatically

## Commit Messages

```
[TYPE] Brief description (50 chars max)

Optional detailed explanation.
- Reference issues: Fixes #123, Relates to #456

BREAKING CHANGE: If applicable
```

Types: `[FEATURE]`, `[BUG FIX]`, `[ENHANCEMENT]`, `[REFACTOR]`, `[DOCS]`, `[TEST]`, `[PERF]`

## Getting Help

- **Issues:** [GitHub Issues](https://github.com/llm4s/llm4s/issues)
- **Discussions:** [GitHub Discussions](https://github.com/llm4s/llm4s/discussions)
- **Discord:** https://discord.gg/4uvTPn6qww
- **Docs:** [Documentation site](https://llm4s.github.io/llm4s/)

---

**Thank you for contributing!** 🎉
