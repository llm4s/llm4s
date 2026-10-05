#!/usr/bin/env bash
# Deploys the deploy-service image to one environment of a Kubernetes cluster, waits for the rollout, rolls
# back if it fails, and smoke-checks the result. The staged-deployment workflow
# (.github/workflows/deploy-staged.yml) calls it once per environment.
#
#   deploy/scripts/deploy.sh deploy <dev|staging|prod>
#   deploy/scripts/deploy.sh check        # render every overlay and verify the result; needs no cluster
#
# Environment:
#   IMAGE             registry/name:tag to deploy. Required. The tag must be immutable (the workflow uses the
#                     commit SHA); `latest` is refused, because a rollback must name a tag that still exists.
#   KUBE_CONFIG       the kubeconfig text for the target cluster. Required for `deploy`.
#   NAMESPACE_PREFIX  the namespace is <prefix>-<environment>. Default: llm4s.
#   REPLICAS          overrides the overlay's replica count. Optional.
#   ROLLOUT_TIMEOUT   how long to wait for the rollout. Default: 180s.
#   KUSTOMIZE         the command that renders a directory. Default: kubectl kustomize.
#
# Exit code 0 = deployed and healthy (or, for `check`, every overlay renders as expected).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOYMENT="llm4s-deploy-service"
BASE_IMAGE_NAME="llm4s/deploy-service"   # the placeholder image in deploy/base/deployment.yaml
KUSTOMIZE="${KUSTOMIZE:-kubectl kustomize}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-180s}"
NAMESPACE_PREFIX="${NAMESPACE_PREFIX:-llm4s}"
REPLICAS="${REPLICAS:-}"

die() { echo "error: $*" >&2; exit 1; }

# State the EXIT trap cleans up. Global, not local to `deploy`: the trap runs after the function has returned.
KUBECONFIG_FILE=""
MANIFESTS_FILE=""
PORT_FORWARD_PID=""

cleanup() {
  [ -z "$KUBECONFIG_FILE" ] || rm -f "$KUBECONFIG_FILE"
  [ -z "$MANIFESTS_FILE" ] || rm -f "$MANIFESTS_FILE"
  [ -z "$PORT_FORWARD_PID" ] || kill "$PORT_FORWARD_PID" 2>/dev/null || true
  rm -rf "$ROOT/deploy/rendered"
}

usage() { sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; exit 2; }

validate_environment() {
  case "$1" in
    dev | staging | prod) ;;
    *) die "environment must be dev, staging or prod, not '$1'" ;;
  esac
}

