# SPIFFE workload identity for providers - design

Date: 2026-10-04
Status: approved in brainstorming, awaiting written-spec review

## Intent

LiteLLM cannot attach a SPIFFE-derived credential to provider requests, which breaks Databricks
model serving when the workspace is configured for workload identity federation rather than
personal access tokens. llm4s should support this from configuration alone, refresh credentials
before and after expiry instead of failing, and prove it works against a real SPIFFE issuer
without a real Databricks workspace.

**Success criteria**

- A config-driven `openai-compatible` section can call a Databricks serving endpoint using a
  JWT-SVID file maintained by `spiffe-helper`, with no code changes.
- Tokens are refreshed before expiry; a 401 triggers one refresh-and-retry rather than a failure.
- `llm4s-openai` and `llm4s-anthropic` can authenticate with SPIFFE JWT-SVIDs through their
  SDKs' native workload identity federation (WIF).
- Tests prove the flow at four layers: unit, fake-server module specs, a real SPIRE stack, and
  the vendor SDKs driven by real SVIDs.

**Out of scope**: testing against a real Databricks workspace or real OpenAI/Anthropic WIF
endpoints; a SPIFFE Workload API (gRPC) token source; Kubernetes (kind) test environments;
`auth` for embeddings and the reranker; migrating `VertexAIAuthProvider` onto the new types.

## Background

Databricks does not accept a raw JWT as a bearer. The workload exchanges it (RFC 8693) at
`POST https://<workspace>/oidc/v1/token` with form fields
`grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, `subject_token=<jwt>`,
`subject_token_type=urn:ietf:params:oauth:token-type:jwt`, `scope=all-apis` and, for a
service-principal federation policy, `client_id=<sp uuid>`. The response holds `access_token`
and `expires_in`; the access token expires with the JWT. The serving endpoint at
`https://<workspace>/serving-endpoints/chat/completions` speaks OpenAI chat-completions.

