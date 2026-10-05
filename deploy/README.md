# Staged deployments: dev, staging, prod

A template for rolling an llm4s service out through `dev` -> `staging` -> `prod` with health checks at each
stage and a rollback when a rollout fails. It is a starting point to copy, not a framework (issue
[#846](https://github.com/llm4s/llm4s/issues/846)).

| Piece | Where |
|---|---|
| A small service: `GET /health` and `GET /llm-check` | [`modules/deploy-service`](../modules/deploy-service) |
| Its container image, built by the sbt Docker plugin | `sbt deployService/Docker/publishLocal` |
| Kubernetes manifests: a base and a `dev`, `staging` and `prod` overlay | [`deploy/base`](base), [`deploy/overlays`](overlays) |
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
| `GET /health` | The process is up. Always `200`. The Kubernetes liveness and readiness probes call it. |
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

Render one without a cluster: `kubectl kustomize deploy/overlays/prod`.

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
deploy/scripts/deploy.sh check        # renders every overlay and verifies image, namespace, replicas, hardening
```

CI runs it on every pull request (it needs `kubectl`, which the GitHub-hosted runners have).
`modules/deploy-service` has unit tests, and the real routes are tested over HTTP on an ephemeral port:
`sbt deployService/test`.
