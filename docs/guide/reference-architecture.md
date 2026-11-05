---
layout: page
title: Reference Architecture
description: "Deploy LLM4S behind an AI gateway: secret injection, egress policy, failover, quotas and spend, with tested Kubernetes manifests."
search_title: "LLM4S enterprise reference architecture"
parent: User Guide
nav_order: 43
---

# Enterprise Reference Architecture
{: .no_toc }

Where an LLM4S application runs, how it reaches the model provider, where its keys come from, and how egress,
quotas, spend and failures are controlled: one deployment picture, with the configuration behind each part.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## The picture

This page documents deploying today's clients and agents. Every configuration block on it is loaded by a test, and
the Kubernetes manifests are in [`deploy/overlays/enterprise`](https://github.com/llm4s/llm4s/tree/main/deploy/overlays/enterprise),
rendered and schema-validated by CI.

```text
 Kubernetes namespace llm4s-enterprise
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                                                                                        │
 │  ┌──────────────────────┐  gateway key   ┌──────────────────────┐  provider keys       │
 │  │  App (llm4s)         │  + X-Tenant-Id │  AI gateway          │  (env, from the      │
 │  │  openai-compatible   │ ─────────────▶ │  LiteLLM proxy       │   secret store)      │
 │  │  section "gateway"   │  :4000/v1      │  quotas, audit,      │ ──────────────┐      │
 │  │  ReliableClient      │                │  routing, spend      │               │      │
 │  └──────────┬───────────┘                └──────────────────────┘               │      │
 │             │ apiKeyFile                         ▲                              │      │
 │   ┌─────────┴─────────┐                          │ ExternalSecret               │      │
 │   │ Secret volume     │◀── ExternalSecret ───────┴── Secret store               │      │
 │   │ (rotates in place)│    (Vault, AWS SM, Key Vault, GCP SM)                   │      │
 │   └───────────────────┘                                                         │      │
 │  NetworkPolicy: the app may reach only the gateway and DNS; the gateway only    │      │
 │  DNS and HTTPS to public addresses.                                             │      │
 └─────────────────────────────────────────────────────────────────────────────────┼──────┘
                                                                                   ▼
              OpenAI · Anthropic · Azure OpenAI (private endpoint) · Bedrock (VPC endpoint) · ...
   Traces: self-hosted Langfuse or an OTLP collector.   Metrics: Prometheus or Micrometer.
```

| Concern | Where it is enforced | LLM4S's part |
|---|---|---|
| Provider keys | The gateway | None: the app never holds a provider key |
| The app's own key | Secret store, External Secrets Operator | `apiKeyFile`, re-read when it changes |
| Egress | NetworkPolicy, an egress proxy | `llm4s.http.proxy` when there is no gateway |
| Failures | The gateway, then the app | `ReliableClient`; a second section for failover |
| Quotas and spend | The gateway, per tenant key | `RateLimitConfig`, `maxTokens`, `CostTracker`, metrics |
| Audit and traces | The gateway's log, your tracing backend | Langfuse or OpenTelemetry tracing |

## The gateway

Many organisations require every model call to go through a central AI gateway (LiteLLM proxy, Envoy AI Gateway,
Kong AI Gateway, Azure API Management, AWS API Gateway in front of Bedrock) that holds the provider keys and applies
authentication, quotas, audit and spend controls. Such a gateway speaks the OpenAI chat-completions protocol, so the
app uses the generic `openai-compatible` provider: `baseUrl` is the gateway, the key is the app's own gateway key, and
`headers` carry what the gateway needs to attribute the call.

This is the app's whole `application.conf` in the enterprise overlay, mounted from a ConfigMap
([`application.conf`](https://github.com/llm4s/llm4s/blob/main/deploy/overlays/enterprise/application.conf)):

```hocon
llm4s.providers {
  provider = "gateway"                     # the section the service loads

  gateway {
    provider         = "openai-compatible"
    baseUrl          = "http://litellm:4000/v1"                    # the gateway's Service, not the vendor
    model            = "gpt-4o-mini"                               # a model_name in the gateway's config
    registryProvider = "openai"                                    # the context window of openai/gpt-4o-mini
    apiKeyFile       = "/var/run/secrets/llm4s/gateway-api-key"   # this app's gateway key, re-read when rotated
    headers {
      "X-Tenant-Id"   = "acme-retail"     # sent on every request, for the gateway's audit log and routing
      "X-Cost-Centre" = "cc-4711"
    }
    timeouts {
      request = 60s
      stream  = 5m
    }
  }
}
```

What the gateway receives, on every chat request:

- `POST <baseUrl>/chat/completions` (here `http://litellm:4000/v1/chat/completions`), streamed or not.
- `Authorization: Bearer <the key file's content>`. The file is checked before every request, so a rotated key is
  sent from the next request on.
- Every entry of `headers`, unchanged. What the gateway does with them (an audit field, a routing rule, a budget) is
  the gateway's own configuration; the header names above are examples, not names LiteLLM gives a meaning to.
- `model` as written. The gateway maps it to a provider, a region and a deployment, so switching any of those is a
  change to the gateway, not to the app.

`registryProvider` tells LLM4S which [model registry](https://github.com/llm4s/llm4s/blob/main/docs/MODEL_METADATA.md) entry gives the context window, since a
gateway's host says nothing about the model behind it
([details](providers#where-the-context-window-comes-from)). Without it, the window is 8192 tokens.

The overlay's gateway is a LiteLLM proxy whose `config.yaml` maps the model name and reads its keys from the
environment ([`litellm-config.yaml`](https://github.com/llm4s/llm4s/blob/main/deploy/overlays/enterprise/litellm-config.yaml)).
To keep the example self-contained it authenticates the app with its master key. In production, give each app or
tenant its own LiteLLM virtual key, with its own budget and rate limits; virtual keys need LiteLLM's database. The
app's side does not change: the virtual key goes in the same file.

### Without a gateway: private endpoints

Where no gateway is mandated, the app can call a cloud provider over a private network path instead. Azure OpenAI
behind a private endpoint, and Bedrock behind a VPC interface endpoint, are reached at their usual host names, which
resolve to private addresses inside the network (Azure's `privatelink.openai.azure.com` zone, the endpoint's private
DNS on AWS), so the sections are the usual ones:

```hocon
llm4s.providers {
  azure-private {
    provider       = "azure"
    endpoint       = "https://contoso-llm.openai.azure.com"   # resolves to the private endpoint inside the VNet
    model          = "gpt-4o"                                 # the deployment name
    authentication = "entra-id"                               # managed identity: no key at all (llm4s-azure-identity)
  }

  bedrock-private {
    provider = "bedrock"
    region   = "eu-central-1"
    model    = "anthropic.claude-3-5-sonnet-20240620-v1:0"
    # baseUrl = "https://vpce-0123456789abcdef0-abcdefgh.bedrock-runtime.eu-central-1.vpce.amazonaws.com"
    #   only when the VPC endpoint's private DNS is off
  }
}
```

Bedrock takes its credentials from the AWS default chain (an IRSA role in Kubernetes) and Azure from
[Microsoft Entra ID](providers#azure-openai-with-microsoft-entra-id), so neither section holds a key. In this
picture the gateway's controls (per-tenant quotas, the central audit log) are yours to provide in the app; the rest
of this page still applies.

## Secrets

The app holds one key, its gateway key, and reads it from a file that `apiKeyFile` names. The
[External Secrets Operator](https://external-secrets.io) syncs it from the secret store into a Kubernetes Secret,
which is mounted as a volume; when the store's value changes the operator updates the Secret, the kubelet rewrites the
file, and LLM4S sends the new key from the next request, with no restart
([#2313](https://github.com/llm4s/llm4s/issues/2313)). The overlay's
[`externalsecret.yaml`](https://github.com/llm4s/llm4s/blob/main/deploy/overlays/enterprise/externalsecret.yaml) has
two: the gateway's provider keys, and the app's gateway key.

- **Mount the Secret as a volume, never with `subPath`** (the kubelet never updates a `subPath` file) and never as an
  environment variable, which is read once at start.
- **A non-root pod reads the file through its group.** A Secret volume's files belong to root, so the overlay gives
  the pod an `fsGroup` and the file mode `0440`. LLM4S logs once that the key file is readable by its group, as it
  does for any such file; the group is the pod's own.
- **Vault Agent Injector and the Secrets Store CSI driver** work the same way: each writes a file that `apiKeyFile`
  names. Their manifests, and an `ApiKeyProvider` for a secret store your code calls, are in
  [Secrets and key rotation](../PRODUCTION_DEPLOYMENT#secrets-and-key-rotation).
- **Embeddings, the reranker and every chat provider** take `apiKeyFile` too
  ([Rotating keys](../getting-started/configuration#rotating-keys-apikeyfile-and-apikeyprovider)), so RAG needs no
  environment-variable key either.

The gateway in the overlay reads its provider keys as environment variables, so a rotated provider key reaches it on
its next restart; that is LiteLLM's behaviour, not LLM4S's.

## Egress

The overlay's [`networkpolicy.yaml`](https://github.com/llm4s/llm4s/blob/main/deploy/overlays/enterprise/networkpolicy.yaml)
lets the app's pods reach only the gateway (TCP 4000) and cluster DNS, and lets only the gateway leave the cluster,
on TCP 443 to public addresses. Add a rule for each other destination the app has: a self-hosted Langfuse, an OTLP
collector, a database.

A NetworkPolicy matches addresses, never host names. To allow only the providers' host names, route the traffic
through an egress proxy that checks them, or use a CNI with FQDN policies. Without a gateway, LLM4S sends every
provider client through one proxy, and trusts a corporate CA for TLS inspection, with the `llm4s.http` block
([Corporate networks](../getting-started/configuration#corporate-networks-a-proxy-and-a-custom-ca),
[#2273](https://github.com/llm4s/llm4s/issues/2273)); a section can set its own route, for example no proxy for an
internal gateway. The host each provider needs is listed in
[Network destinations for an egress allow-list](data-protection#network-destinations-for-an-egress-allow-list).

## Resilience

Three layers, from the outside in:

1. **The gateway** can retry and fall back between deployments and regions of the same model, in its own config.
2. **`ReliableClient`** in the app retries what is transient (429, 408, 5xx and timeouts, honouring `Retry-After`),
   opens a circuit breaker when the gateway keeps failing, bounds each call with a deadline and can rate-limit
   locally ([Reliable LLM Calling](https://github.com/llm4s/llm4s/blob/main/docs/reliability-guide.md)).
3. **A second section**, for a second gateway or provider, asked only when the first still fails.

The section's `timeouts` bound how long one HTTP attempt may wait for the response to begin
([Timeouts](../getting-started/configuration#timeouts)); `ReliableClient`'s `deadline` bounds the whole call,
retries included. Build each client once and share it, because the circuit breaker and the rate limiter count across
calls:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.metrics.CostTracker
import org.llm4s.model.ModelRegistryService
import org.llm4s.reliability.{ RateLimitConfig, ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.types.Result

import scala.concurrent.duration.*

val costs = CostTracker.create()

val reliability = ReliabilityConfig(
  retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 500.millis),
  rateLimit = RateLimitConfig(enabled = true, requestsPerMinute = 300, burstCapacity = 30),
  deadline = Some(2.minutes)
)

def reliableClient(section: String): Result[LLMClient] =
  for
    config   <- Llm4sConfig.provider(section)
    registry <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client <- LLMConnect.getClient(config, costs)
  yield new ReliableClient(client, section, reliability, Some(costs))
```

For a second region or provider, add a section beside `gateway` (here a gateway in another region; it could as well
be a direct provider section):

```hocon
llm4s.providers.gateway-dr {
  provider         = "openai-compatible"
  baseUrl          = "https://llm-gateway.eu-west-1.example.internal/v1"
  model            = "gpt-4o-mini"
  registryProvider = "openai"
  apiKeyFile       = "/var/run/secrets/llm4s/gateway-dr-api-key"
  headers {
    "X-Tenant-Id"   = "acme-retail"
    "X-Cost-Centre" = "cc-4711"
  }
}
```

and ask it only when the first, after its own retries, still fails. With `reliableClient("gateway")` and
`reliableClient("gateway-dr")` built once:

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, Conversation }
import org.llm4s.types.Result

def completeWithFailover(primary: LLMClient, secondary: LLMClient, conversation: Conversation): Result[Completion] =
  primary.complete(conversation) match
    case Left(_) => secondary.complete(conversation)
    case served  => served
```

The cookbook's [fallback recipe](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/FallbackRecipe.scala)
runs this pattern against a simulated outage.

## Quotas and cost

- **Per tenant, at the gateway.** The gateway is the one place that sees every app and every replica, so it owns
  per-tenant budgets and rate limits (in LiteLLM, per virtual key). A tenant that exceeds one gets `429`, which
  `ReliableClient` treats as a rate limit.
- **Per client, in the app.** `RateLimitConfig` is a token bucket inside one `ReliableClient`: it smooths one pod's
  bursts, and the limit for the deployment is `requestsPerMinute` times the replica count.
- **Per call.** `CompletionOptions().withMaxTokens(n)` bounds each answer, and with it the cost of one call; the
  [context window middleware](context-window-pruning) bounds what each agent turn sends.
- **Spend.** A client built with a `MetricsCollector` records each call's requests, tokens and estimated cost. The
  cost is estimated from the model registry's price for the section's `model`, so a gateway model name the registry
  does not know records tokens but no cost. `CostTracker` (`llm4s-observability`) keeps the totals in memory;
  `PrometheusMetrics` (`llm4s-observability-prometheus`) and `MicrometerMetrics` (`llm4s-observability-micrometer`)
  export them as `llm4s_cost_usd_total` and `llm4s_tokens_total` for alerting
  ([metrics](observability/micrometer)). `MetricsCollector.compose` sends them to more than one.

```scala
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.metrics.CostTracker

val costs = CostTracker.create() // passed to LLMConnect.getClient, as in the Resilience section

// At most 1,024 tokens per answer: the bound on what one call can cost.
val bounded = CompletionOptions().withMaxTokens(1024)

// What this process has spent, for a check of your own; alert across replicas on llm4s_cost_usd_total instead.
def overBudget(limitUsd: BigDecimal): Boolean = costs.totalCost > limitUsd
```

Provider-neutral cost controls (prompt caching, effort, service tier) are tracked in
[#2115](https://github.com/llm4s/llm4s/issues/2115).

## Observability and audit

- **Traces** go to a backend you run inside the network: a self-hosted Langfuse (`TRACING_MODE=langfuse`, `LANGFUSE_URL`
  set to your instance) or an OpenTelemetry collector (`TRACING_MODE=opentelemetry`,
  `OTEL_EXPORTER_OTLP_ENDPOINT`). See [Tracing Configuration](../getting-started/configuration#tracing-configuration).
- **What is captured** differs by backend: Langfuse records queries, completions and tool input and output;
  OpenTelemetry records content only with `captureContent` on; console tracing records none by default. The table,
  and what is redacted, is in [Data Protection](data-protection#logging-and-tracing-defaults).
- **The audit log of model calls** is the gateway's: it sees the tenant headers, the key and the model for every
  call, from every app.

## Check the configuration in CI

`llm4s-config-policy` checks a configuration before it ships. Its `prod` preset deliberately refuses the generic
`openai-compatible` provider, because such a section can point anywhere, so a gateway deployment states its own
policy: only the gateway's provider, model and URL, and a key written down for each section (`ownApiKey`, which
`apiKeyFile` satisfies; [details](../PRODUCTION_DEPLOYMENT#check-for-inherited-keys-with-the-config-policy)).

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.configpolicy.{ CatalogEnvironment, ConfigPolicy, ConfigPolicyEngine, PolicyViolation }
import org.llm4s.types.Result

val gatewayOnly: ConfigPolicy =
  ConfigPolicy()
    .withAllowedProviders("openai-compatible")
    .withAllowedModelPatterns("openai-compatible/gpt-4o-mini")
    .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "openai-compatible", "http://litellm:4000/v1")
    .withMaxContextWindow(CatalogEnvironment.Prod, "openai-compatible", 128000)
    .withOwnApiKeyRequired(CatalogEnvironment.Prod)

// Run at startup, or in a test of the deployment's configuration: empty when the section complies.
val violations: Result[List[PolicyViolation]] =
  Llm4sConfig.defaultProvider().map(config => ConfigPolicyEngine.check(config, gatewayOnly, CatalogEnvironment.Prod))
```

## Deploy the overlay

[`deploy/overlays/enterprise`](https://github.com/llm4s/llm4s/tree/main/deploy/overlays/enterprise) runs the
[example agent service](https://github.com/llm4s/llm4s/tree/main/deploy#the-example-agent-service) behind the
LiteLLM gateway. It is an example to copy, applied by hand:

1. Install the External Secrets Operator and create a `ClusterSecretStore` named `llm4s-secret-store` for your store.
2. Put three secrets in the store: `prod/llm4s/openai-api-key`, `prod/llm4s/litellm-master-key` and
   `prod/llm4s/gateway-api-key` (the key the app sends; the master key or a virtual key made from it).
3. Set the image in a copy of the overlay
   (`kustomize edit set image llm4s/deploy-service=<registry>/<name>:<tag>`), then `kubectl apply -k`.

`deploy/scripts/deploy.sh check` renders every overlay and checks what this page promises (the gateway URL, the key
file, no inlined Secret, no `subPath`); `deploy/scripts/deploy.sh validate` validates them against the Kubernetes and
External Secrets Operator schemas with `kubeconform`. CI runs both on every pull request.