`spiffe-helper` fetches a JWT-SVID for an audience from the SPIRE agent socket, writes it to a
file and rewrites it before expiry (SPIRE's default SVID TTL is 5 minutes).

The bundled SDKs already support WIF:

- `openai-java` 4.69.3: `OpenAIOkHttpClient.builder().workloadIdentity(WorkloadIdentity)`, where
  `WorkloadIdentity` takes `identityProviderId`, `serviceAccountId`, optional `clientId` and a
  `SubjectTokenProvider` (`tokenType()`, `getToken(...)`, `getTokenAsync(...)`). The exchange URL
  is hard-coded to `https://auth.openai.com/oauth/token`. The SDK invalidates and retries on 401.
- `anthropic-java` 2.65.0: `AnthropicOkHttpClient.builder().configurationProvider(...)` with an
  `InMemoryProfileConfigProvider(ProfileConfig)` whose auth is workload identity and whose
  `IdentityTokenConfig` is `source = "file"`, `path = ...`. The exchange posts a `jwt-bearer`
  grant to `<baseUrl>/v1/oauth/token` with `federation_rule_id`, `organization_id`, optional
  `service_account_id` and `workspace_id`. The programmatic token-provider hook is
  Kotlin-internal, so only the file source is reachable.

Today `OpenAICompatibleClient.requestHeaders` (rebuilt per request) sends
`Authorization: Bearer <static apiKey>`; `HttpErrorMapper` maps 401/403 to
`AuthenticationError`, which `RetryPolicy.isRetryable` treats as non-retryable.

## Design

### 1. Core: `org.llm4s.llmconnect.auth` (in `llm4s-core`)

All operations return `Result`; no exceptions escape.

- `IdentityTokenSource` - `fetch(): Result[String]`.
  - `IdentityTokenSource.file(path)` re-reads the file on every fetch (so `spiffe-helper`
    rotation is picked up), trims it, and returns `AuthenticationError` for a missing, unreadable,
    empty or whitespace-only file.
  - `IdentityTokenSource.static(token)` for tests and literal config.
- `AccessToken(value: String, expiresAt: Instant)` - `toString` redacts the value.
- `AccessTokenProvider` - `token(): Result[String]`; `invalidate(rejected: String): Unit` drops
  the cached token only if it equals `rejected`, so a burst of concurrent 401s causes one refresh.
- `CachingAccessTokenProvider(fetch: () => Result[AccessToken], refreshMargin: FiniteDuration,
  clock: Clock)` - caches until `expiresAt - refreshMargin`; concurrent callers share one
  in-flight refresh (lock + double-check, as `VertexAIAuthProvider` does); failures are not cached.
- `TokenExchange.rfc8693(tokenUrl, subject: IdentityTokenSource, clientId: Option[String],
  scope: Option[String], audience: Option[String], httpClient: Llm4sHttpClient):
  () => Result[AccessToken]` - form POST of the RFC 8693 fields (optional ones omitted when
  unset); parses `access_token` and `expires_in`. A 400/401/403 from the token endpoint maps to
  `AuthenticationError`; 5xx and network failures map as elsewhere (retryable). A body without
  `access_token` or `expires_in` is an error.

Times follow the pass 6/7 rule: durations are `FiniteDuration`, instants `Instant`.

### 2. Core: configuration

- `NamedProviderConfig` gains `auth: Option[AuthConfig]` and `withAuth`; no compatibility
  overload (nothing is frozen before 1.0).
- `AuthConfig(identityToken: IdentitySource, extras: Map[String, String])`, where
  `IdentitySource` is `File(path)` or `Literal(token)`. Core parses only
  `auth.identityTokenFile` / `auth.identityToken` (exactly one required) and passes every other
  key of the block through as auth extras. `AuthConfig.toString` redacts a literal token.
- `ProviderConfigSpec` gains `authExtras: Seq[ProviderConfigKey]`. A non-empty list means the
  provider supports `auth`; required/default keys are validated as for `extras`.
- Validation (per section, in `ProviderSections.validated`):
  - `apiKey` and `auth` together is an error.
  - `auth` on a provider with no `authExtras` is an error naming the provider.
  - `auth` satisfies `requiresApiKey`.
  - With `auth`, the shared `llm4s.credentials.<id>.apiKey` fallback is not consulted.
- config-policy: `ownApiKey` treats a section with `auth` as having its own credential.

```hocon
databricks-main {
  provider = "openai-compatible"
  baseUrl  = "https://<ws>.cloud.databricks.com/serving-endpoints"
  model    = "<serving endpoint name>"
  auth {
    identityTokenFile = "/var/run/secrets/spiffe/token"
    tokenUrl = "https://<ws>.cloud.databricks.com/oidc/v1/token"
    clientId = ${?DATABRICKS_CLIENT_ID}
    scope    = "all-apis"
  }
}
```

### 3. Provider wiring

**`llm4s-openai-compatible`** - the generic `openai-compatible` provider only.

- `authExtras`: `tokenUrl` (required), `clientId`, `scope`, `audience` (optional, no defaults).
- `OpenAICompatibleClient.Settings.apiKey: Option[String]` becomes `credential: Credential`:
  `Credential.None`, `Credential.Static(key)`, `Credential.Dynamic(AccessTokenProvider)`.
- `requestHeaders` becomes `Result[Map[String, String]]`; a failed fetch or exchange is that
  call's error.
- `complete` and `streamComplete` share one wrapper: on a 401 with a `Dynamic` credential,
  `invalidate(token)`, re-fetch, resend once; a second 401 returns `AuthenticationError`. The
  status is known before any chunk reaches `onChunk`, so streaming retries are safe.
- `RetryPolicy` is unchanged; auth refresh sits beneath `ReliableClient`.
- `ProviderModelListers` (`/models`) uses the same credential.

**`llm4s-openai`** - provider `openai` only; `azure` and `requesty` reject `auth`.

- `authExtras`: `identityProviderId`, `serviceAccountId` (required), `clientId` (optional).
- `OpenAIClient` sets `.workloadIdentity(WorkloadIdentity.builder()...provider(adapter).build())`
  instead of `.apiKey`. The adapter wraps `IdentityTokenSource`, returns `SubjectTokenType.JWT`,
  and throws inside the SDK callback on failure; existing SDK error mapping returns it as a
  `Result`.

**`llm4s-anthropic`**

- `authExtras`: `federationRuleId`, `organizationId` (required), `serviceAccountId`,
  `workspaceId` (optional).
- `AnthropicClient` sets `.configurationProvider(InMemoryProfileConfigProvider(ProfileConfig(
  workload identity, IdentityTokenConfig(source = "file", path))))`.
- `auth.identityToken` (literal) is rejected for `anthropic` with an error explaining the SDK
  reads a file.

**Docs**: a "Workload identity (SPIFFE)" section in
`docs/getting-started/configuration.md` with Databricks, OpenAI and Anthropic examples; a
CHANGELOG entry.

### 4. Testing

**Shared fakes in `llm4s-provider-testkit`** (published, reusable by external provider authors):

- `FakeTokenExchangeServer` on `LocalProviderTestServer`: an RFC 8693 `/oidc/v1/token` handler
  recording form fields and issuing `t1`, `t2`, ... with configurable `expires_in`; a protected
  `/chat/completions` accepting only the current token (401 for revoked/expired, can force N
  401s); Anthropic `/v1/oauth/token` and `/v1/messages`. Pluggable subject-token validation, so
  layers 3 and 4 can install JWKS verification.
- `TestJwt`: mints ES256 JWTs from an in-memory key using the JDK only.

**Layer 1 - core unit tests**

- `CachingAccessTokenProviderSpec` (fake `Clock`): reuse before margin, refresh after; 20
  concurrent callers make one fetch; stale `invalidate` keeps the newer token; failures not
  cached.
- `IdentityTokenSourceSpec`: rotation picked up; missing/empty/whitespace file errors.
- `TokenExchangeSpec` (against the fake): exact form fields, optional ones omitted; 400/401/403
  map to `AuthenticationError`, 5xx to retryable `ServiceError`; malformed body or missing
  fields error; no secret in `toString` or logs.
- Config specs: `auth` parses; `apiKey` + `auth` rejected; unsupported provider rejected;
  required auth extras enforced; shared-credential fallback skipped with `auth`; only the loaded
  section validated; config-policy `ownApiKey` passes with `auth`.

**Layer 2 - provider module specs** (fakes only, no network)

- `openai-compatible` (`OpenAICompatibleWorkloadIdentitySpec`): config to client; `complete` and
  `streamComplete` send `Bearer t1`; after expiry `t2` is exchanged and sent; a forced 401 gives
  a refresh and one successful retry; two 401s fail with `AuthenticationError` after exactly two
  attempts; concurrent calls make one exchange; the model lister carries the token; a rotated
  SVID file changes `subject_token` on the next exchange.
- `openai`: the descriptor wires `WorkloadIdentity` (ids, and a `SubjectTokenProvider` reading
  the file); `azure` and `requesty` reject `auth`.
- `anthropic`: full flow against the fake via `baseUrl` - the SDK posts the `jwt-bearer` grant
  with the file's SVID, then `/v1/messages` with the access token; refresh on 401; literal
  `identityToken` rejected.
- Each `Llm4s<Name>ModuleSpec` gains an `auth` round trip through `ProviderModuleChecks`.

**Layer 3 - real SPIRE (new `@Spiffe` IT tier, Docker Compose)**

- Tier: `@Spiffe` tag in `org.llm4s.it.tags` (enforced by `itTierCheck`), `sbt testSpiffe`
  alias, a `ci.yml` job running `docker compose -f modules/it/spiffe/compose.yml up -d --wait`
  then `testSpiffe` with `LLM4S_IT_STRICT=true`. Suites gate on `Tier.require` (token file
  present), so they skip on a laptop without the stack.
- Stack (`modules/it/spiffe/`, pinned image versions):
  - `spire-server`: trust domain `llm4s.test`, `jwt_issuer = "https://spire.llm4s.test"`, a short
    `default_jwt_svid_ttl` (about 1 minute, or SPIRE's minimum) to force rotation in-run.
  - `spire-agent`: `join_token` node attestation, unix workload attestor.
  - `spiffe-helper`: `pid: service:spire-agent` so the attestor sees it, registered by uid,
    writing one SVID file per audience (`databricks`, `openai`, `anthropic`) to a bind-mounted
    host directory `target/spiffe/`.
  - An init step creating the join token and registration entry and exporting the JWKS to
    `target/spiffe/jwks.json`.
- `SpiffeDatabricksSpec`: the real `openai-compatible` client, configured through HOCON with
  `auth.identityTokenFile` pointing at the helper's file, against the in-JVM fake Databricks
  whose exchange verifies the SVID: signature against SPIRE's JWKS, `iss`, `aud` contains the
  audience spiffe-helper requested (the fake's expected value, not the RFC 8693 `audience` field), `sub` under `spiffe://llm4s.test/`, `exp`. Asserts: completion and stream
  succeed; after the TTL the file has rotated and the client re-exchanges with the new SVID
  (different `exp`/`jti`) and succeeds; a tampered or wrong-audience SVID is rejected at the
  exchange and surfaces as `AuthenticationError`.
- JWT verification uses `nimbus-jose-jwt`, test-only in `it`.

**Layer 4 - vendor SDKs with real SVIDs (same tier)**

- `SpiffeAnthropicSpec`: `llm4s-anthropic` with `baseUrl` at the fake; `/v1/oauth/token`
  validates the SVID as above; `/v1/messages` served; SDK refresh after rotation and on a forced
  401.
- `SpiffeOpenAISpec`: the SDK's exchange URL is hard-coded, so a package-private hook on
  `OpenAIClient` sets the builder's `proxy`, `trustManager` and `hostnameVerifier`, pointing at an
  in-JVM CONNECT proxy that terminates TLS with a checked-in test keystore for
  `auth.openai.com` and routes to the fake.
  - **Risk**: this works only if the SDK's WIF HTTP client is the builder-configured OkHttp
    client. If not, the spec drives the SDK's `WorkloadIdentityAuth` directly with an injected
    `HttpClient` and a real SVID, and the gap is recorded rather than faked.

**Coverage and CI**: no new modules, so codecov flags and floors are unchanged (floors only
ratchet up). The new CI job must be added to required checks by an admin.

## Error handling summary

| Failure | Result |
|---|---|
| SVID file missing/empty/unreadable | `AuthenticationError` for that call; next call re-reads |
| Token endpoint 400/401/403 | `AuthenticationError` |
| Token endpoint 5xx / network | retryable `ServiceError` / `NetworkError` (via `ReliableClient`) |
| Malformed exchange response | error; not cached |
| API 401 with dynamic credential | invalidate, refresh, retry once; second 401 is `AuthenticationError` |
| `apiKey` and `auth` both set; `auth` on unsupported provider; missing required auth extra | `ConfigurationError` on loading that section |

## Delivery

Two PRs, each with `Signed-off-by`:

1. Core + `openai-compatible` + `openai` + `anthropic` wiring, layers 1 and 2, docs, CHANGELOG.
2. `@Spiffe` IT tier: compose stack, layers 3 and 4, CI job.
