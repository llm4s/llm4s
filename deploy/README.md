# Staged deployments: dev, staging, prod

A template for rolling an llm4s service out through `dev` -> `staging` -> `prod` with health checks at each
stage and a rollback when a rollout fails. It is a starting point to copy, not a framework (issue
[#846](https://github.com/llm4s/llm4s/issues/846)).

| Piece | Where |
|---|---|
| A small service: `GET /health` and `GET /llm-check`, and an example agent service beside them | [`modules/deploy-service`](../modules/deploy-service) |
| Its container image, built by the sbt Docker plugin | `sbt deployService/Docker/publishLocal` |
| Kubernetes manifests: a base, a `dev`, `staging` and `prod` overlay, and a three-replica `postgres` overlay | [`deploy/base`](base), [`deploy/overlays`](overlays) |
| The rollout logic: render, apply, wait, roll back, smoke-check | [`deploy/scripts/deploy.sh`](scripts/deploy.sh) |
| The pipeline | [`.github/workflows/deploy-staged.yml`](../.github/workflows/deploy-staged.yml) |

To use it in your own project, copy those five things, replace `modules/deploy-service` with your service,
and keep the contract the manifests and the script rely on: an image that serves `GET /health` on
port 8080.

## The service

```bash
sbt "deployService/run"                      # listens on 0.0.0.0:8080
curl http://localhost:8080/health            # {"status":"up"}
curl -i http://localhost:8080/llm-check      # 503 until a provider is configured
```

| Endpoint | Meaning |
|---|---|
| `GET /health` | The process is up. Always `200`. The Kubernetes liveness probe calls it. |
| `GET /llm-check` | Is an LLM provider configured, and can llm4s build a client for it? `200` with `{"status":"ready","provider":"..."}`, or `503` with `unconfigured` or `degraded`. |

**`/llm-check` is a configuration check, not a connectivity check.** It loads the default provider and builds a
client, and never calls the provider: it costs nothing and needs no network. A deployment that must prove the
provider answers needs a real call of its own. It reports only the provider id and the error *type* (the
endpoint is unauthenticated, and a message can name a key or a URL); the detail is in the log. It is
deliberately not a Kubernetes probe, so a missing key does not take the pods out of rotation.

The port is `llm4s.deploy-service.port`, bound to the `PORT` environment variable (default `8080`). An
invalid port stops the service with exit code 1, so a bad value fails the rollout instead of starting a
service that listens somewhere nobody probes.

### Configure a provider

Providers are named sections of `application.conf`, and an environment variable binds each vendor's key
(`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, ... see the configuration guide). In a container the simplest way is
system properties in `JAVA_OPTS` for the section and a Secret for the key:

```yaml
# a patch for the Deployment's container
env:
  - name: JAVA_OPTS
    value: "-Dllm4s.providers.provider=openai-main -Dllm4s.providers.openai-main.provider=openai -Dllm4s.providers.openai-main.model=gpt-4o-mini"
  - name: OPENAI_API_KEY
    valueFrom:
      secretKeyRef:
        name: llm4s-llm-credentials
        key: OPENAI_API_KEY
```

The image carries the OpenAI, Anthropic, Gemini, Ollama and OpenAI-compatible providers; add another by adding
its module as a dependency of `deployService` in `build.sbt`.

## The example agent service

Beside `/health` and `/llm-check` the service is a small **example** of an llm4s agent service (design record
[4.60](../docs/design/typed-agent-runtime/4.60-stage-5-slice-p1-example-service-and-a-three-instance-deployment.md)):
runs are submitted to the store's durable run queue, an `AgentWorker` in the same process takes and runs them, and
any instance sharing the store can follow, cancel or read them. **It is an example to copy, not a product**: the HTTP
server that exposes llm4s threads and runs is a separate project, so these endpoints are deliberately few and follow
no API standard, and the `X-Tenant-Id` header that scopes each call is not authentication.

| Endpoint | Meaning |
|---|---|
| `POST /threads/{threadId}/runs` | Queue a run: `{"query": "..."}`, `{"answers": {"<interrupt id>": ...}}` or `{"recover": true}`, with optional `"policy"` (`reject`, the default, `enqueue` or `interrupt`) and `"submissionKey"` (a retry with the same key gets the same ticket). A W3C `traceparent` header is continued by every attempt of the run. `202` with `entryId`, `position` and `existing`; `409` for a busy thread under `reject`. |
| `GET /runs/{entryId}` | The queue entry: `open`, or closed `done`, `failed`, `cancelled` or `interrupted` with its outcome. |
| `GET /runs/{entryId}/events` | The run's events as Server-Sent Events, each with its thread sequence number as `id`, from after `Last-Event-ID` (or `?after=N`); live while the run goes on, on whichever instance runs it; an `end` event with the closed entry ends the stream. |
| `POST /runs/{entryId}/cancel` | Cancel the run wherever it runs: a queued one closes at once, a running one is stopped by the worker holding it. |
| `GET /threads/{threadId}` | The thread's latest status, run, outcome and messages. |
| `GET /threads?status=&limit=&after=` | A page of root threads, newest first; pass the answer's `next` as `after`. |
| `GET /ready` | Readiness: the worker's own health (running, its loop alive, the store answering). `503` while it drains. The Kubernetes readiness probe calls it. |
| `GET /metrics` | The runtime's run, claim, commit and queue metrics in Prometheus text format (`LLM4S_SERVICE_METRICS=false` turns it off). |

```bash
sbt "deployService/run"
curl -s -X POST localhost:8080/threads/t1/runs -d '{"query": "hello"}'   # {"entryId":"1",...}
curl -N localhost:8080/runs/1/events                                     # run-event ..., then end
curl -s localhost:8080/threads/t1                                        # ... "Echo: hello" ...
```

**The agent** is a demo by default: a built-in scripted model that needs no provider, network or key. Each turn calls
a `pause` tool that takes `LLM4S_SERVICE_TOOL_DELAY` (default `2 seconds`, long enough to cancel, interrupt or kill a
run part-way), then answers `Echo: <query>`. `LLM4S_SERVICE_MODEL=provider` uses the application's default provider
section instead (see [Configure a provider](#configure-a-provider)).

**Everything is configured from `application.conf` or the environment**, so the service runs as a plain JVM
process as well as in the image:

| Variable | Meaning | Default |
|---|---|---|
| `PORT` | The port | `8080` |
| `LLM4S_CHECKPOINTER` | The store: `memory` (this process only), `sqlite` or `postgres` | `memory` |
| `LLM4S_CHECKPOINTER_POSTGRES_URL` / `_USER` / `_PASSWORD` | The Postgres store's database, role and password | - |
| `LLM4S_RUNTIME_LABEL`, `LLM4S_WORKER_LABEL` | Name this instance in claims, leases and logs (the manifests set the pod name) | random |
| `LLM4S_SERVICE_MODEL` | `demo` or `provider` | `demo` |
| `LLM4S_SERVICE_TOOL_DELAY` | How long the demo model's tool call takes | `2 seconds` |
| `LLM4S_SERVICE_METRICS` | Serve `GET /metrics` | `true` |

Anything else is a system property: the Postgres schema and pool (`-Dllm4s.agent.checkpointer.postgres.schema=...`,
`.maxPoolSize`), the claim, lease and worker settings (`llm4s.agent.runtime`, `llm4s.agent.queue`,
`llm4s.agent.worker`, in `llm4s-agent`'s `reference.conf`). Several instances on one Postgres share threads:

```bash
sbt "export deployService/Runtime/fullClasspath" | tail -1 > /tmp/cp      # the classpath, once
PORT=8081 LLM4S_CHECKPOINTER=postgres LLM4S_CHECKPOINTER_POSTGRES_URL=jdbc:postgresql://localhost:5432/postgres \
  LLM4S_CHECKPOINTER_POSTGRES_USER=postgres LLM4S_CHECKPOINTER_POSTGRES_PASSWORD=postgres LLM4S_WORKER_LABEL=a \
  java -cp "$(cat /tmp/cp)" org.llm4s.deploy.DeployServiceMain
# ...and again with PORT=8082 LLM4S_WORKER_LABEL=b: a run submitted to one is followed through the other
```

**On SIGTERM** the service drains: its worker stops taking runs, lets running ones finish within
`llm4s.agent.worker.drain.grace` (30 seconds), cancels the rest - whose queue entries another instance takes at once -
then the server stops and the store closes. A run on an instance killed outright (`kill -9`) is recovered by another
once its claim expires (`llm4s.agent.runtime.claims.ttl`, 30 seconds).

With the default `memory` store each instance keeps its own threads, so the `dev`, `staging` and `prod` overlays'
runs are per pod; use the `postgres` overlay to share them.

## The image

```bash
sbt deployService/Docker/publishLocal     # llm4s/deploy-service:<version>
```

It is built like the workspace-runner image, with the sbt Docker plugin: no Dockerfile builds sbt inside an
image and nothing copies the repository into a build context. The base is a JRE (`eclipse-temurin:21-jre`)
and the service runs as the numeric non-root user `1001`. It is built for the machine that builds it (amd64 on
a GitHub-hosted runner); for a cluster of the other kind, build on that architecture or pass `--platform`
through `dockerBuildOptions` in `project/DeployServiceDocker.scala`.

## The manifests

`deploy/base` is a Deployment, a Service and a Namespace; each overlay sets only its namespace
(`llm4s-dev`, `llm4s-staging`, `llm4s-prod`) and replica count (1, 1 and 2).

- **Hardened pod.** `runAsNonRoot`, a `RuntimeDefault` seccomp profile, no privilege escalation, all
  capabilities dropped, and a read-only root filesystem with a writable `/tmp`.
- **Safe rollouts.** `maxUnavailable: 0`, `maxSurge: 1`: a new pod must be ready before an old one goes.
- **No image tag in the manifests.** The pipeline sets the registry, the name and the tag when it deploys,
  so every environment runs exactly the image that was built and tested.

- **Readiness is the worker's health** (`GET /ready`), and the pod has 60 seconds after SIGTERM to drain. The pod's
  name labels its claims and leases (`LLM4S_RUNTIME_LABEL`, `LLM4S_WORKER_LABEL`).

Render one without a cluster: `kubectl kustomize deploy/overlays/prod`.

### The Postgres overlay: three instances on one database

[`deploy/overlays/postgres`](overlays/postgres) runs three replicas of the example agent service sharing threads
through one PostgreSQL database: any pod accepts a run, whichever worker takes it runs it, any pod streams or cancels
it, and a run whose pod dies is recovered by another. It is applied by hand (the pipeline deploys only `dev`,
`staging` and `prod`) and the database is yours - a managed Postgres, or one you run:

1. Set the database URL (and, in `JAVA_OPTS`, the schema and pool size) in
   [`database.env`](overlays/postgres/database.env), which becomes the `llm4s-agent-store` ConfigMap. Three pods with a
   pool of 10 each, plus one listening connection per pod, need about 33 of the server's connections.
2. Create the credentials Secret; the overlay never holds them, and a pod without the Secret does not start:

   ```bash
   kubectl create namespace llm4s-postgres
   kubectl -n llm4s-postgres create secret generic llm4s-postgres-credentials \
     --from-literal=username=llm4s --from-literal=password='<the role's password>'
   ```

3. Set the image and apply: `kustomize edit set image llm4s/deploy-service=<registry>/<name>:<tag>` in a copy of the
   overlay, then `kubectl apply -k`.

The store creates its schema on first start (several pods starting together migrate it once). A PodDisruptionBudget
keeps a node drain to one pod at a time.

## The pipeline

[`deploy-staged.yml`](../.github/workflows/deploy-staged.yml) is **opt-in**: it runs only when started from the
Actions tab (`workflow_dispatch`) or called from another workflow (`workflow_call`), never on a push or a pull
request.

1. **Build and smoke-test.** Builds the image, checks it does not run as root, starts it, and calls
   `/health` (must be `200`) and `/llm-check` (reported; gating only with `require_llm`). Nothing is pushed.
2. **With `deploy`:** push the image tagged with the commit SHA, then deploy to each environment up to and
   including `target`, in order: `dev`, then `staging`, then `prod`. Each stage applies the manifests, waits for
   the rollout, and calls `/health` through the Service.

| Input | Meaning | Default |
|---|---|---|
| `target` | Deploy up to and including `dev`, `staging` or `prod` | `dev` |
| `deploy` | Push and deploy; off means build and smoke-test only | off |
| `registry` | Container registry | `ghcr.io` |
| `image_name` | Repository without the registry | `<owner>/deploy-service` |
| `namespace_prefix` | Namespaces are `<prefix>-dev` and so on | `llm4s` |
| `replicas` | Replica count for every environment | each overlay's own |
| `require_llm` | Fail the smoke test unless `/llm-check` is `200` | off |

**Set up once:**

1. Create the GitHub Environments `dev`, `staging` and `prod`.
2. In each, add the cluster's kubeconfig as the secret `KUBE_CONFIG`.
3. On `staging` and `prod`, add **required reviewers**. The job for that environment then waits for an
   approval before it starts, which is the promotion gate. (The pipeline does not need an approval step of
   its own.)
4. The login step uses `GITHUB_TOKEN`, which GHCR accepts. For another registry, replace that one step.

### Rollback

- **Automatic.** If a rollout does not finish within the timeout (180 s, `ROLLOUT_TIMEOUT`), the script runs
  `kubectl rollout undo`, waits for it, and fails the job. A first deployment has nothing to roll back to, and
  the script says so.
- **By hand.** Every deployed image has an immutable tag, the commit SHA, that stays in the registry:
  `kubectl -n llm4s-prod set image deployment/llm4s-deploy-service service=<registry>/<name>:<earlier-sha>`,
  or `kubectl -n llm4s-prod rollout undo deployment/llm4s-deploy-service`.

The script refuses the `latest` tag and an image with no tag, because a rollback has to name a tag that still
exists.

## Checking the template itself

```bash
deploy/scripts/deploy.sh check        # renders every overlay and verifies image, namespace, replicas, hardening, store
```

CI runs it on every pull request (it needs `kubectl`, which the GitHub-hosted runners have).
`modules/deploy-service` has unit tests, and the real routes are tested over HTTP on an ephemeral port:
`sbt deployService/test`. `DeployServiceSmokeSpec` in `modules/it` (`@Docker`) starts the service as a JVM process
on Postgres, runs and streams a run, and stops it with SIGTERM.
