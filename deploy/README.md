# Staged deployment template (issue #846)

This directory provides a **reusable CI/CD template** for deploying the LLM4S Safe Deployment Service across dev → staging → prod.

## Contents

- **Dockerfile.safe-deployment** — Multi-stage build for the sample service (exposes `/health` and `/llm-check`).
- **deployment.yaml** / **service.yaml** — Kubernetes base manifests with liveness/readiness probes.
- **overlays/dev**, **overlays/staging**, **overlays/prod** — Kustomize overlays per environment (namespace, image tag, replicas).

## Local run

```bash
sbt "samples/runMain org.llm4s.samples.deploy.SafeDeploymentService"
curl http://localhost:8080/health
curl http://localhost:8080/llm-check
```

## Build image

```bash
docker build -f deploy/Dockerfile.safe-deployment -t llm4s/safe-deployment:latest .
```

## Deploy with Kustomize

```bash
kustomize build deploy/overlays/dev   | kubectl apply -f -
kustomize build deploy/overlays/staging | kubectl apply -f -
kustomize build deploy/overlays/prod  | kubectl apply -f -
```

## CI/CD (GitHub Actions)

The workflow `.github/workflows/deploy-staged.yml`:

1. **Build and push** — Builds the image and pushes to GHCR on push to main.
2. **Smoke test** — Runs the container and checks `/health` and `/llm-check`.
3. **Deploy dev/staging/prod** — Run when repo variable `ENABLE_KUBE_DEPLOY` is `true` and secret `KUBE_CONFIG` is set. Use GitHub **Environments** (dev, staging, prod) with required reviewers for staging → prod.

Complementary to **#841** (secret scanning) and **GSoC 2026 Project 14: CI/CD Templates for Safe Model Deployments**.