# The image must carry an explicit, immutable tag.
validate_image() {
  local image="$1" tag
  [ -n "$image" ] || die "IMAGE is not set"
  tag="${image##*:}"
  case "$image" in
    *:*) ;;
    *) die "IMAGE '$image' has no tag; deploy by an immutable tag such as the commit SHA" ;;
  esac
  case "$tag" in
    */*) die "IMAGE '$image' has no tag (the text after the last ':' is part of the path)" ;;
    latest) die "IMAGE '$image' uses the mutable tag 'latest'; deploy by an immutable tag such as the commit SHA" ;;
    "") die "IMAGE '$image' has an empty tag" ;;
  esac
  [[ "$tag" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || die "IMAGE tag '$tag' is not a valid image tag"
}

validate_namespace() {
  [[ "$1" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] && [ "${#1}" -le 63 ] ||
    die "namespace '$1' is not a valid Kubernetes namespace name (NAMESPACE_PREFIX='$NAMESPACE_PREFIX')"
}

validate_replicas() {
  [ -z "$1" ] || [[ "$1" =~ ^[1-9][0-9]*$ ]] || die "REPLICAS must be a positive integer, not '$1'"
}

# render <environment> <image> <namespace> <replicas>: prints the manifests for that environment, with the
# image, the namespace and (if given) the replica count laid over the overlay.
render() {
  local env="$1" image="$2" namespace="$3" replicas="$4"
  local dir="$ROOT/deploy/rendered/$env"
  mkdir -p "$dir"
  {
    echo "apiVersion: kustomize.config.k8s.io/v1beta1"
    echo "kind: Kustomization"
    echo "resources:"
    echo "  - ../../overlays/$env"
    echo "namespace: $namespace"
    echo "images:"
    echo "  - name: $BASE_IMAGE_NAME"
    echo "    newName: ${image%:*}"
    echo "    newTag: \"${image##*:}\""
    if [ -n "$replicas" ]; then
      echo "replicas:"
      echo "  - name: $DEPLOYMENT"
      echo "    count: $replicas"
    fi
  } >"$dir/kustomization.yaml"
  # shellcheck disable=SC2086  # KUSTOMIZE is a command with arguments
  $KUSTOMIZE "$dir"
  rm -rf "$ROOT/deploy/rendered"
}

check() {
  local image="example.invalid/llm4s/deploy-service:0000000000000000000000000000000000000000"
  local env expected out
  validate_image "$image"
  for env in dev staging prod; do
    expected=1
    [ "$env" = prod ] && expected=2
    out="$(render "$env" "$image" "${NAMESPACE_PREFIX}-$env" "")"
    grep -q "image: $image" <<<"$out" || die "$env: the image was not set to $image"
    grep -q "namespace: ${NAMESPACE_PREFIX}-$env" <<<"$out" || die "$env: the namespace is not ${NAMESPACE_PREFIX}-$env"
    grep -q "replicas: $expected" <<<"$out" || die "$env: expected $expected replica(s)"
    ! grep -q ":latest" <<<"$out" || die "$env: a ':latest' image reached the manifests"
    grep -q "runAsNonRoot: true" <<<"$out" || die "$env: the pod is not marked runAsNonRoot"
    out="$(render "$env" "$image" "acme-$env" 7)"
    grep -q "replicas: 7" <<<"$out" || die "$env: REPLICAS did not override the overlay"
    grep -q "namespace: acme-$env" <<<"$out" || die "$env: NAMESPACE_PREFIX did not override the overlay"
    echo "ok  $env"
  done
}

deploy() {
  local env="$1"
  validate_environment "$env"
  validate_image "${IMAGE:-}"
  local namespace="${NAMESPACE_PREFIX}-$env"
  validate_namespace "$namespace"
  validate_replicas "$REPLICAS"
  [ -n "${KUBE_CONFIG:-}" ] || die "KUBE_CONFIG is not set: add the cluster's kubeconfig as a secret of the '$env' environment"

  KUBECONFIG_FILE="$(mktemp)"
  MANIFESTS_FILE="$(mktemp)"
  trap cleanup EXIT
  chmod 600 "$KUBECONFIG_FILE"
  printf '%s' "$KUBE_CONFIG" >"$KUBECONFIG_FILE"
  export KUBECONFIG="$KUBECONFIG_FILE"

  render "$env" "$IMAGE" "$namespace" "$REPLICAS" >"$MANIFESTS_FILE"
  echo "Deploying $IMAGE to namespace $namespace"
  kubectl apply -f "$MANIFESTS_FILE"

  if ! kubectl -n "$namespace" rollout status "deployment/$DEPLOYMENT" --timeout="$ROLLOUT_TIMEOUT"; then
    echo "The rollout of $IMAGE did not finish within $ROLLOUT_TIMEOUT; rolling back." >&2
    if kubectl -n "$namespace" rollout undo "deployment/$DEPLOYMENT"; then
      kubectl -n "$namespace" rollout status "deployment/$DEPLOYMENT" --timeout="$ROLLOUT_TIMEOUT" ||
        echo "The rollback did not finish either; inspect the deployment by hand." >&2
    else
      echo "There was no earlier revision to roll back to (a first deployment)." >&2
    fi
    exit 1
  fi

  echo "Smoke-checking $env"
  kubectl -n "$namespace" port-forward "svc/$DEPLOYMENT" 18080:80 >/dev/null 2>&1 &
  PORT_FORWARD_PID=$!
  disown "$PORT_FORWARD_PID"   # so killing it in `cleanup` does not print a job-status line
  local attempt
  for attempt in $(seq 1 30); do
    curl -fsS --max-time 3 http://127.0.0.1:18080/health >/dev/null 2>&1 && break
    [ "$attempt" -lt 30 ] || die "$env: GET /health did not answer through the service after the rollout"
    sleep 1
  done
  echo "GET /health: $(curl -fsS --max-time 5 http://127.0.0.1:18080/health)"
  # Not gating: /llm-check says whether a provider is configured, which a given environment may not want.
  echo "GET /llm-check: HTTP $(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:18080/llm-check)"
}

case "${1:-}" in
  deploy) [ $# -eq 2 ] || usage; deploy "$2" ;;
  check) [ $# -eq 1 ] || usage; check ;;
  *) usage ;;
esac
