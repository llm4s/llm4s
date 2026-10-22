# llm4s-bedrock

AWS Bedrock chat provider for llm4s, built on the AWS SDK for Java v2 Bedrock Runtime (`bedrockruntime`).
Reaches models hosted on Bedrock (Anthropic Claude, Meta Llama, Mistral, Amazon Nova, Amazon Titan, and others)
through Bedrock's Converse and ConverseStream APIs. Beta (see
[`docs/reference/v1-scope.md`](../../../docs/reference/v1-scope.md)).

## Install

```scala
libraryDependencies += "org.llm4s" %% "llm4s-bedrock" % "<version>"
```

Brings in `llm4s-core` and the AWS SDK v2 Bedrock Runtime (`software.amazon.awssdk:bedrockruntime`); core itself
depends on no cloud vendor SDK. The provider registers itself through `META-INF/services`. Which release carries
which module is in the [installation guide](../../../docs/getting-started/installation.md); this README
describes `main`.

## Providers

| Kind | Id | Default base URL | Credentials |
|---|---|---|---|
| Chat | `bedrock` | AWS regional endpoint (override with `baseUrl`) | AWS SDK default credential chain, profile, or explicit access keys |

`Llm4sBedrockModule` contributes chat only; it provides no embedding provider.

## Configuration

Bedrock has **no single API key**: it authenticates with AWS credentials. When no explicit credentials or
profile are specified in the section, the AWS SDK default credential chain is used (environment variables
`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`, `~/.aws/credentials`, EC2 instance profile, ECS task role).
Therefore, there is no `llm4s.credentials.bedrock` block.

A minimal section needs only `provider`, `model`, and `region`:

```hocon
llm4s.providers {
  provider = "claude-bedrock"      # the default section

  claude-bedrock {
    provider = "bedrock"
    model    = "anthropic.claude-3-5-sonnet-20241022-v2:0"
    region   = ${?AWS_REGION}
  }
}
```

Configured settings:

- `region`: required, never defaulted (`${?AWS_REGION}`). A model is only available in specific regions;
  an explicit region prevents sending requests to the wrong region.
- `model`: Bedrock model ID or regional inference profile ID (e.g.
  `"anthropic.claude-3-5-sonnet-20241022-v2:0"` or `"us.anthropic.claude-3-5-sonnet-20241022-v2:0"`).
- `profile`: optional named AWS shared-config profile (`${?AWS_PROFILE}`).
- `accessKeyId`, `secretAccessKey`, `sessionToken`: explicit static credentials (`${?AWS_ACCESS_KEY_ID}`,
  `${?AWS_SECRET_ACCESS_KEY}`, `${?AWS_SESSION_TOKEN}`). `accessKeyId` and `secretAccessKey` must be set
  together. `sessionToken` is optional for temporary session credentials. Explicit credentials cannot be
  combined with `profile`.
- `baseUrl`: optional endpoint URL override (for VPC endpoints or local test servers).
- `timeouts`: optional `timeouts { request = 3m, stream = 15m }` block. `request` sets the AWS SDK
  `apiCallTimeout` for `Converse`; `stream` sets a deadline on the whole `ConverseStream` call. Without
  this block, the SDK keeps its default timeout for requests, and streams have no limit (see
  [Timeouts](../../../docs/getting-started/configuration.md#timeouts)). Either expiry returns `TimeoutError`.

Example with a shared profile and timeouts:

```hocon
llm4s.providers.bedrock-prod {
  provider = "bedrock"
  model    = "us.anthropic.claude-3-5-sonnet-20241022-v2:0"
  region   = "us-east-1"
  profile  = "production"
  timeouts {
    request = 2m
    stream  = 10m
  }
}
```

Example with explicit static credentials:

```hocon
llm4s.providers.bedrock-sts {
  provider        = "bedrock"
  model           = "us.amazon.nova-micro-v1:0"
  region          = "us-east-1"
  accessKeyId     = ${?AWS_ACCESS_KEY_ID}
  secretAccessKey = ${?AWS_SECRET_ACCESS_KEY}
  sessionToken    = ${?AWS_SESSION_TOKEN}
}
```

## Minimal example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService

val reply = for {
  providerConfig  <- Llm4sConfig.provider("claude-bedrock")
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client     <- LLMConnect.getClient(providerConfig)
  completion <- client.complete(Conversation(Seq(UserMessage("Say hello in one word."))))
} yield completion.content
```

## What it supports

- **Streaming and tool calling.** `complete` and `streamComplete` use Bedrock's Converse and ConverseStream APIs.
- **Streaming concurrency and cancellation.** `ConverseStream` uses the AWS SDK asynchronous client; callbacks
  enqueue events into an internal queue, and the calling thread drains the queue. `onChunk` runs sequentially on
  the caller's thread. Interrupting the calling thread cancels the request and returns `Left(CancelledError)`.
- **Inference profiles and model registry.** Supports regional inference profiles (`us.`, `eu.`, `apac.`,
  `global.`) as well as direct base model IDs. Context window capacity and completion reserve are automatically
  resolved from the model registry.
- **Structured output is not sent via API parameter.** Structured output (`CompletionOptions.responseFormat`) is
  not sent via API-level schema enforcement on BedrockClient; format instructions should be included in prompt
  messages.
- **Error mapping:** AWS SDK exceptions are mapped to llm4s typed errors: `ThrottlingException` and
  `ServiceQuotaExceededException` to `RateLimitError`, `ValidationException` to `ValidationError`,
  `AccessDeniedException` (and 401/403) to `AuthenticationError`, timeouts to `TimeoutError`, and network connection
  failures to `NetworkError`.

## Tests

```bash
sbt bedrock/test   # unit tests, incl. Llm4sBedrockModuleSpec, BedrockConfigSpec, BedrockConfiguredTimeoutsSpec
sbt testSmoke      # the @Cloud tier in modules/it, incl. BedrockSmokeSpec: needs AWS credentials
```

## See also

- [Writing a provider](../../../docs/guide/writing-a-provider.md) - the provider SPI this module implements
- [Providers guide](../../../docs/guide/providers.md) and [configuration](../../../docs/getting-started/configuration.md)
- [1.0 Scope](../../../docs/reference/v1-scope.md) - stability and module maturity
