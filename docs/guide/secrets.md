---
layout: page
title: Secrets and API Keys
description: "Choose where a provider's API key comes from: config, environment, a mounted file, HashiCorp Vault or AWS Secrets Manager, with caching and rotation."
search_title: "Read LLM4S API keys from Vault or AWS Secrets Manager"
parent: Operations
nav_order: 704
grand_parent: User Guide
---

# Secrets and API key providers
{: .no_toc }

A provider's API key can come from the configuration file, an environment variable, a file that a secret manager
keeps current, or a secret store that your process reads itself. `llm4s-secrets-vault` and `llm4s-secrets-aws` are the
last kind: an `ApiKeyProvider` that fetches the key from HashiCorp Vault or AWS Secrets Manager over HTTP, caches it and
fetches it again, with no vendor SDK ([#2479](https://github.com/llm4s/llm4s/issues/2479)). Both are Experimental.

1. TOC
{:toc}

---

## Choose a key source

From the simplest to the most dynamic. A provider section uses the first that applies to it.

| Source | Set with | The key changes when |
|:-------|:---------|:---------------------|
| The section's own key | `llm4s.providers.<name>.apiKey` | the application restarts |
| The vendor's shared key | `llm4s.credentials.<id>.apiKey`, bound by each provider module to the vendor's variable (`OPENAI_API_KEY`) | the application restarts |
| A key file | `apiKeyFile` in a section, an embeddings block or `llm4s.credentials.<id>` | the file changes; checked before every request |
| Your own `ApiKeyProvider` | `withApiKeyProvider(provider)` in code | whatever your `current()` does |
| Vault | `VaultApiKeyProvider` (`llm4s-secrets-vault`) | the cache expires, five minutes by default |
| AWS Secrets Manager | `AwsSecretsManagerApiKeyProvider` (`llm4s-secrets-aws`) | the cache expires, five minutes by default |

The first three need no extra module and are described in
[Configuration](../getting-started/configuration#api-keys) and
[Rotating keys](../getting-started/configuration#rotating-keys-apikeyfile-and-apikeyprovider). Prefer them when the
platform can write the key to a file (Vault Agent, the Secrets Store CSI driver, a Kubernetes secret volume): llm4s then
makes no call to the secret store, and the Kubernetes and CSI setups are in
[Production deployment](production-deployment#secrets-and-key-rotation). Use a provider from this page when it cannot.

A section sets one of `apiKey`, `apiKeyFile` and `auth`, never two. A key provider set in code with
`withApiKeyProvider` replaces whatever the section loaded.

```hocon
llm4s.providers.openai-main {
  provider   = "openai"
  model      = "gpt-5.6-luna"
  # no key here: llm4s.credentials.openai.apiKey is bound to OPENAI_API_KEY
}

llm4s.credentials.anthropic.apiKeyFile = "/var/run/secrets/llm4s/anthropic-api-key"
```

## How a provider is attached

`ApiKeyProvider` is a one-method trait, `def current(): Result[ApiKey]`, which a client calls on the request path for
every request (`modules/core/.../llmconnect/auth/ApiKeyProvider.scala`). A config carries one as
`ProviderConfig.apiKeyProvider`, and `withApiKeyProvider(provider)` returns a `Result` holding the config with it set.
A config whose client cannot take one - a provider that authenticates without an API key - returns a
`ConfigurationError` naming the provider, never the config unchanged. `EmbeddingProviderConfig` has the same setter,
which always succeeds, so it returns the config itself. The built-in providers that take an API key support it; the list is in
[Rotating keys](../getting-started/configuration#rotating-keys-apikeyfile-and-apikeyprovider).

The section you load must still resolve a key when it is loaded, so that it validates: a section with no key from the
sources above fails with an error naming both places. To avoid that, build the config in code
([Configuration in code](../getting-started/configuration-in-code)).

## Vault

```sbt
libraryDependencies += "org.llm4s" %% "llm4s-secrets-vault" % "{{ site.data.project.latest_release }}"
```

It depends on `llm4s-core` only. One `GET` against Vault's KV secrets engine, with llm4s's own HTTP client.

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.auth.vault.{ VaultApiKeyProvider, VaultSettings }
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.llm4s.types.ProviderModelTypes.ProviderName

import scala.concurrent.duration.*

def vaultClient(vaultToken: String): Result[LLMClient] =
  for
    keys <- VaultApiKeyProvider(
      VaultSettings("https://vault.internal:8200", Secret(vaultToken), "llm4s/openai", "apiKey")
        .withRefreshInterval(1.minute)
    )
    config   <- Llm4sConfig.provider(ProviderName("openai-main")).flatMap(_.withApiKeyProvider(keys))
    registry <- Llm4sConfig.modelRegistryService()
    client <- {
      given ModelRegistryService = registry
      LLMConnect.getClient(config)
    }
  yield client
```

`VaultSettings(address, token, path, field)` is the only constructor; the rest are `with*` setters.

| Setting | Meaning | Default |
|:--------|:--------|:--------|
| `address` | Vault's base URL, `http(s)://host:port`, without `/v1` | required |
| `token` | the Vault token, a `Secret`, sent as `X-Vault-Token` | required |
| `path` | the secret's path inside the mount, such as `llm4s/openai` | required |
| `field` | the key of the secret's data that holds the API key | required |
| `withMount(m)` | where the KV engine is mounted | `secret` (`VaultSettings.DefaultMount`) |
| `withKvVersion(v)` | `VaultSettings.KvVersion.V2` (`GET /v1/<mount>/data/<path>`) or `V1` (`GET /v1/<mount>/<path>`) | `V2` |
| `withNamespace(ns)` | Enterprise or HCP namespace, sent as `X-Vault-Namespace`; takes a `String` or an `Option` | none |
| `withRefreshInterval(d)` | how long a key is used before it is read again | 5 minutes |
| `withRequestTimeout(d)` | how long one read may take | 10 seconds |

Mount and path lose surrounding slashes, each path segment is URL-encoded, and the version read is always the latest.
The module has no `reference.conf` and no config keys: you build `VaultSettings` in code, from wherever your
application keeps the Vault address and token.

`VaultApiKeyProvider(settings)` returns a `Result`: a `ConfigurationError` that names the setting when the address is not
an http(s) URL, the token is blank or spans lines, the mount, path, field or namespace is blank, or an interval or
timeout is not positive. It does not contact Vault; the first `current()` does. `VaultApiKeyProvider(settings,
httpClient)` reads through an `Llm4sHttpClient` you supply, for a proxy or custom TLS, and leaves it open when the
provider is closed.

## AWS Secrets Manager

```sbt
libraryDependencies += "org.llm4s" %% "llm4s-secrets-aws" % "{{ site.data.project.latest_release }}"
```

It depends on `llm4s-core` only. One `GetSecretValue` `POST` signed with Signature Version 4, which the module
implements itself.

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.auth.aws.{
  AwsCredentials,
  AwsSecretsManagerApiKeyProvider,
  AwsSecretsManagerSettings
}
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.llm4s.types.ProviderModelTypes.ProviderName

import scala.concurrent.duration.*

/** Your own lookup: IRSA, an instance profile, an assumed role. Called again for every read. */
def currentCredentials(): Result[AwsCredentials] =
  Right(AwsCredentials("ASIA...", Secret("secret-access-key")).withSessionToken(Secret("session-token")))

def awsClient(): Result[LLMClient] =
  val settings = AwsSecretsManagerSettings("eu-west-1", AwsCredentials("AKIA...", Secret("unused")), "llm4s/openai")
    .withCredentials(() => currentCredentials())
    .withField("apiKey")
    .withRefreshInterval(1.minute)
  for
    keys     <- AwsSecretsManagerApiKeyProvider(settings)
    config   <- Llm4sConfig.provider(ProviderName("openai-main")).flatMap(_.withApiKeyProvider(keys))
    registry <- Llm4sConfig.modelRegistryService()
    client <- {
      given ModelRegistryService = registry
      LLMConnect.getClient(config)
    }
  yield client
```

`AwsSecretsManagerSettings(region, credentials, secretId)` is the only constructor, so it needs one fixed
`AwsCredentials`; `withCredentials` replaces it. Pass `AwsCredentials(accessKeyId, Secret(secretAccessKey))` for
long-lived credentials and add `.withSessionToken(Secret(token))` for temporary ones.

| Setting | Meaning | Default |
|:--------|:--------|:--------|
| `region` | such as `eu-west-1`; picks the endpoint and is part of the signature | required |
| `credentials` | an `AwsCredentials`, or `withCredentials(() => Result[AwsCredentials])`, called once per read | required |
| `secretId` | the secret's name or ARN | required |
| `withField(f)` | the key of a JSON-object `SecretString` that holds the key; takes a `String` or an `Option` | none: the whole `SecretString` is the key |
| `withVersionStage(s)` | the version stage read | `AWSCURRENT` (`DefaultVersionStage`) |
| `withEndpoint(url)` | `http(s)://host[:port]` with no path, for a VPC or FIPS endpoint; takes a `String` or an `Option` | `https://secretsmanager.<region>.amazonaws.com`, `.amazonaws.com.cn` for a `cn-` region |
| `withRefreshInterval(d)` | how long a key is used before it is read again | 5 minutes |
| `withRequestTimeout(d)` | how long one read may take | 10 seconds |

There is no `reference.conf` and there are no config keys. `AwsSecretsManagerApiKeyProvider(settings)` returns a
`ConfigurationError` for an invalid region, a blank secret id, field or stage, an endpoint that is not an http(s) URL
without a path, or a non-positive interval or timeout. Nothing is contacted, and the credentials function is not called,
until the first `current()`. As with Vault, `apply(settings, httpClient)` takes your `Llm4sHttpClient`.

## When the key is read

The two providers behave the same way (`VaultApiKeyProvider.scala:53-68`,
`AwsSecretsManagerApiKeyProvider.scala:63-78`).

- **Lazily.** The first `current()` reads the key, on the request path. Building the provider reads nothing.
- **Cached for `refreshInterval`.** Later calls return the cached key without contacting the store. After the interval,
  the next `current()` reads again, so a rotated key is sent from the first request after the interval. There is no
  background refresh and no early re-read: the provider has no invalidate method, and a request rejected as
  unauthorized does not trigger one. If the old key is revoked at once on rotation, requests fail until the interval
  ends, so choose an interval shorter than your rotation overlap.
- **One read at a time.** Concurrent callers wait for a single read and share its result. A caller can therefore wait up
  to `requestTimeout` for a read in progress.
- **A failed read is a `Left`, and is retried on the next call.** The expired key is not served in its place, because
  a rotated-out key may already be revoked. A failure is never cached, so a store that comes back is used at the next
  request. Nothing retries within one read.
- **Credentials too.** For AWS, `credentials()` runs inside each read, not each request, so with the default interval a
  function is called at most once per `refreshInterval`. A function that throws or returns a `Left` fails that read
  with a `ConfigurationError`; the credentials are checked for blank parts and line breaks before AWS is contacted.

## What failures look like

The key and the token or credentials are never in an error. The error is the `Left` that `current()` returns, so a
client call fails with it.

| Condition | Vault | AWS Secrets Manager |
|:----------|:------|:--------------------|
| Store refuses the caller | `AuthenticationError` on 401 or 403, with Vault's `errors` text | `AuthenticationError` on 401, 403 or an auth error type such as `AccessDeniedException`, `ExpiredTokenException`, `InvalidSignatureException` |
| No such secret | `NotFoundError` on 404 | `NotFoundError` on `ResourceNotFoundException` (also a missing version stage) |
| Secret has no usable key | `ConfigurationError`: field missing, not a string, blank, not JSON, or more than one line | `ConfigurationError`: binary-only secret, no `SecretString`, field missing or blank, `SecretString` not JSON, or more than one line |
| Any other answer | `ServiceError` with the status | `ServiceError` with the status |
| Store unreachable or timed out | the HTTP client's own error | the HTTP client's own error |

A key is trimmed before use. Both error paths remove the token or the secret access key and session token from anything
the store echoes back. A provider that throws instead of returning a `Left` is turned into an `AuthenticationError`
naming the exception class.

## What is never logged

A token, a secret access key and a session token are `Secret`s: their `toString` is `***` and their value is read only
with `reveal`. `VaultSettings`, `AwsSecretsManagerSettings`, `AwsCredentials` and both providers print the location
(`mount/path#field`, `secretId#field`) and never the credential. A key read is an `ApiKey`, which registers it for
redaction, so an error from a provider that echoes it is scrubbed. Neither module logs. Keep your own logging of settings
to the same rule; see [Security best practices](patterns/security-best-practices).

## Closing

Both providers are `AutoCloseable`. Closing a provider closes the HTTP client it created and leaves one you supplied
alone. A provider lives as long as the client that uses it, so close it when the application stops.

## Testing without Vault or AWS

The addresses are settings, so a test points the provider at a local server; the modules' own specs
(`VaultApiKeyProviderSpec`, `AwsSecretsManagerApiKeyProviderSpec`) run against a JDK `HttpServer` this way, and AWS's
checks every request's signature. Use `http://` and an `endpoint` for AWS only in tests.

```scala
import com.sun.net.httpserver.HttpServer
import org.llm4s.llmconnect.auth.vault.{ VaultApiKeyProvider, VaultSettings }
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.asKey
import org.llm4s.types.Result

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

def readFromFakeVault(): Result[String] =
  val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  server.createContext(
    "/v1/secret/data/llm4s/openai",
    exchange =>
      val body = """{"data":{"data":{"apiKey":"sk-test-key"}}}""".getBytes(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(200, body.length.toLong)
      exchange.getResponseBody.write(body)
      exchange.close()
  )
  server.start()
  val address = s"http://127.0.0.1:${server.getAddress.getPort}"
  val key = VaultApiKeyProvider(VaultSettings(address, Secret("test-token"), "llm4s/openai", "apiKey"))
    .flatMap(_.current())
    .map(_.asKey)
  server.stop(0)
  key // Right("sk-test-key")
```

For tests of code that merely needs a key, skip the module: `ApiKeyProvider.static(ApiKey("sk-test"))` always returns
one, and a small class implementing `current()` can count calls or fail on demand. To test a whole client, see
[Your HTTP client](http-transport).

## Limits

- **Vault authenticates with a token only.** There is no AppRole, Kubernetes, AWS or JWT login, and the token is not
  renewed: pass a token that lives as long as the process, or run Vault Agent and use `apiKeyFile`. Only the KV engine,
  version 1 or 2, is read; the latest version of a secret, never a named one, and no dynamic secrets or leases.
- **AWS gets no credentials itself.** It does not read the default credential chain, environment variables, profiles,
  an instance profile or IRSA; your code supplies them, as a function if they expire. It reads `SecretString`, never
  `SecretBinary`, selected by a version stage, not a version id, and the key must be one line.
- Neither sets a retry, backoff or circuit breaker around a read, and neither refreshes in the background.
- One provider reads one key. For several providers, make one per secret.
- Both are `@Experimental`: their signatures can still change before 1.0.

## See also

- [Configuration: API keys](../getting-started/configuration#api-keys) and
  [rotating keys](../getting-started/configuration#rotating-keys-apikeyfile-and-apikeyprovider)
- [Production deployment: secrets and key rotation](production-deployment#secrets-and-key-rotation)
- [Security best practices](patterns/security-best-practices)
